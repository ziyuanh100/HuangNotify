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
        android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(480,280,android.graphics.Bitmap.Config.ARGB_8888);
        image.eraseColor(0xffd9efe4);
        android.graphics.Canvas canvas=new android.graphics.Canvas(image); android.graphics.Paint paint=new android.graphics.Paint(3);
        paint.setColor(0xff146c59); canvas.drawRect(0,160,480,280,paint); paint.setColor(0xffefc875); canvas.drawCircle(370,78,36,paint);
        paint.setColor(0xff172c24); paint.setTextSize(28); canvas.drawText("HuangNotify",28,62,paint);
        paint.setTextSize(18); canvas.drawText("Notification image fixture",28,96,paint);
        builder.setContentTitle("截圖已儲存").setGroup("screenshot-only").setGroupSummary(true)
            .setStyle(new Notification.BigPictureStyle().bigPicture(image));
      } else if("message-image".equals(kind) || "denied-image".equals(kind)) {
        android.graphics.Bitmap picture=android.graphics.Bitmap.createBitmap(96,64,android.graphics.Bitmap.Config.ARGB_8888); picture.eraseColor(0xff146c59);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(getCacheDir(),"notification-picture.png"))) { picture.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out); }
        catch(java.io.IOException e) { throw new IllegalStateException(e); }
        picture.recycle();
        android.net.Uri uri=android.net.Uri.parse("content://dev.huang.notify.probe.images/"+("denied-image".equals(kind)?"missing":"picture"));
        Notification.MessagingStyle.Message message=new Notification.MessagingStyle.Message("圖片說明",System.currentTimeMillis(),(CharSequence)"相機").setData("image/png",uri);
        builder.setStyle(new Notification.MessagingStyle("你").addMessage(message));
      } else builder.setContentTitle(title==null?"通知保存測試":title).setContentText(text==null?"即使通知消失，這段內容仍會留下。":text)
          .setStyle(new Notification.BigTextStyle().bigText(text==null?"即使通知消失，這段內容仍會留下。":text));
      manager.notify(id,builder.build());
    }
    finish();
  }
}
