package dev.huang.notify;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Recurring portable encrypted backups. Old files are pruned only after read-back verification. */
final class AutoBackup {
  static final int JOB = 8420;
  private static final java.util.concurrent.ExecutorService IO=java.util.concurrent.Executors.newSingleThreadExecutor();
  private static final AtomicBoolean RUNNING = new AtomicBoolean();
  interface Callback { void done(String message); }
  static SharedPreferences prefs(Context c) { return c.getSharedPreferences("auto_backup", 0); }
  static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", false); }
  static int keep(Context c) { return prefs(c).getInt("keep", 7); }
  static String status(Context c) {
    SharedPreferences p = prefs(c); String error = p.getString("error", "");
    String last = p.getLong("success", 0) == 0 ? "尚未成功備份" : "最後成功：" + new java.text.SimpleDateFormat("MM/dd HH:mm", Locale.TAIWAN).format(new Date(p.getLong("success", 0)));
    return (RUNNING.get() ? "正在加密與驗證備份…" : enabled(c) ? "每日加密備份 · 保留 " + keep(c) + " 份" : "尚未開啟") + "\n" + last + (error.isEmpty() ? "" : "\n" + error);
  }
  static void configure(Context c, Uri tree, char[] password, int keep) throws Exception {
    String wrapped = DeviceSecret.wrap(password);
    SharedPreferences p = prefs(c); String owner = p.getString("owner", UUID.randomUUID().toString());
    if (!tree.toString().equals(p.getString("tree",""))) p.edit().remove("success").apply();
    if (!p.edit().putString("tree", tree.toString()).putString("secret", wrapped).putString("owner", owner)
        .putInt("keep", keep).putBoolean("enabled", true).remove("error").commit()) throw new IOException("無法儲存備份設定");
    schedule(c);
  }
  static void disable(Context c) {
    prefs(c).edit().putBoolean("enabled", false).remove("secret").apply(); schedule(c);
  }
  static void schedule(Context c) {
    JobScheduler scheduler = c.getSystemService(JobScheduler.class);
    if (!enabled(c)) { scheduler.cancel(JOB); return; }
    if (scheduler.getPendingJob(JOB) == null) scheduler.schedule(new JobInfo.Builder(JOB, new ComponentName(c, BackupJob.class))
        .setPeriodic(UpdateChecker.DAY).setPersisted(true).setRequiresBatteryNotLow(true).build());
  }
  static void run(Context context, boolean force, AtomicBoolean cancelled, Callback callback) {
    Context c = context.getApplicationContext();
    if (!RUNNING.compareAndSet(false, true)) { callback.done("已有備份作業進行中"); return; }
    IO.execute(() -> {
      byte[] raw = null, encrypted = null; char[] password = null; String message;
      try {
        SharedPreferences p = prefs(c);
        if (!enabled(c)) throw new IOException("自動備份已關閉");
        long elapsed = System.currentTimeMillis() - p.getLong("success", 0);
        if (!force && elapsed >= 0 && elapsed < UpdateChecker.DAY) { callback.done("今天已成功備份"); return; }
        String tree = p.getString("tree", ""), secret = p.getString("secret", "");
        password = DeviceSecret.unwrap(secret);
        raw = Store.IO.submit(() -> Store.get(c).exportBackup().toString().getBytes(StandardCharsets.UTF_8)).get();
        encrypted = BackupCrypto.encrypt(raw, password);
        if (cancelled.get() || !enabled(c)) throw new InterruptedIOException();
        String prefix = "HuangNotify_auto_" + p.getString("owner", "") + "_";
        String name = prefix + String.format(Locale.ROOT, "%013d", System.currentTimeMillis()) + "_" + UUID.randomUUID() + ".hnbk";
        Destination destination = new Documents(c, Uri.parse(tree));
        boolean clean = saveVerified(destination, name, prefix, encrypted, keep(c), cancelled);
        p.edit().putLong("success", System.currentTimeMillis()).putString("error", clean ? "" : "新備份成功；部分舊檔無法清理，稍後可重試").apply();
        message = clean ? "加密備份已完成並驗證" : "備份成功，舊檔清理未完成";
      } catch (Exception e) {
        message = "備份未完成：請檢查資料夾權限、空間或重新設定密碼；既有備份保留";
        prefs(c).edit().putString("error", message).apply();
      } finally {
        if (raw != null) Arrays.fill(raw, (byte)0); if (encrypted != null) Arrays.fill(encrypted, (byte)0);
        if (password != null) Arrays.fill(password, '\0'); RUNNING.set(false);
      }
      callback.done(message);
    });
  }
  interface Destination {
    String create(String name) throws Exception;
    OutputStream write(String id) throws Exception;
    InputStream read(String id) throws Exception;
    Map<String,String> files() throws Exception;
    void delete(String id) throws Exception;
  }
  static boolean saveVerified(Destination destination, String name, String prefix, byte[] bytes, int keep, AtomicBoolean cancelled) throws Exception {
    if (keep < 1 || keep > 30) throw new IllegalArgumentException("保留份數不正確");
    String id = destination.create(name);
    try {
      try (OutputStream out = destination.write(id)) { if (out == null) throw new IOException();
        for (int offset = 0; offset < bytes.length; offset += 8192) {
          if (cancelled.get()) throw new InterruptedIOException(); out.write(bytes, offset, Math.min(8192, bytes.length - offset));
        } out.flush(); }
      MessageDigest digest = MessageDigest.getInstance("SHA-256"); int total = 0;
      try (InputStream in = destination.read(id)) { if (in == null) throw new IOException(); byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) != -1) { total += n; if (cancelled.get() || total > bytes.length) throw new IOException(); digest.update(buffer, 0, n); }
      }
      if (total != bytes.length || !MessageDigest.isEqual(digest.digest(), MessageDigest.getInstance("SHA-256").digest(bytes))) throw new IOException("備份驗證失敗");
    } catch (Exception e) { try { destination.delete(id); } catch (Exception ignored) { } throw e; }
    // Only this installation's generated filenames qualify. Never delete arbitrary user files.
    List<Map.Entry<String,String>> owned = new ArrayList<>();
    for (Map.Entry<String,String> file : destination.files().entrySet())
      if (file.getValue().matches(java.util.regex.Pattern.quote(prefix) + "[0-9]{13}_[0-9a-f-]{36}\\.hnbk")) owned.add(file);
    owned.sort((a,b) -> b.getValue().compareTo(a.getValue()));
    boolean clean = true;
    for (int i = keep; i < owned.size(); i++) { if (cancelled.get()) return false;
      if (owned.get(i).getKey().equals(id)) continue;
      try { destination.delete(owned.get(i).getKey()); } catch (Exception e) { clean = false; }
    }
    return clean;
  }
  static final class Documents implements Destination {
    final Context c; final Uri tree, root;
    Documents(Context c, Uri tree) { this.c = c; this.tree = tree; root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)); }
    public String create(String name) throws Exception {
      Uri uri = DocumentsContract.createDocument(c.getContentResolver(), root, "application/octet-stream", name);
      if (uri == null) throw new IOException(); return uri.toString();
    }
    public OutputStream write(String id) throws Exception { return c.getContentResolver().openOutputStream(Uri.parse(id), "wt"); }
    public InputStream read(String id) throws Exception { return c.getContentResolver().openInputStream(Uri.parse(id)); }
    public Map<String,String> files() throws Exception {
      Map<String,String> result = new HashMap<>();
      Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
      try (Cursor cursor = c.getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
        if (cursor == null) throw new IOException();
        while (cursor.moveToNext()) result.put(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)).toString(), cursor.getString(1));
      } return result;
    }
    public void delete(String id) throws Exception { if (!DocumentsContract.deleteDocument(c.getContentResolver(), Uri.parse(id))) throw new IOException(); }
  }
}
