package dev.huang.notify;

import android.app.Notification;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Only notification attachments; never scans storage, loads avatars, or opens network URLs. */
final class NotificationImages {
  static final int MAX_IMAGES=4, MAX_EDGE=1440, MAX_IMAGE_BYTES=256*1024, MAX_SOURCE_BYTES=4*1024*1024;
  static final Set<String> STATUSES=new HashSet<>(Arrays.asList("none","saved","partial","unavailable","disabled"));
  private static final ThreadPoolExecutor READERS=new ThreadPoolExecutor(0,1,10,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"notification-image-reader"); t.setDaemon(true); return t;});
  static final class Attachment {
    final String hash; final byte[] data; final int width,height;
    Attachment(byte[] data,int width,int height) { this.data=data; this.width=width; this.height=height; this.hash=sha(data); }
  }
  static final class Result {
    final List<Attachment> images; final String status;
    Result(List<Attachment> images,String status) { this.images=Collections.unmodifiableList(new ArrayList<>(images)); this.status=status; }
    static Result empty(String status) { return new Result(Collections.emptyList(),status); }
  }
  static String sha(byte[] data) {
    try { byte[] digest=MessageDigest.getInstance("SHA-256").digest(data); StringBuilder s=new StringBuilder(); for(byte b:digest)s.append(String.format(Locale.ROOT,"%02x",b&255)); return s.toString(); }
    catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
  static String fingerprint(String title,String body,List<String> hashes,String status) {
    String text=EventPolicy.fingerprint(title,body);
    return hashes.isEmpty() && "none".equals(status)?text:EventPolicy.fingerprint(text,status+":"+String.join(",",hashes));
  }
  static String label(String status,int count) {
    switch(status) {
      case "saved": return "已保存 "+count+" 張圖片 · 壓縮預覽";
      case "partial": return "已保存 "+count+" 張；部分圖片無法讀取或超過上限";
      case "unavailable": return "圖片未保存：來源未授權、格式不支援、超過限制或讀取逾時";
      case "disabled": return "圖片未保存：當時已關閉圖片記錄";
      default: return "";
    }
  }
  @SuppressWarnings("deprecation")
  static Result read(Context c,Notification n,boolean enabled) {
    List<Object> candidates=new ArrayList<>(); Bundle b=n.extras;
    Object picture=NotificationText.value(b,Notification.EXTRA_PICTURE);
    if(picture!=null)candidates.add(picture);
    if(Build.VERSION.SDK_INT>=31) { Object icon=NotificationText.value(b,Notification.EXTRA_PICTURE_ICON); if(icon!=null)candidates.add(icon); }
    Object messages=NotificationText.value(b,Notification.EXTRA_MESSAGES);
    if(messages instanceof Parcelable[]) {
      Parcelable[] all=(Parcelable[])messages;
      for(int i=all.length-1;i>=0 && candidates.size()<12;i--) {
        if(!(all[i] instanceof Bundle))continue;
        Bundle m=(Bundle)all[i]; Object mime=NotificationText.value(m,"type"),uri=NotificationText.value(m,"uri");
        if(mime instanceof String && ((String)mime).startsWith("image/")) candidates.add(uri instanceof Uri?uri:INVALID);
      }
    }
    if(candidates.isEmpty())return Result.empty("none");
    if(!enabled)return Result.empty("disabled");
    List<Attachment> images=new ArrayList<>(); Set<String> seen=new HashSet<>(); boolean missed=false;
    int attempts=0;
    for(Object candidate:candidates) {
      if(images.size()>=MAX_IMAGES || attempts++>=8) { missed=true; break; }
      Bitmap bitmap=null; boolean owned=false;
      try {
        if(candidate instanceof Bitmap) bitmap=(Bitmap)candidate;
        else if(candidate instanceof Uri) { bitmap=fromUri(c,(Uri)candidate); owned=true; }
        else if(candidate instanceof Icon && Build.VERSION.SDK_INT>=28) {
          Icon icon=(Icon)candidate; int type=icon.getType();
          if(type==Icon.TYPE_URI || type==Icon.TYPE_URI_ADAPTIVE_BITMAP)bitmap=fromUri(c,icon.getUri());
          else if(type==Icon.TYPE_BITMAP || type==Icon.TYPE_ADAPTIVE_BITMAP || type==Icon.TYPE_RESOURCE) {
            Drawable d=icon.loadDrawable(c); if(d==null)throw new IOException();
            int w=Math.max(1,d.getIntrinsicWidth()),h=Math.max(1,d.getIntrinsicHeight());
            float scale=Math.min(1f,MAX_EDGE/(float)Math.max(w,h));
            bitmap=Bitmap.createBitmap(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)),Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(bitmap); d.setBounds(0,0,bitmap.getWidth(),bitmap.getHeight()); d.draw(canvas);
          } else throw new IOException();
          owned=true;
        } else throw new IOException();
        Attachment image=normalize(bitmap); if(seen.add(image.hash))images.add(image);
      } catch(Exception | OutOfMemoryError e) { missed=true; }
      finally { if(owned && bitmap!=null && !bitmap.isRecycled())bitmap.recycle(); }
    }
    return new Result(images,images.isEmpty()?"unavailable":missed?"partial":"saved");
  }
  private static final Object INVALID=new Object();
  static Attachment normalize(Bitmap original) throws IOException {
    if(original==null || original.isRecycled())throw new IOException("圖片無法讀取");
    Bitmap scaled=null,software=null;
    try {
      if(original.getConfig()==Bitmap.Config.HARDWARE) { software=original.copy(Bitmap.Config.ARGB_8888,false); if(software==null)throw new IOException(); original=software; }
      int w=original.getWidth(),h=original.getHeight();
      float ratio=Math.min(1f,MAX_EDGE/(float)Math.max(w,h));
      // Drawing to a software bitmap also handles hardware bitmaps supplied by the OS.
      for(int attempt=0;attempt<10;attempt++) {
        int width=Math.max(1,Math.round(w*ratio)),height=Math.max(1,Math.round(h*ratio));
        scaled=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(scaled);
        canvas.drawBitmap(original,null,new Rect(0,0,width,height),new Paint(Paint.FILTER_BITMAP_FLAG));
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(!scaled.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("圖片壓縮失敗");
        scaled.recycle(); scaled=null;
        if(out.size()<=MAX_IMAGE_BYTES)return new Attachment(out.toByteArray(),width,height);
        ratio*=0.7f;
      }
      throw new IOException("圖片超過上限");
    } finally { if(scaled!=null)scaled.recycle(); if(software!=null)software.recycle(); }
  }
  private static Bitmap fromUri(Context c,Uri uri) throws Exception {
    if(uri==null || !"content".equals(uri.getScheme()))throw new IOException("只接受通知授權的本機圖片");
    CancellationSignal cancellation=new CancellationSignal();
    java.util.concurrent.atomic.AtomicReference<android.content.res.AssetFileDescriptor> open=new java.util.concurrent.atomic.AtomicReference<>();
    Future<Bitmap> task=READERS.submit(()->{
      try(android.content.res.AssetFileDescriptor fd=c.getContentResolver().openAssetFileDescriptor(uri,"r",cancellation)) {
        if(fd==null)throw new IOException(); open.set(fd);
        try(InputStream in=fd.createInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
          byte[] buf=new byte[8192]; int n;
          while((n=in.read(buf))!=-1) { if(cancellation.isCanceled() || out.size()+n>MAX_SOURCE_BYTES)throw new IOException(); out.write(buf,0,n); }
          byte[] data=out.toByteArray(); BitmapFactory.Options options=new BitmapFactory.Options(); options.inJustDecodeBounds=true;
          BitmapFactory.decodeByteArray(data,0,data.length,options);
          if(options.outWidth<=0 || options.outHeight<=0 || options.outWidth>32000 || options.outHeight>32000 || (long)options.outWidth*options.outHeight>100000000)throw new IOException();
          options.inJustDecodeBounds=false; options.inSampleSize=1;
          while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>MAX_EDGE)options.inSampleSize*=2;
          Bitmap bitmap=BitmapFactory.decodeByteArray(data,0,data.length,options);
          if(bitmap==null)throw new IOException();
          if(cancellation.isCanceled()) { bitmap.recycle(); throw new IOException(); } return bitmap;
        }
      } catch(OutOfMemoryError e) { throw new IOException("圖片過大",e); }
    });
    try { return task.get(2,TimeUnit.SECONDS); }
    finally { cancellation.cancel(); task.cancel(true); READERS.purge(); android.content.res.AssetFileDescriptor fd=open.get(); if(fd!=null)try { fd.close(); } catch(IOException ignored) {} }
  }
  static Attachment validate(byte[] bytes,String hash,int width,int height) throws IOException {
    if(bytes.length==0 || bytes.length>MAX_IMAGE_BYTES || !sha(bytes).equals(hash) || width<1 || height<1 || width>MAX_EDGE || height>MAX_EDGE)throw new IOException("備份圖片校驗失敗");
    byte[] magic={(byte)137,80,78,71,13,10,26,10};
    if(bytes.length<24 || !Arrays.equals(magic,Arrays.copyOf(bytes,8)))throw new IOException("備份圖片格式錯誤");
    BitmapFactory.Options o=new BitmapFactory.Options(); o.inJustDecodeBounds=true; BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);
    if(o.outWidth!=width || o.outHeight!=height)throw new IOException("備份圖片尺寸不符");
    Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
    if(bitmap==null)throw new IOException("備份圖片損壞"); bitmap.recycle(); return new Attachment(bytes,width,height);
  }
}
