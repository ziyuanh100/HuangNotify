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
    Diagnostics.event(this,"connected","");
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
          try { Diagnostics.event(this,"replayed",s.getPackageName());captureNotification(s,active); }
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
    Diagnostics.event(this,"disconnected","");
    requestRebind(new ComponentName(this, CaptureService.class));
  }

  @Override
  public void onDestroy() {
    connected = false;
    Diagnostics.event(this,"destroyed","");
    super.onDestroy();
  }

  @Override
  public void onNotificationPosted(StatusBarNotification sbn) {
    try {
      if(sbn!=null&&!getPackageName().equals(sbn.getPackageName()))Diagnostics.event(this,"received",sbn.getPackageName());
      captureNotification(sbn,null);
    } catch (RuntimeException e) {
      Diagnostics.event(this,"parse_failed",sbn==null?"":sbn.getPackageName());
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
      if(NotificationText.hasGroupChildren(sbn,snapshot)) {Diagnostics.event(this,"group_summary",sbn.getPackageName());return;}
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
            if(store.excluded(pkg)){Diagnostics.record(store,"excluded",pkg);return;}
            if("1".equals(store.setting("paused","0"))){Diagnostics.record(store,"paused",pkg);return;}
            NotificationImages.Result images=NotificationImages.read(this,n,!"0".equals(store.setting("save_images","1")));
            String result;
            try { result=store.captureEvent(key,pkg,app,savedTitle,savedText,posted,images); }
            catch(android.database.sqlite.SQLiteException e) {
              if(images.images.isEmpty())throw e;
              result=store.captureEvent(key,pkg,app,savedTitle,savedText,posted,NotificationImages.Result.empty("unavailable"));
              Diagnostics.record(store,"image_unavailable",pkg);
            }
            Diagnostics.record(store,result,pkg);
            if("unavailable".equals(images.status))Diagnostics.record(store,"image_unavailable",pkg);
            if("partial".equals(images.status))Diagnostics.record(store,"image_partial",pkg);
            long now = System.currentTimeMillis();
            if (now - lastCleanup > 3600000L) {
              store.cleanup(Integer.parseInt(store.setting("retention", "0")));
              lastCleanup = now;
            }
          } catch (Exception e) {
            Diagnostics.record(this,"write_failed",pkg);
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
