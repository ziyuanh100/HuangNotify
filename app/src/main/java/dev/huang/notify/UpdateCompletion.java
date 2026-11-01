package dev.huang.notify;

import android.app.*;
import android.content.*;
import android.os.Build;

/** The OS stops the old process on self update. Reopening is an explicit user action. */
public final class UpdateCompletion extends BroadcastReceiver {
  @Override public void onReceive(Context context,Intent intent) {
    if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) mark(context);
  }
  static void mark(Context c) {
    if (BuildConfig.VERSION_NAME.equals(UpgradeManager.prefs(c).getString("completed_version",""))) return;
    UpgradeManager.prefs(c).edit().putString("completed_version",BuildConfig.VERSION_NAME).putBoolean("completion_pending",true)
      .putBoolean("ready",false).putBoolean("show_progress",false).remove("stage").remove("session").remove("tag").remove("sha")
      .putString("status","已更新至 "+BuildConfig.VERSION_NAME+"，可重新開啟新版").apply();
    UpgradeManager.apk(c).delete(); new java.io.File(c.getFilesDir(),"update.part").delete();
    UpgradeManager.confirmation=null;
    NotificationManager manager=c.getSystemService(NotificationManager.class);
    manager.createNotificationChannel(new NotificationChannel("update_downloads","更新下載與安裝",NotificationManager.IMPORTANCE_DEFAULT));
    Intent open=new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("open_updates",true);
    PendingIntent pending=PendingIntent.getActivity(c,UpgradeManager.NOTICE,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    if (Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) return;
    try { manager.notify(UpgradeManager.NOTICE,new Notification.Builder(c,"update_downloads").setSmallIcon(R.drawable.ic_launcher)
      .setContentTitle("HuangNotify 更新完成").setContentText("點選重新開啟 "+BuildConfig.VERSION_NAME)
      .setContentIntent(pending).addAction(new Notification.Action.Builder(null,"重新開啟新版",pending).build()).setAutoCancel(true).build()); }
    catch(SecurityException ignored) { }
  }
  static void acknowledge(Context c) {
    UpgradeManager.prefs(c).edit().putBoolean("completion_pending",false).apply();
    c.getSystemService(NotificationManager.class).cancel(UpgradeManager.NOTICE);
  }
}
