package dev.huang.notify;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("deprecation")
public class ImageUiTest extends ActivityInstrumentationTestCase2<MainActivity> {
  private MainActivity main; private ImageViewerActivity viewer; private Record record; private NotificationImages.Attachment picture;
  private Configuration original;
  public ImageUiTest(){super(MainActivity.class);}
  @Override protected void setUp() throws Exception {
    super.setUp(); android.content.Context c=getInstrumentation().getTargetContext(); original=new Configuration(c.getResources().getConfiguration());
    UpdateChecker.enable(c,false); c.getSharedPreferences("permission_guide",0).edit().putInt("step",3).commit();
    picture=ImageStoreTest.picture(0xff146c59);
    record=Store.IO.submit(()->{ Store s=Store.get(c); s.getWritableDatabase().delete("records",null,null); s.putSetting("paused","0"); s.putSetting("save_images","1"); s.putSetting("exclude:fixture.images","0");
      s.capture("ui-image","fixture.images","相機","值得留下的照片","通知消失後，仍可查看已保存的圖片。",1,new NotificationImages.Result(Collections.singletonList(picture),"saved")); return s.list("",null,false,false,10).get(0); }).get(10,TimeUnit.SECONDS);
  }
  @Override protected void tearDown() throws Exception {
    getInstrumentation().runOnMainSync(()->{ if(viewer!=null)viewer.finish(); if(main!=null) {AppDialog.dismissAll(main); main.finish();} getInstrumentation().getTargetContext().getResources().updateConfiguration(original,getInstrumentation().getTargetContext().getResources().getDisplayMetrics()); });
    Store.IO.submit(()->{Store s=Store.get(getInstrumentation().getTargetContext()); s.delete(record.id); s.putSetting("save_images","1");}).get(); super.tearDown();
  }
  public void testDetailGalleryViewerZoomAndSaveCopy() throws Exception {
    main=getActivity(); getInstrumentation().waitForIdleSync();
    org.json.JSONArray versions=Store.IO.submit(()->Store.get(main).versions(record.id)).get(); org.json.JSONArray images=Store.IO.submit(()->Store.get(main).recordImages(record.id)).get();
    java.lang.reflect.Method detail=MainActivity.class.getDeclaredMethod("showDetail",Record.class,org.json.JSONArray.class,org.json.JSONArray.class); detail.setAccessible(true);
    getInstrumentation().runOnMainSync(()->{try {detail.invoke(main,record,versions,images);}catch(Exception e){throw new RuntimeException(e);}});
    Store.IO.submit(()->{}).get(); getInstrumentation().waitForIdleSync(); capture("image-gallery.png");
    Instrumentation.ActivityMonitor monitor=getInstrumentation().addMonitor(ImageViewerActivity.class.getName(),null,false);
    try {
      AccessibilityNodeInfo root=getInstrumentation().getUiAutomation().getRootInActiveWindow(); boolean clicked=false;
      for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText("查看圖片 1"))if(node.isVisibleToUser() && node.isClickable()) {clicked=node.performAction(AccessibilityNodeInfo.ACTION_CLICK); if(clicked)break;}
      assertTrue("Image preview action missing",clicked); viewer=(ImageViewerActivity)getInstrumentation().waitForMonitorWithTimeout(monitor,5000); assertNotNull(viewer); awaitImage();
    } finally {getInstrumentation().removeMonitor(monitor);}
    getInstrumentation().runOnMainSync(()->find(viewer.getWindow().getDecorView(),"放大").performClick());
    getInstrumentation().runOnMainSync(()->find(viewer.getWindow().getDecorView(),"重設").performClick()); capture("image-viewer.png");
    AutoBackup.Documents destination=new AutoBackup.Documents(viewer,Uri.parse("content://dev.huang.notify.testdocs/tree/root")); String id=destination.create("image-ui-"+UUID.randomUUID()+".png");
    try {
      getInstrumentation().runOnMainSync(()->viewer.onActivityResult(19,Activity.RESULT_OK,new Intent().setData(Uri.parse(id)))); Store.IO.submit(()->{}).get();
      try(InputStream in=destination.read(id); ByteArrayOutputStream out=new ByteArrayOutputStream()) {byte[] buffer=new byte[1024]; int n; while((n=in.read(buffer))!=-1)out.write(buffer,0,n); assertTrue(Arrays.equals(picture.data,out.toByteArray()));}
    } finally {destination.delete(id);}
  }
  public void testDarkLargeTextViewerControlsFit() throws Exception {
    android.content.Context c=getInstrumentation().getTargetContext(); Configuration config=new Configuration(original); config.fontScale=1.8f; config.uiMode=(config.uiMode&~Configuration.UI_MODE_NIGHT_MASK)|Configuration.UI_MODE_NIGHT_YES;
    getInstrumentation().runOnMainSync(()->c.getResources().updateConfiguration(config,c.getResources().getDisplayMetrics()));
    viewer=(ImageViewerActivity)getInstrumentation().startActivitySync(new Intent(c,ImageViewerActivity.class).putExtra("image_hash",picture.hash).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); awaitImage();
    for(String label:new String[]{"關閉","另存圖片","放大","重設"}) {
      View button=find(viewer.getWindow().getDecorView(),label); assertNotNull(button); android.graphics.Rect visible=new android.graphics.Rect(); assertTrue(button.getGlobalVisibleRect(visible)); assertTrue(visible.height()>0);
    }
    capture("image-viewer-dark-large.png");
  }
  public void testImageRecordingSwitchPersistsWithoutRemovingExistingPictures() throws Exception {
    main=getActivity(); getInstrumentation().waitForIdleSync(); View settings=find(main.getWindow().getDecorView(),"設定");
    getInstrumentation().runOnMainSync(()->((View)settings.getParent()).performClick()); Store.IO.submit(()->{}).get(); getInstrumentation().waitForIdleSync();
    RecordSwitch control=(RecordSwitch)description(main.getWindow().getDecorView(),"保存通知圖片"); assertNotNull(control); assertTrue(control.isChecked());
    getInstrumentation().runOnMainSync(()->control.performClick()); Store.IO.submit(()->{}).get(); getInstrumentation().waitForIdleSync();
    assertEquals("0",Store.IO.submit(()->Store.get(main).setting("save_images","1")).get()); assertNotNull(Store.IO.submit(()->Store.get(main).image(picture.hash)).get()); capture("image-setting.png");
  }
  private void awaitImage() throws Exception {
    long deadline=SystemClock.elapsedRealtime()+8000;
    while(SystemClock.elapsedRealtime()<deadline) {getInstrumentation().waitForIdleSync(); View button=find(viewer.getWindow().getDecorView(),"另存圖片"); if(button!=null && button.isEnabled())return; SystemClock.sleep(50);}
    fail("Saved image did not load");
  }
  private View find(View v,String text) {if(v instanceof TextView && text.contentEquals(((TextView)v).getText()))return v; if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;} return null;}
  private View description(View v,String text) {if(text.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v; if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=description(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}return null;}
  private void capture(String name) throws Exception {
    for(String command:new String[]{"mkdir -p /data/local/tmp/huangnotify-ui-preview","screencap -p /data/local/tmp/huangnotify-ui-preview/"+name})try(android.os.ParcelFileDescriptor fd=getInstrumentation().getUiAutomation().executeShellCommand(command); InputStream in=new FileInputStream(fd.getFileDescriptor())) {byte[] buffer=new byte[1024];while(in.read(buffer)!=-1){}}
  }
}
