package dev.huang.notify;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

/** Persisted, skippable permission flow; never treats returning from Settings as permission granted. */
final class PermissionGuide {
  private final Activity activity;
  AppDialog dialog;
  PermissionGuide(Activity activity) { this.activity = activity; }
  private SharedPreferences prefs() { return activity.getSharedPreferences("permission_guide", Activity.MODE_PRIVATE); }
  private void step(int value) { prefs().edit().putInt("step", value).apply(); }
  private boolean access() {
    if (Build.VERSION.SDK_INT >= 27) return activity.getSystemService(NotificationManager.class)
        .isNotificationListenerAccessGranted(new ComponentName(activity, CaptureService.class));
    String value = Settings.Secure.getString(activity.getContentResolver(), "enabled_notification_listeners");
    if (value == null) return false;
    for (String item : value.split(":")) if (new ComponentName(activity, CaptureService.class).equals(ComponentName.unflattenFromString(item))) return true;
    return false;
  }
  void restart() { if (dialog != null && dialog.isShowing()) return; step(0); resume(); }
  void resume() {
    if (activity.isFinishing() || activity.isDestroyed() || (dialog != null && dialog.isShowing())) return;
    int step = prefs().getInt("step", 0);
    if (step == 3) return;
    if (step < 2 && access()) { step(2); step = 2; }
    if (step < 2) {
      final boolean returned = step == 1;
      dialog = new AppDialog.Builder(activity)
          .setTitle(returned ? "通知存取權還沒開啟" : "先讓通知好好留下")
          .setMessage("步驟 1 / 2 · 保存通知\n\nHuangNotify 需要「通知存取權」，才能保存其他 App 顯示的通知文字。紀錄只存手機，不會上傳。\n\n接著會開啟 Android 系統設定，請啟用 HuangNotify。你可以稍後再設定；尚未授權時不會記錄通知。")
          .setPositiveButton(returned ? "再到系統設定" : "開始設定", (d, w) -> {
            step(1);
            try { openListenerSettings(activity); }
            catch (Exception e) { step(0); new AppDialog.Builder(activity).setTitle("請手動開啟通知存取權").setMessage("可在手機設定搜尋「通知存取權」，並選擇 HuangNotify。").setPositiveButton("知道了", null).show(); }
          })
          .setNegativeButton(returned ? "先略過這一步" : "稍後設定", (d, w) -> {
            step(returned ? 2 : 3); if (returned) resume();
          }).create();
    } else {
      if (activity.getSystemService(NotificationManager.class).areNotificationsEnabled()
          && (Build.VERSION.SDK_INT<33 || activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED)) { step(3); return; }
      dialog = new AppDialog.Builder(activity).setTitle("允許更新完成通知")
          .setMessage("步驟 2 / 2 · 選用功能\n\n預設每次進入 App 都向 GitHub 檢查新版本，可在設定關閉，GitHub 會收到 IP 等一般連線資訊，不會收到你的通知內容。\n\n下一步會請求傳送更新通知的權限。不同意仍可保存通知，並在 App 內收到更新提示。備份則在使用時選擇檔案，不需開放全部檔案權限。")
          .setPositiveButton("允許更新通知", (d, w) -> {
            step(3);
            if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
              activity.requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 8413);
            else if (!activity.getSystemService(NotificationManager.class).areNotificationsEnabled())
              activity.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));

          }).setNegativeButton("暫時不用", (d, w) -> step(3)).create();
    }
    dialog.setOnCancelListener(d -> step(3));
    dialog.show();
  }
  static void openListenerSettings(Activity activity) {
    if (Build.VERSION.SDK_INT >= 30) {
      try {
        activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, new ComponentName(activity, CaptureService.class).flattenToString()));
        return;
      } catch (android.content.ActivityNotFoundException ignored) { }
    }
    activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
  }
  void backgroundHelp() {
    new AppDialog.Builder(activity).setTitle("讓背景記錄更穩定")
        .setMessage("若你的 Samsung 手機經常停止記錄，可到系統的 App 資訊 → 電池，查看是否限制 HuangNotify 的背景運作；並檢查是否被加入休眠或深度休眠清單。選項會因 One UI 版本而不同。\n\n這是選用調整，可能增加耗電，也無法保證完全不漏通知。不需要定位、聯絡人、相機或全部檔案權限。")
        .setPositiveButton("開啟 App 資訊", (d, w) -> {
          try { activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + activity.getPackageName()))); }
          catch (android.content.ActivityNotFoundException ignored) { }
        }).setNegativeButton("了解", null).show();
  }
}
