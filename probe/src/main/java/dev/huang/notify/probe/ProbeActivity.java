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
      String kind=getIntent().getStringExtra("kind");
      Notification.Builder builder=new Notification.Builder(this,"test").setSmallIcon(android.R.drawable.ic_dialog_info);
      if("screenshot".equals(kind)) {
        android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(4,4,android.graphics.Bitmap.Config.ARGB_8888);
        image.eraseColor(android.graphics.Color.GRAY);
        builder.setContentTitle("截圖已儲存").setGroup("screenshot-only").setGroupSummary(true)
            .setStyle(new Notification.BigPictureStyle().bigPicture(image));
      } else builder.setContentTitle(title==null?"通知保存測試":title).setContentText(text==null?"即使通知消失，這段內容仍會留下。":text)
          .setStyle(new Notification.BigTextStyle().bigText(text==null?"即使通知消失，這段內容仍會留下。":text));
      manager.notify(id,builder.build());
    }
    finish();
  }
}
