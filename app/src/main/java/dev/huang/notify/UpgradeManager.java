package dev.huang.notify;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Verified, opt-in self updates. No unrelated package can be installed through this component. */
final class UpgradeManager {
  static final int JOB = 8430, NOTICE = 8431;
  static final long MAX_APK = 100L * 1024 * 1024;
  static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newSingleThreadExecutor();
  static volatile Intent confirmation;
  private static final AtomicBoolean RECOVERED = new AtomicBoolean();
  private static final AtomicBoolean INSTALLING = new AtomicBoolean();
  static SharedPreferences prefs(Context c) { return c.getSharedPreferences("upgrade", 0); }
  static boolean automatic(Context c) { return prefs(c).getBoolean("download", false); }
  static boolean autoInstall(Context c) { return prefs(c).getBoolean("install", false); }
  static File apk(Context c) { return new File(c.getFilesDir(), "update.apk"); }
  static String status(Context c) { return prefs(c).getString("status", "尚未下載更新"); }
  static void stage(Context c,String stage,String text) { prefs(c).edit().putString("stage",stage).putString("status",text).apply(); }
  static void status(Context c, String text) { prefs(c).edit().putString("status", text).apply(); }
  static void configure(Context c, boolean download, boolean install) {
    prefs(c).edit().putBoolean("download", download).putBoolean("install", download && install).apply();
    if (!download) {
      JobInfo job = c.getSystemService(JobScheduler.class).getPendingJob(JOB);
      if (job != null && job.getExtras().getBoolean("automatic")) c.getSystemService(JobScheduler.class).cancel(JOB);
    }
  }
  static void request(Context c, UpdateChecker.Result result, boolean automatic) {
    if (INSTALLING.get() || "installing".equals(prefs(c).getString("stage",""))) { status(c,"請先完成目前的安裝程序"); return; }
    if (!result.installable()) { status(c, "此版本缺少完整驗證資訊，請到官方版本頁下載"); return; }
    if (automatic && !automatic(c)) return;
    if (prefs(c).getBoolean("ready", false) && result.tag.equals(prefs(c).getString("tag", "")) && apk(c).isFile()) return;
    JobInfo existing=c.getSystemService(JobScheduler.class).getPendingJob(JOB);
    if (existing!=null) {
      if (!automatic && existing.getExtras().getBoolean("automatic")) c.getSystemService(JobScheduler.class).cancel(JOB);
      else return;
    }
    prefs(c).edit().putBoolean("ready",false).putInt("progress",0).apply();
    PersistableBundle data = new PersistableBundle();
    data.putString("tag", result.tag); data.putString("url", result.apk); data.putString("sha", result.sha256); data.putLong("size", result.size); data.putBoolean("automatic", automatic);
    int scheduled = c.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(JOB, new ComponentName(c, UpgradeJob.class))
        .setRequiredNetworkType(automatic ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY)
        .setExtras(data).setPersisted(true).build());
    stage(c,scheduled==JobScheduler.RESULT_SUCCESS?"queued":"failed", scheduled == JobScheduler.RESULT_SUCCESS ? automatic ? "等待 Wi-Fi 等非計量網路下載更新" : "準備下載更新…" : "無法排程下載，請重試");
  }
  static boolean allowedVersion(Context c,String tag) {
    ReleaseVersion version=ReleaseVersion.parse(tag), current=ReleaseVersion.parse(BuildConfig.VERSION_NAME);
    return version!=null && current!=null && version.compareTo(current)>0 && (UpdateChecker.previews(c) || !version.isPreview());
  }
  static boolean allowedDownload(String url) {
    try { URI u = new URI(url); return "https".equals(u.getScheme()) && "github.com".equals(u.getHost()) && u.getPort() == -1
        && u.getRawUserInfo() == null && u.getRawQuery() == null && u.getRawFragment() == null
        && u.getRawPath().matches("/ziyuanh100/HuangNotify/releases/download/[A-Za-z0-9.+-]+/[A-Za-z0-9._+-]+\\.apk"); }
    catch (Exception e) { return false; }
  }
  static boolean redirectAllowed(URL url) {
    return "https".equals(url.getProtocol()) && url.getUserInfo() == null && (url.getPort() == -1 || url.getPort() == 443)
        && Arrays.asList("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com").contains(url.getHost());
  }
  static void download(Context c, PersistableBundle data, AtomicBoolean cancelled) throws Exception {
    String source = data.getString("url", ""), expected = data.getString("sha", ""), tag = data.getString("tag", ""); long size = data.getLong("size");
    if (!allowedVersion(c,tag) || !allowedDownload(source) || !expected.matches("[a-f0-9]{64}") || size <= 0 || size > MAX_APK) throw new IOException();
    File temp = new File(c.getFilesDir(), "update.part"); HttpURLConnection connection = null;
    try {
      URL url = new URL(source);
      for (int redirects = 0; ; redirects++) {
        if (!redirectAllowed(url) || redirects > 5) throw new IOException("不允許的下載位置");
        connection = (HttpURLConnection) url.openConnection(); connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000); connection.setReadTimeout(15000); connection.setRequestProperty("User-Agent", "HuangNotify-Updater");
        int response = connection.getResponseCode();
        if (response == 301 || response == 302 || response == 303 || response == 307 || response == 308) {
          String location = connection.getHeaderField("Location"); if (location == null) throw new IOException();
          url = new URL(url, location); connection.disconnect(); connection = null; continue;
        }
        if (response != 200) throw new IOException(); break;
      }
      long received = 0, deadline = SystemClock.elapsedRealtime() + 10 * 60 * 1000; int lastPercent = -1;
      try (InputStream input = connection.getInputStream(); OutputStream out = new FileOutputStream(temp)) {
        byte[] buffer = new byte[16384]; int n;
        while ((n = input.read(buffer)) != -1) {
          if (cancelled.get() || (data.getBoolean("automatic") && !automatic(c)) || SystemClock.elapsedRealtime() > deadline) throw new InterruptedIOException();
          received += n; if (received > size) throw new IOException(); out.write(buffer, 0, n);
          int percent = (int)(received * 100 / size);
          if (percent / 5 != lastPercent / 5 || lastPercent < 0) { lastPercent = percent; prefs(c).edit().putString("stage","downloading").putInt("progress",percent).putString("status","正在下載更新 "+percent+"%").apply(); notice(c, "正在下載更新", percent + "%", null); }
        }
      }
      if (received != size || cancelled.get() || !allowedVersion(c,tag)) throw new IOException();
      verify(c, temp, expected, tag);
      if (apk(c).exists() && !apk(c).delete()) throw new IOException();
      if (!temp.renameTo(apk(c))) throw new IOException();
      prefs(c).edit().putBoolean("ready", true).putString("tag", tag).putString("sha", expected).apply();
      stage(c,"ready", tag + " 已下載並通過驗證，點選安裝更新"); notice(c, "更新已準備好", "點選開啟 HuangNotify 完成更新", null);
      if (data.getBoolean("automatic") && automatic(c) && autoInstall(c) && Build.VERSION.SDK_INT >= 31 && c.getPackageManager().canRequestPackageInstalls()) install(c, true);
    } finally { if (connection != null) connection.disconnect(); temp.delete(); }
  }
  static void verify(Context c, File file, String sha, String tag) throws Exception {
    if (!sha.matches("[a-f0-9]{64}") || file.length() <= 0 || file.length() > MAX_APK) throw new IOException("更新檔不完整");
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = new FileInputStream(file)) { byte[] buffer = new byte[16384]; int n; while ((n=input.read(buffer))!=-1) digest.update(buffer,0,n); }
    StringBuilder hex = new StringBuilder(); for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT,"%02x",b & 255));
    if (!sha.equals(hex.toString())) throw new SecurityException("更新檔驗證失敗");
    PackageManager pm = c.getPackageManager(); int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    PackageInfo candidate = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags), installed = pm.getPackageInfo(c.getPackageName(), flags);
    validateInfo(candidate, installed, tag);
  }
  @SuppressWarnings("deprecation")
  static void validateInfo(PackageInfo candidate, PackageInfo installed, String tag) {
    if (candidate == null || !installed.packageName.equals(candidate.packageName)) throw new SecurityException("套件名稱不符");
    long next = Build.VERSION.SDK_INT >= 28 ? candidate.getLongVersionCode() : candidate.versionCode;
    long current = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
    ReleaseVersion nextVersion = ReleaseVersion.parse(candidate.versionName), advertised = ReleaseVersion.parse(tag), old = ReleaseVersion.parse(installed.versionName);
    if (next <= current || nextVersion == null || advertised == null || old == null || nextVersion.compareTo(advertised) != 0 || nextVersion.compareTo(old) <= 0)
      throw new SecurityException("拒絕舊版本或版本資訊不符的更新");
    Signature[] a = Build.VERSION.SDK_INT >= 28 && candidate.signingInfo != null ? candidate.signingInfo.getApkContentsSigners() : candidate.signatures;
    Signature[] b = Build.VERSION.SDK_INT >= 28 && installed.signingInfo != null ? installed.signingInfo.getApkContentsSigners() : installed.signatures;
    if (a == null || b == null || a.length != 1 || b.length != 1 || !a[0].equals(b[0])) throw new SecurityException("更新簽章與已安裝版本不符");
  }
  static void install(Context context, boolean silent) {
    Context c = context.getApplicationContext();
    if (!INSTALLING.compareAndSet(false, true)) return;
    stage(c,"installing","正在驗證與準備安裝更新…");
    IO.execute(() -> {
      PackageInstaller installer = c.getPackageManager().getPackageInstaller(); int sessionId = -1;
      try {
        if (!allowedVersion(c,prefs(c).getString("tag",""))) throw new SecurityException("更新頻道已變更或版本過舊");
        if (!prefs(c).getBoolean("ready",false)) throw new IOException("請先下載更新");
        if (!c.getPackageManager().canRequestPackageInstalls()) { stage(c,"ready","請先允許 HuangNotify 安裝更新"); return; }
        if (silent && (!automatic(c) || !autoInstall(c))) { stage(c,"ready","更新已準備好，可手動安裝"); return; }
        verify(c, apk(c), prefs(c).getString("sha",""), prefs(c).getString("tag",""));
        int previous = prefs(c).getInt("session", -1);
        if (previous != -1) try { installer.abandonSession(previous); } catch (Exception ignored) { }
        confirmation = null;
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName()); params.setSize(apk(c).length());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(silent ? PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED : PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        sessionId = installer.createSession(params);
        prefs(c).edit().putInt("session",sessionId).apply();
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
          try (InputStream in = new FileInputStream(apk(c)); OutputStream out = session.openWrite("base.apk",0,apk(c).length())) {
            byte[] buffer = new byte[16384]; int n; while ((n=in.read(buffer))!=-1) out.write(buffer,0,n); session.fsync(out);
          }
          Intent receiver = new Intent(c, InstallStatusReceiver.class).setAction(c.getPackageName()+".INSTALL_STATUS").setData(android.net.Uri.parse("huangnotify://install/"+sessionId));
          int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
          PendingIntent result = PendingIntent.getBroadcast(c, sessionId, receiver, flags);
          status(c,"已交由 Android 處理更新；如需確認，請點選更新通知");
          if (silent && (!automatic(c) || !autoInstall(c))) {
            installer.abandonSession(sessionId); prefs(c).edit().remove("session").apply();
            stage(c,"retry","自動安裝已關閉，可手動安裝已下載更新"); return;
          }
          session.commit(result.getIntentSender());
        }
      } catch (Exception e) {
        if (sessionId != -1) try { installer.abandonSession(sessionId); } catch (Exception ignored) { }
        prefs(c).edit().putBoolean("ready",false).apply();
        stage(c,"failed","安裝未完成：請確認更新檔、簽章、安裝權限或重新下載");
      } finally { INSTALLING.set(false); }
    });
  }
  /** A lost confirmation Intent cannot be recovered from preferences. Require an explicit retry. */
  static void recover(Context c) {
    if (!RECOVERED.compareAndSet(false,true) || INSTALLING.get()) return;
    recoverInterrupted(c);
  }
  static void recoverInterrupted(Context c) {
    String stage=prefs(c).getString("stage","");
    if (stage.equals("installing") || (stage.equals("confirm") && confirmation==null)) {
      PackageInstaller installer=c.getPackageManager().getPackageInstaller();
      int id=prefs(c).getInt("session",-1);
      try {
        PackageInstaller.SessionInfo session=installer.getSessionInfo(id);
        if (session!=null && !session.isSealed()) { installer.abandonSession(id); prefs(c).edit().remove("session").apply(); }
      } catch (Exception ignored) { }
      boolean ready=prefs(c).getBoolean("ready",false) && apk(c).isFile();
      prefs(c).edit().putBoolean("ready",ready).apply();
      stage(c,ready?"retry":"failed",ready?"上次安裝程序中斷；若 Android 尚未完成，可點選再次安裝":"更新程序中斷，請重新下載更新");
    } else if ((stage.equals("queued") || stage.equals("downloading")) && c.getSystemService(JobScheduler.class).getPendingJob(JOB)==null) {
      prefs(c).edit().putBoolean("ready",false).apply(); stage(c,"failed","下載已中斷，請重新下載更新");
    }
  }
  static void reconcile(Context c) {
    ReleaseVersion pending=ReleaseVersion.parse(prefs(c).getString("tag","")),current=ReleaseVersion.parse(BuildConfig.VERSION_NAME);
    if (pending!=null && current!=null && pending.compareTo(current)<=0) {
      apk(c).delete(); UpdateCompletion.mark(c);
    }
  }
  static void notice(Context c, String title, String message, Intent confirm) {
    NotificationManager manager = c.getSystemService(NotificationManager.class);
    manager.createNotificationChannel(new NotificationChannel("update_downloads","更新下載與安裝",NotificationManager.IMPORTANCE_DEFAULT));
    Intent open = confirm == null ? new Intent(c,MainActivity.class) : new Intent(c,InstallConsentActivity.class).putExtra("confirmation",confirm);
    open.putExtra("open_updates",true);
    PendingIntent action = PendingIntent.getActivity(c,NOTICE,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return;
    try { manager.notify(NOTICE,new Notification.Builder(c,"update_downloads").setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(title).setContentText(message).setContentIntent(action).setOnlyAlertOnce(true).setAutoCancel(true).build()); }
    catch (SecurityException ignored) { }
  }
}
