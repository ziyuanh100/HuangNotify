package dev.huang.notify;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.*;
import org.json.JSONArray;

/** Thumbnails load only for the opened detail, never for the complete notification list. */
final class ImageGallery {
  static void add(Activity activity,Ui u,LinearLayout body,JSONArray images,String status,boolean thumbnails) {
    if(images==null)images=new JSONArray();
    if(images.length()==0 && "none".equals(status))return;
    u.gap(body,12);
    body.addView(u.text(NotificationImages.label(status,images.length()),12,u.muted,false));
    if(images.length()==0)return;
    LinearLayout items=u.row(); items.setGravity(android.view.Gravity.TOP);
    HorizontalScrollView scroll=new HorizontalScrollView(activity); scroll.setHorizontalScrollBarEnabled(false); scroll.addView(items);
    body.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
    for(int i=0;i<images.length();i++) {
      String hash=images.optString(i); int index=i+1;
      LinearLayout card=u.column(); card.setPadding(u.d(4),u.d(8),u.d(8),u.d(4));
      TextView label=u.button("查看圖片 "+index,false); card.addView(label);
      Runnable open=()->activity.startActivity(new Intent(activity,ImageViewerActivity.class).putExtra("image_hash",hash));
      label.setOnClickListener(v->open.run());
      if(thumbnails) {
        ImageView preview=new ImageView(activity); preview.setBackground(u.shape(u.pale,12)); preview.setClipToOutline(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER); preview.setContentDescription("通知圖片 "+index+"，點擊查看"); preview.setFocusable(true);
        card.addView(preview,new LinearLayout.LayoutParams(u.d(160),u.d(140))); preview.setOnClickListener(v->open.run());
        Store.IO.execute(()->{
          Bitmap image=null;
          try {
            byte[] bytes=Store.get(activity).image(hash);
            if(bytes!=null) { BitmapFactory.Options options=new BitmapFactory.Options(); options.inJustDecodeBounds=true; BitmapFactory.decodeByteArray(bytes,0,bytes.length,options); options.inJustDecodeBounds=false; options.inSampleSize=1;
              while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>400)options.inSampleSize*=2;
              image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options); }
          } catch(Exception | OutOfMemoryError ignored) { }
          Bitmap result=image;
          activity.runOnUiThread(()->{ if(activity.isDestroyed() || !preview.isAttachedToWindow()) { if(result!=null)result.recycle(); return; }
            if(result!=null)preview.setImageBitmap(result); else preview.setContentDescription("圖片已刪除或無法讀取"); });
        });
      }
      items.addView(card);
    }
  }
}
