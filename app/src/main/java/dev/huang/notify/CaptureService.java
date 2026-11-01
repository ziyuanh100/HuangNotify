package dev.huang.notify;

import android.app.Notification;
import android.content.ComponentName;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.HashSet;
import java.util.Set;

public final class CaptureService extends NotificationListenerService {
  public static volatile boolean connected;
  private long lastCleanup;

  @Override
  public void onListenerConnected() {
    connected = true;
    try {
      StatusBarNotification[] active = getActiveNotifications();
      if (active != null) {
        Set<String> keys = new HashSet<>();
        for (StatusBarNotification s : active) keys.add(s.getKey());
        Store.IO.execute(
            () -> {
              try {
                Store.get(this).reconcile(keys);
              } catch (Exception e) {
                Store.error = "無法整理通知狀態，請檢查儲存空間";
              }
            });
        for (StatusBarNotification s : active) {
          try { captureNotification(s,active); }
          catch(RuntimeException e) { Store.error="有一則通知格式無法讀取；其他通知會繼續記錄"; }
        }
      }
    } catch (RuntimeException e) {
      Store.error = "無法讀取目前通知，請重新連線";
    }
  }

  @Override
  public void onListenerDisconnected() {
    connected = false;
    requestRebind(new ComponentName(this, CaptureService.class));
  }

  @Override
  public void onDestroy() {
    connected = false;
    super.onDestroy();
  }

  @Override
  public void onNotificationPosted(StatusBarNotification sbn) {
    try {
      captureNotification(sbn,null);
    } catch (RuntimeException e) {
      Store.error = "有一則通知格式無法讀取；其他通知會繼續記錄";
    }
  }

  private void captureNotification(StatusBarNotification sbn,StatusBarNotification[] snapshot) {
    if (sbn == null || sbn.getPackageName().equals(getPackageName())) return;
    Notification n = sbn.getNotification();
    if(n==null) return;
    if((n.flags & Notification.FLAG_GROUP_SUMMARY)!=0) {
      if(snapshot==null && connected) try { snapshot=getActiveNotifications(); } catch(RuntimeException ignored) { }
      // A summary can be the only notification (e.g. a screenshot). Keep it in that case.
      if(NotificationText.hasGroupChildren(sbn,snapshot)) return;
    }
    NotificationText content=NotificationText.read(n);
    final String savedTitle=content.title, savedText=content.body;
    final String key = sbn.getKey(), pkg = sbn.getPackageName();
    final long posted = sbn.getPostTime();
    Store.IO.execute(
        () -> {
          try {
            String app = pkg;
            try {
              app =
                  getPackageManager()
                      .getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))
                      .toString();
            } catch (Exception ignored) {
            }
            Store store = Store.get(this);
            store.capture(key, pkg, app, savedTitle, savedText, posted);
            long now = System.currentTimeMillis();
            if (now - lastCleanup > 3600000L) {
              store.cleanup(Integer.parseInt(store.setting("retention", "0")));
              lastCleanup = now;
            }
          } catch (Exception e) {
            Store.error = "通知儲存失敗，請檢查手機剩餘空間";
          }
        });
  }

  @Override
  public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
    if (sbn != null)
      Store.IO.execute(
          () -> {
            try {
              Store.get(this).removed(sbn.getKey(), reason);
            } catch (Exception e) {
              Store.error = "通知狀態儲存失敗";
            }
          });
  }

}
