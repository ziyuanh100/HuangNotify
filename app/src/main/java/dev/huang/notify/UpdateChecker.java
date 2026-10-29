package dev.huang.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only public release metadata leaves this component. It never opens the notification database. */
public final class UpdateChecker {
  static final long DAY = 24L * 60 * 60 * 1000;
  static final int JOB = 8412, NOTICE = 8413;
  static final String CHANNEL = "app_updates";
  public static final String RELEASES = "https://github.com/ziyuanh100/HuangNotify/releases";
  private static final String API = "https://api.github.com/repos/ziyuanh100/HuangNotify/releases?per_page=100";
  private static final ExecutorService IO = Executors.newSingleThreadExecutor();
  public interface Callback { void done(Result result); }
  public static final class Result {
    public final String tag, url, message;
    Result(String tag, String url, String message) { this.tag = tag; this.url = url; this.message = message; }
    public boolean available() { return !tag.isEmpty(); }
  }
  private UpdateChecker() {}
  static SharedPreferences prefs(Context c) { return c.getSharedPreferences("updates", Context.MODE_PRIVATE); }
  public static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", false); }
  public static boolean previews(Context c) { return prefs(c).getBoolean("previews", true); }
  public static void enable(Context c, boolean value) {
    prefs(c).edit().putBoolean("enabled", value).apply();
    schedule(c);
    if (!value) c.getSystemService(NotificationManager.class).cancel(NOTICE);
  }
  public static void setPreviews(Context c, boolean value) {
    prefs(c).edit().putBoolean("previews", value).remove("tag").remove("url").remove("checked").remove("attempt").remove("error").apply();
    c.getSystemService(NotificationManager.class).cancel(NOTICE);
  }
  public static void schedule(Context c) {
    JobScheduler scheduler = c.getSystemService(JobScheduler.class);
    if (!enabled(c)) { scheduler.cancel(JOB); return; }
    if (scheduler.getPendingJob(JOB) == null)
      scheduler.schedule(new JobInfo.Builder(JOB, new ComponentName(c, UpdateJob.class))
          .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(DAY).setPersisted(true).build());
  }
  public static Result cached(Context c) {
    SharedPreferences p = prefs(c);
    String tag = p.getString("tag", ""), url = p.getString("url", "");
    ReleaseVersion v = ReleaseVersion.parse(tag), current = ReleaseVersion.parse(BuildConfig.VERSION_NAME);
    if (v != null && current != null && v.compareTo(current) > 0 && validReleaseUrl(url)
        && (previews(c) || !v.isPreview())) return new Result(tag, url, "已有新版本 " + tag);
    return new Result("", "", "目前版本 " + BuildConfig.VERSION_NAME);
  }
  static boolean validReleaseUrl(String url) {
    try {
      java.net.URI u = new java.net.URI(url);
      return "https".equals(u.getScheme()) && "github.com".equals(u.getHost())
          && u.getRawUserInfo() == null && u.getPort() == -1 && u.getRawQuery() == null
          && u.getRawFragment() == null && u.getRawPath().matches("/ziyuanh100/HuangNotify/releases/tag/[A-Za-z0-9.+-]+");
    } catch (Exception e) { return false; }
  }
  static Result select(JSONArray releases, String installed, boolean includePreviews) throws Exception {
    ReleaseVersion best = ReleaseVersion.parse(installed);
    if (best == null) throw new IllegalArgumentException("無法判斷目前版本");
    Result result = new Result("", "", "已是最新版本（所選更新頻道）");
    for (int i = 0; i < releases.length(); i++) {
      JSONObject r = releases.getJSONObject(i);
      String tag = r.optString("tag_name"), url = r.optString("html_url");
      ReleaseVersion v = ReleaseVersion.parse(tag);
      if (r.optBoolean("draft", true) || v == null || v.compareTo(best) <= 0 || !validReleaseUrl(url)
          || (!includePreviews && (r.optBoolean("prerelease", true) || v.isPreview()))) continue;
      JSONArray assets = r.optJSONArray("assets");
      boolean apk = false;
      if (assets != null) for (int j = 0; j < assets.length(); j++) {
        JSONObject a = assets.getJSONObject(j);
        String download = a.optString("browser_download_url");
        if (a.optString("name").endsWith(".apk") && a.optLong("size") > 0
            && "uploaded".equals(a.optString("state"))
            && download.startsWith("https://github.com/ziyuanh100/HuangNotify/releases/download/")) apk = true;
      }
      if (apk) { best = v; result = new Result(tag, url, "發現新版本 " + tag); }
    }
    return result;
  }
  public static void check(Context context, boolean manual, Callback callback) {
    Context c = context.getApplicationContext();
    IO.execute(() -> {
      Result result;
      try {
        if (!manual && !enabled(c)) { callback.done(cached(c)); return; }
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis(), last = p.getLong("checked", 0);
        long attempt = p.getLong("attempt", 0);
        long retryInterval = manual ? 60000 : 60L * 60 * 1000;
        if (now >= attempt && now - attempt < retryInterval && !p.getString("error", "").isEmpty()) {
          callback.done(new Result("", "", p.getString("error", ""))); return;
        }
        long interval = manual ? 60000 : DAY;
        if (now >= last && now - last < interval) {
          result = cached(c);
          if (!result.available()) result = new Result("", "", "最近已檢查，未發現新版本");
        } else {
          boolean includePreviews = previews(c);
          p.edit().putLong("attempt", now).apply();
          result = fetch(includePreviews);
          // A user may disable checks or change channel while the request is running.
          if (includePreviews == previews(c) && (manual || enabled(c)))
            p.edit().putString("tag", result.tag).putString("url", result.url).putLong("checked", now).remove("error").apply();
          else result = cached(c);
        }
        if (!manual && enabled(c)) notifyUpdate(c, result);
      } catch (Exception e) {
        result = new Result("", "", "暫時無法檢查更新，請稍後確認網路再試；通知紀錄不受影響");
        prefs(c).edit().putString("error", result.message).apply();
      }
      callback.done(result);
    });
  }
  private static Result fetch(boolean previews) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(API).openConnection();
    connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestProperty("Accept", "application/vnd.github+json");
    connection.setRequestProperty("User-Agent", "HuangNotify-UpdateChecker");
    try {
      if (connection.getResponseCode() != 200) throw new java.io.IOException("Release service unavailable");
      try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192]; int n;
        long deadline = android.os.SystemClock.elapsedRealtime() + 20000;
        while ((n = input.read(buffer)) != -1) {
          if (Thread.currentThread().isInterrupted() || android.os.SystemClock.elapsedRealtime() > deadline
              || output.size() + n > 2 * 1024 * 1024) throw new java.io.IOException("Response limit");
          output.write(buffer, 0, n);
        }
        return select(new JSONArray(output.toString(StandardCharsets.UTF_8.name())), BuildConfig.VERSION_NAME, previews);
      }
    } finally { connection.disconnect(); }
  }
  private static void notifyUpdate(Context c, Result result) {
    if (!result.available()) return;
    NotificationManager manager = c.getSystemService(NotificationManager.class);
    manager.createNotificationChannel(new NotificationChannel(CHANNEL, "App 版本更新", NotificationManager.IMPORTANCE_DEFAULT));
    if (android.os.Build.VERSION.SDK_INT >= 33
        && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
    if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL).getImportance() == NotificationManager.IMPORTANCE_NONE
        || result.tag.equals(prefs(c).getString("notified", ""))) return;
    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(result.url));
    PendingIntent pending = PendingIntent.getActivity(c, NOTICE, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    try {
      manager.notify(NOTICE, new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
          .setContentTitle("HuangNotify 有新版本")
          .setContentText(result.tag + " · 點選查看更新並下載")
          .setContentIntent(pending).setAutoCancel(true).build());
      prefs(c).edit().putString("notified", result.tag).apply();
    } catch (SecurityException ignored) { /* Permission may change between the check and notify. */ }
  }
}
