package dev.huang.notify;

import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.OutputStream;

/** Private local viewer: no external file paths, network requests, or storage permissions. */
public final class ImageViewerActivity extends Activity {
  private String hash; private ZoomView image; private TextView state,save;
  @Override public void onCreate(Bundle bundle) {
    super.onCreate(bundle); Ui u=new Ui(this); hash=getIntent().getStringExtra("image_hash");
    LinearLayout root=u.column(); root.setBackgroundColor(u.bg);
    root.setOnApplyWindowInsetsListener((v,insets)->{
      if(android.os.Build.VERSION.SDK_INT>=30) { Insets s=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()); v.setPadding(s.left,s.top,s.right,s.bottom); return WindowInsets.CONSUMED; }
      v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets.consumeSystemWindowInsets();
    });
    if(android.os.Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
    setContentView(root); root.requestApplyInsets();
    LinearLayout top=u.column(); top.setPadding(u.d(16),u.d(12),u.d(16),u.d(8)); root.addView(top);
    top.addView(u.text("通知圖片",22,u.ink,true)); state=u.text("正在讀取圖片…",12,u.muted,false); top.addView(state); u.gap(top,8);
    LinearLayout actions=u.row(); TextView close=u.button("關閉",false); save=u.button("另存圖片",true); save.setEnabled(false);
    actions.addView(close,new LinearLayout.LayoutParams(0,-2,1)); LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,-2,1); right.setMargins(u.d(8),0,0,0); actions.addView(save,right); top.addView(actions);
    close.setOnClickListener(v->finish()); save.setOnClickListener(v->chooseDestination());
    image=new ZoomView(); image.setBackgroundColor(u.pale); root.addView(image,new LinearLayout.LayoutParams(-1,0,1));
    LinearLayout bottom=u.row(); bottom.setPadding(u.d(16),u.d(8),u.d(16),u.d(12)); TextView zoom=u.button("放大",false),reset=u.button("重設",false);
    bottom.addView(zoom,new LinearLayout.LayoutParams(0,-2,1)); LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,-2,1); rp.setMargins(u.d(8),0,0,0); bottom.addView(reset,rp); root.addView(bottom);
    zoom.setOnClickListener(v->image.zoom()); reset.setOnClickListener(v->image.reset());
    Store.IO.execute(()->{
      Bitmap bitmap=null;
      try { byte[] bytes=Store.get(this).image(hash); if(bytes!=null)bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length); } catch(Exception | OutOfMemoryError ignored) { }
      Bitmap result=bitmap;
      runOnUiThread(()->{ if(isDestroyed() || isFinishing()) { if(result!=null)result.recycle(); return; }
        if(result==null) { state.setText("圖片已刪除或無法讀取"); return; }
        image.setBitmap(result); save.setEnabled(true); state.setText("已壓縮保存 · 雙指縮放、拖曳或連點兩下"); });
    });
  }
  private void chooseDestination() {
    try { startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/png").putExtra(Intent.EXTRA_TITLE,"HuangNotify_"+System.currentTimeMillis()+".png"),19); }
    catch(Exception e) { Toast.makeText(this,"無法開啟檔案選擇器",Toast.LENGTH_SHORT).show(); }
  }
  @Override protected void onActivityResult(int request,int result,Intent data) {
    super.onActivityResult(request,result,data);
    if(request!=19 || result!=RESULT_OK || data==null || data.getData()==null)return;
    Uri destination=data.getData(); save.setEnabled(false);
    Store.IO.execute(()->{
      String message="圖片已另存";
      try {
        byte[] bytes=Store.get(this).image(hash); if(bytes==null)throw new java.io.IOException();
        try(OutputStream out=getContentResolver().openOutputStream(destination,"wt")) { if(out==null)throw new java.io.IOException(); out.write(bytes); out.flush(); }
      } catch(Exception e) { message="圖片未另存，請檢查檔案位置或儲存空間"; }
      String text=message; runOnUiThread(()->{ if(!isDestroyed()) { save.setEnabled(true); Toast.makeText(this,text,Toast.LENGTH_LONG).show(); } });
    });
  }
  final class ZoomView extends View {
    private Bitmap bitmap; private float fit=1,level=1,x,y,lastX,lastY; private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
    private final ScaleGestureDetector scale; private final GestureDetector taps;
    ZoomView() {
      super(ImageViewerActivity.this); setFocusable(true); setClickable(true); setContentDescription("通知圖片，可使用下方放大與重設按鈕");
      scale=new ScaleGestureDetector(getContext(),new ScaleGestureDetector.SimpleOnScaleGestureListener(){ @Override public boolean onScale(ScaleGestureDetector detector){ change(level*detector.getScaleFactor(),detector.getFocusX(),detector.getFocusY()); return true; } });
      taps=new GestureDetector(getContext(),new GestureDetector.SimpleOnGestureListener(){ @Override public boolean onDown(MotionEvent e){return true;} @Override public boolean onDoubleTap(MotionEvent e){change(level>1?1:2.5f,e.getX(),e.getY());return true;} });
    }
    void setBitmap(Bitmap value) { bitmap=value; reset(); }
    void reset() { if(bitmap==null || getWidth()==0 || getHeight()==0)return; fit=Math.min(getWidth()/(float)bitmap.getWidth(),getHeight()/(float)bitmap.getHeight()); level=1; x=(getWidth()-bitmap.getWidth()*fit)/2; y=(getHeight()-bitmap.getHeight()*fit)/2; invalidate(); }
    void zoom() { change(level>=6?1:level*1.5f,getWidth()/2f,getHeight()/2f); }
    private void change(float value,float focusX,float focusY) { if(bitmap==null)return; float next=Math.max(1,Math.min(6,value)); x=focusX-(focusX-x)*next/level; y=focusY-(focusY-y)*next/level; level=next; bound(); invalidate(); }
    private void bound() { if(bitmap==null)return; float w=bitmap.getWidth()*fit*level,h=bitmap.getHeight()*fit*level; x=w<=getWidth()?(getWidth()-w)/2:Math.max(getWidth()-w,Math.min(0,x)); y=h<=getHeight()?(getHeight()-h)/2:Math.max(getHeight()-h,Math.min(0,y)); }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) { reset(); }
    @Override protected void onDraw(Canvas canvas) { super.onDraw(canvas); if(bitmap!=null)canvas.drawBitmap(bitmap,null,new RectF(x,y,x+bitmap.getWidth()*fit*level,y+bitmap.getHeight()*fit*level),paint); }
    @Override public boolean onTouchEvent(MotionEvent event) {
      scale.onTouchEvent(event); taps.onTouchEvent(event);
      if(event.getActionMasked()==MotionEvent.ACTION_MOVE && event.getPointerCount()==1 && !scale.isInProgress()) { x+=event.getX()-lastX; y+=event.getY()-lastY; bound(); invalidate(); }
      lastX=event.getX(); lastY=event.getY();
      if(event.getActionMasked()==MotionEvent.ACTION_UP)performClick(); return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
  }
}
