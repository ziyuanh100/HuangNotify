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
    public final String tag, url, message, apk, sha256;
    public final long size;
    Result(String tag, String url, String message) { this(tag,url,message,"","",0); }
    Result(String tag, String url, String message, String apk, String sha256, long size) {
      this.tag=tag; this.url=url; this.message=message; this.apk=apk; this.sha256=sha256; this.size=size;
    }
    public boolean installable() { return available() && UpgradeManager.allowedDownload(apk) && sha256.matches("[a-f0-9]{64}") && size > 0 && size <= UpgradeManager.MAX_APK; }
    public boolean available() { return !tag.isEmpty(); }
  }
  private UpdateChecker() {}
  static SharedPreferences prefs(Context c) { return c.getSharedPreferences("updates", Context.MODE_PRIVATE); }
  public static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", true); }
  public static boolean previews(Context c) { return prefs(c).getBoolean("previews", false); }
  public static void enable(Context c, boolean value) {
    SharedPreferences p=prefs(c);
    p.edit().putBoolean("enabled", value).putInt("generation",p.getInt("generation",0)+1).apply();
    schedule(c);
    if (!value) { c.getSystemService(NotificationManager.class).cancel(NOTICE); UpgradeManager.configure(c,false,false); }
  }
  public static void setPreviews(Context c, boolean value) {
    c.getSystemService(JobScheduler.class).cancel(UpgradeManager.JOB);
    ReleaseVersion downloaded=ReleaseVersion.parse(UpgradeManager.prefs(c).getString("tag",""));
    if (!value && downloaded!=null && downloaded.isPreview()) {
      UpgradeManager.prefs(c).edit().putBoolean("ready",false).apply();
      UpgradeManager.status(c,"已切換正式版頻道，請重新檢查更新");
    }
    prefs(c).edit().putBoolean("previews", value).putInt("generation",prefs(c).getInt("generation",0)+1).remove("etag").remove("tag").remove("url").remove("checked").remove("attempt").remove("error").apply();
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
        && (previews(c) || !v.isPreview())) return new Result(tag, url, "已有新版本 " + tag, p.getString("apk",""), p.getString("sha",""), p.getLong("size",0));
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
      boolean apk = false; String apkUrl="", sha=""; long size=0;
      if (assets != null) for (int j = 0; j < assets.length(); j++) {
        JSONObject a = assets.getJSONObject(j);
        String download = a.optString("browser_download_url");
        if (a.optString("name").endsWith(".apk") && a.optLong("size") > 0
            && "uploaded".equals(a.optString("state"))
            && UpgradeManager.allowedDownload(download)) {
          apk = true;
          String digest = a.optString("digest");
          if (apkUrl.isEmpty() || digest.matches("sha256:[a-f0-9]{64}")) {
            apkUrl=download; size=a.optLong("size"); sha=digest.startsWith("sha256:")?digest.substring(7):"";
          }
        }
      }
      if (apk) { best = v; result = new Result(tag, url, "發現新版本 " + tag, apkUrl, sha, size); }
    }
    return result;
  }
  static final class NetworkResult {
    final Result result; final String etag;
    NetworkResult(Result result,String etag) { this.result=result; this.etag=etag; }
  }
  interface Source { NetworkResult fetch(Context c,boolean previews) throws Exception; }
  static volatile Source source=UpdateChecker::fetch;
  public static void check(Context c,boolean manual,Callback callback) { check(c,manual,false,callback); }
  public static void checkOnEntry(Context c,Callback callback) { check(c,false,true,callback); }
  private static void check(Context context,boolean manual,boolean entry,Callback callback) {
    Context c=context.getApplicationContext();
    IO.execute(()->{
      Result result;
      try {
        if (!manual && !enabled(c)) { callback.done(new Result("","","自動檢查已關閉")); return; }
        SharedPreferences p=prefs(c); long now=System.currentTimeMillis(), last=p.getLong("checked",0), attempt=p.getLong("attempt",0);
        boolean includePreviews=previews(c); int generation=p.getInt("generation",0);
        long retry=manual?60000:60L*60*1000;
        if (now<p.getLong("retry_after",0)) throw new java.io.IOException("版本服務要求稍後重試");
        if (!entry && now>=attempt && now-attempt<retry && !p.getString("error","").isEmpty()) {
          callback.done(new Result("","",p.getString("error",""))); return;
        }
        long interval=manual?60000:DAY;
        if (!entry && now>=last && now-last<interval) {
          result=cached(c);
          if (!result.available()) result=new Result("","","最近已檢查，未發現新版本");
        } else {
          p.edit().putLong("attempt",now).apply();
          NetworkResult network=source.fetch(c,includePreviews); result=network.result;
          if (includePreviews==previews(c) && generation==p.getInt("generation",0) && (manual||enabled(c))) {
            p.edit().putString("tag",result.tag).putString("url",result.url).putString("apk",result.apk)
              .putString("sha",result.sha256).putLong("size",result.size).putString("etag",network.etag)
              .putLong("checked",now).remove("error").remove("retry_after").apply();
          } else result=new Result("","","更新偏好已變更");
        }
        if (!manual && enabled(c)) {
          if (!entry) notifyUpdate(c,result);
          if (result.installable() && UpgradeManager.automatic(c)) UpgradeManager.request(c,result,true);
        }
      } catch(Exception e) {
        String message="暫時無法檢查更新，請稍後確認網路再試；通知紀錄不受影響";
        prefs(c).edit().putString("error",message).apply();
        Result known=cached(c);
        result=entry && enabled(c) && known.available()
          ?new Result(known.tag,known.url,"上次發現 "+known.tag+"；目前無法確認最新狀態",known.apk,known.sha256,known.size)
          :new Result("","",message);
      }
      callback.done(result);
    });
  }
  private static NetworkResult fetch(Context c,boolean previews) throws Exception {
    HttpURLConnection connection=(HttpURLConnection)new URL(API).openConnection();
    connection.setConnectTimeout(10000); connection.setReadTimeout(10000); connection.setInstanceFollowRedirects(false);
    connection.setRequestProperty("Accept","application/vnd.github+json");
    connection.setRequestProperty("User-Agent","HuangNotify-UpdateChecker");
    String etag=prefs(c).getString("etag","");
    if (!etag.isEmpty()) connection.setRequestProperty("If-None-Match",etag);
    try {
      int response=connection.getResponseCode();
      if (response==304) return new NetworkResult(cached(c),etag);
      if (response==403 || response==429) {
        long until=System.currentTimeMillis()+60000;
        try {
          String retry=connection.getHeaderField("Retry-After"), reset=connection.getHeaderField("X-RateLimit-Reset");
          if (retry!=null) until=Math.max(until,System.currentTimeMillis()+Long.parseLong(retry)*1000);
          if ("0".equals(connection.getHeaderField("X-RateLimit-Remaining")) && reset!=null) until=Math.max(until,Long.parseLong(reset)*1000);
        } catch(NumberFormatException ignored) { }
        prefs(c).edit().putLong("retry_after",Math.min(until,System.currentTimeMillis()+DAY)).apply();
      }
      if (response!=200) throw new java.io.IOException("Release service unavailable");
      try(InputStream input=connection.getInputStream(); ByteArrayOutputStream output=new ByteArrayOutputStream()) {
        byte[] buffer=new byte[8192]; int n; long deadline=android.os.SystemClock.elapsedRealtime()+20000;
        while((n=input.read(buffer))!=-1) {
          if (Thread.currentThread().isInterrupted() || android.os.SystemClock.elapsedRealtime()>deadline || output.size()+n>2*1024*1024) throw new java.io.IOException("Response limit");
          output.write(buffer,0,n);
        }
        String nextEtag=connection.getHeaderField("ETag");
        return new NetworkResult(select(new JSONArray(output.toString(StandardCharsets.UTF_8.name())),BuildConfig.VERSION_NAME,previews),nextEtag==null?"":nextEtag);
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
    Intent intent = new Intent(c,MainActivity.class).putExtra("open_updates",true);
    PendingIntent pending = PendingIntent.getActivity(c, NOTICE, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    try {
      manager.notify(NOTICE, new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
          .setContentTitle("HuangNotify 有新版本")
          .setContentText(result.tag + " · 點選開啟 App 更新")
          .setContentIntent(pending).setAutoCancel(true).build());
      prefs(c).edit().putString("notified", result.tag).apply();
    } catch (SecurityException ignored) { /* Permission may change between the check and notify. */ }
  }
}
