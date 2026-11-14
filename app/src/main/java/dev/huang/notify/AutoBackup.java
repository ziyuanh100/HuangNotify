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
  static final java.util.concurrent.ExecutorService IO=java.util.concurrent.Executors.newSingleThreadExecutor();
  private static final AtomicBoolean RUNNING = new AtomicBoolean();
  private static final Object LOCK = new Object();
  private static AtomicBoolean active;
  private static volatile BackupTask activeTask;
  interface Callback { void done(String message); }
  static SharedPreferences prefs(Context c) { return c.getSharedPreferences("auto_backup", 0); }
  static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", false); }
  static int keep(Context c) { return prefs(c).getInt("keep", 7); }
  static boolean running() { return RUNNING.get(); }
  static BackupTask task() { return activeTask; }
  static String folderName(Context c) { return prefs(c).getString("folder_name", "已選擇的資料夾"); }
  static Uri folder(Context c) { return Uri.parse(prefs(c).getString("tree", "")); }
  static void cancel() { synchronized (LOCK) { if (active != null) active.set(true); } }

  static void setKeep(Context c, int keep) {
    if (keep != 3 && keep != 7 && keep != 14 && keep != 30) throw new IllegalArgumentException("保留份數不正確");
    synchronized (LOCK) { prefs(c).edit().putInt("keep", keep).apply(); }
  }

  static void configure(Context c, Uri tree, char[] password, int keep) throws Exception {
    if (keep < 1 || keep > 30) throw new IllegalArgumentException("保留份數不正確");
    String wrapped = DeviceSecret.wrap(password);
    String folderName = BackupReceipt.location(c, tree);
    synchronized (LOCK) {
      SharedPreferences p = prefs(c);
      SharedPreferences.Editor edit = folderEdit(p, tree, folderName);
      if (active != null) active.set(true);
      if (!edit.putString("secret", wrapped)
          .putString("owner", p.getString("owner", UUID.randomUUID().toString()))
          .putLong("generation", p.getLong("generation",0)+1).putInt("keep", keep)
          .putBoolean("enabled", true).remove("error").commit()) throw new IOException("無法儲存備份設定");
    }
    schedule(c);
  }
  static void moveFolder(Context c, Uri tree) throws IOException {
    String folderName = BackupReceipt.location(c, tree);
    synchronized (LOCK) {
      SharedPreferences p = prefs(c);
      if (!p.getBoolean("enabled", false)) throw new IOException("自動備份尚未開啟");
      if (active != null) active.set(true);
      if (!folderEdit(p, tree, folderName).remove("error")
          .putLong("generation", p.getLong("generation", 0)+1).commit()) throw new IOException("無法儲存備份位置");
    }
  }

  private static SharedPreferences.Editor folderEdit(SharedPreferences p, Uri tree, String name) {
    SharedPreferences.Editor edit = p.edit().putString("tree", tree.toString()).putString("folder_name", name);
    if (!tree.toString().equals(p.getString("tree", ""))) {
      if (!p.contains("verified_time") && p.contains("success")) edit.putLong("verified_time", p.getLong("success", 0));
      edit.remove("success");
    }
    return edit;
  }
  static void disable(Context c) {
    synchronized (LOCK) {
      SharedPreferences p=prefs(c);
      if (active != null) active.set(true);
      p.edit().putBoolean("enabled", false).remove("secret").remove("error")
          .putLong("generation",p.getLong("generation",0)+1).apply();
    }
    schedule(c);
  }
  static void schedule(Context c) {
    JobScheduler scheduler = c.getSystemService(JobScheduler.class);
    if (!enabled(c)) { scheduler.cancel(JOB); return; }
    if (scheduler.getPendingJob(JOB) == null) scheduler.schedule(new JobInfo.Builder(JOB, new ComponentName(c, BackupJob.class))
        .setPeriodic(UpdateChecker.DAY).setPersisted(true).setRequiresBatteryNotLow(true).build());
  }
  static void run(Context context, boolean force, AtomicBoolean cancelled, Callback callback) {
    Context c = context.getApplicationContext();
    final long generation, success; final String tree, secret, owner;
    synchronized (LOCK) {
      if (!RUNNING.compareAndSet(false, true)) { callback.done("已有備份作業進行中"); return; }
      SharedPreferences p=prefs(c); active=cancelled;
      generation=p.getLong("generation",0); success=p.getLong("success",0);
      tree=p.getString("tree", ""); secret=p.getString("secret", ""); owner=p.getString("owner", "");
    }
    IO.execute(() -> {
      BackupFiles.Session session=null; char[] password = null; String message;
      BackupTask task=new BackupTask(cancelled);activeTask=task;
      try {
        if (cancelled.get() || !enabled(c)) throw new InterruptedIOException();
        long elapsed = System.currentTimeMillis() - success;
        if (!force && elapsed >= 0 && elapsed < UpdateChecker.DAY) message="今天已成功備份";
        else {
          password = DeviceSecret.unwrap(secret);
          session=BackupFiles.export(c,password,task);
          if (cancelled.get()) throw new InterruptedIOException();
          String prefix = "HuangNotify_auto_" + owner + "_";
          String name = prefix + String.format(Locale.ROOT, "%013d", System.currentTimeMillis()) + "_" + UUID.randomUUID() + ".hnbk";
          Documents destination = new Documents(c, Uri.parse(tree));
          String id = writeVerified(destination, name, session.ready, task, BackupFiles.expected(session,task));
          long completed = System.currentTimeMillis();
          int keep;
          synchronized (LOCK) {
            if (cancelled.get() || generation!=prefs(c).getLong("generation",0)) throw new InterruptedIOException();
            SharedPreferences p = prefs(c);
            BackupReceipt.record(p.edit().putLong("success", completed).remove("error"), completed,
                session.readyBytes, p.getString("folder_name", "已選擇的資料夾")).apply();
            keep = keep(c);
          }
          task.phase(BackupTask.PRUNE, 0);
          boolean clean = prune(destination, id, prefix, keep, cancelled);
          synchronized (LOCK) {
            if (!clean && !cancelled.get() && generation==prefs(c).getLong("generation",0))
              prefs(c).edit().putString("error", "新備份成功，部分舊檔未能清理").apply();
          }
          message = clean ? "加密備份已完成並驗證" : "備份成功，舊檔清理未完成";
        }
      } catch (Exception e) {
        synchronized (LOCK) {
          if (cancelled.get() || generation!=prefs(c).getLong("generation",0) || !enabled(c)) message="備份已停止；既有備份保留";
          else {
            message = "備份未完成："+BackupFiles.error(e)+"；既有備份保留";
            prefs(c).edit().putString("error", message).apply();
          }
        }
      } finally {
        if(session!=null)session.close();activeTask=null;
        if (password != null) Arrays.fill(password, '\0');
        synchronized (LOCK) { active=null; RUNNING.set(false); }
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
    if (cancelled.get()) throw new InterruptedIOException();
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
    return prune(destination,id,prefix,keep,cancelled);
  }
  static boolean saveVerified(Destination destination,String name,String prefix,File file,int keep,BackupTask task)throws Exception {
    if(keep<1||keep>30)throw new BackupProblem("保留份數不正確");task.check();long size=file.length();byte[] expected;
    task.phase("檢查備份檔",size);try(InputStream in=new FileInputStream(file)){expected=BackupFiles.digest(in,size,task);}
    return saveVerified(destination,name,prefix,file,keep,task,expected);
  }
  static boolean saveVerified(Destination destination, String name, String prefix, BackupFiles.Session session,
      int keep, BackupTask task) throws Exception {
    return saveVerified(destination,name,prefix,session.ready,keep,task,BackupFiles.expected(session,task));
  }
  private static boolean saveVerified(Destination destination,String name,String prefix,File file,int keep,BackupTask task,byte[] expected)throws Exception {
    if(keep<1||keep>30)throw new BackupProblem("保留份數不正確");
    return prune(destination,writeVerified(destination,name,file,task,expected),prefix,keep,task.cancelled);
  }
  private static String writeVerified(Destination destination,String name,File file,BackupTask task,byte[] expected)throws Exception {
    task.check();long size=file.length();String id=destination.create(name);
    try {
      task.phase(BackupTask.SAVE,size);
      try(InputStream in=new FileInputStream(file);OutputStream out=destination.write(id)){if(out==null)throw new IOException();BackupFiles.copy(in,out,BackupStreamCrypto.MAX_FILE_BYTES,task);}
      task.phase(BackupTask.VERIFY,size);
      try(InputStream in=destination.read(id)){if(in==null||!MessageDigest.isEqual(expected,BackupFiles.digest(in,size,task)))throw new IOException("備份驗證失敗");}
    }catch(Exception e){try{destination.delete(id);}catch(Exception ignored){}throw e;}
    return id;
  }
  private static boolean prune(Destination destination,String id,String prefix,int keep,AtomicBoolean cancelled)throws Exception {
    // Only this installation's generated filenames qualify. Never delete arbitrary user files.
    List<Map.Entry<String,String>> owned = new ArrayList<>();
    Map<String,String> files;
    try { files=destination.files(); } catch (Exception e) { return false; }
    for (Map.Entry<String,String> file : files.entrySet())
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
