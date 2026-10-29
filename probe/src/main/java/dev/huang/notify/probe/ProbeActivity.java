package dev.huang.notify.probe;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Bundle;

/** Test-only companion APK; never part of the distributed app. */
public final class ProbeActivity extends Activity {
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel("test", "測試通知", NotificationManager.IMPORTANCE_DEFAULT));
    int id = getIntent().getIntExtra("id", 42);
    if (getIntent().getBooleanExtra("cancel", false)) manager.cancel(id);
    else {
      String title = getIntent().getStringExtra("title"), text = getIntent().getStringExtra("text");
      manager.notify(
          id,
          new Notification.Builder(this, "test")
              .setSmallIcon(android.R.drawable.ic_dialog_info)
              .setContentTitle(title == null ? "通知保存測試" : title)
              .setContentText(text == null ? "即使通知消失，這段內容仍會留下。" : text)
              .setStyle(
                  new Notification.BigTextStyle().bigText(text == null ? "即使通知消失，這段內容仍會留下。" : text))
              .build());
    }
    finish();
  }
}
