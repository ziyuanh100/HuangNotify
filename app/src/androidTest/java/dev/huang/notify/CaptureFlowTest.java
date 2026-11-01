package dev.huang.notify;

import android.content.Context;
import android.os.*;
import android.test.InstrumentationTestCase;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real OS notification -> listener -> SQLite -> removal. Only synthetic probe images are used. */
@SuppressWarnings("deprecation")
public class CaptureFlowTest extends InstrumentationTestCase {
  private static final String PROBE="dev.huang.notify.probe", COMPONENT="dev.huang.notify/dev.huang.notify.CaptureService";
  private Context c; private boolean wasGranted; private String paused,excluded,listenerSetting,imageSetting;
  private String kind="screenshot";
  @Override protected void setUp() throws Exception {
    super.setUp(); c=getInstrumentation().getTargetContext(); wasGranted=PermissionGuide.hasAccess(c);
    String[] previous=Store.IO.submit(()->{
      Store s=Store.get(c); String[] old={s.setting("paused","0"),s.setting("exclude:"+PROBE,"0"),s.setting("save_images","1")};
      s.putSetting("save_images","1"); s.putSetting("paused","0"); s.putSetting("exclude:"+PROBE,"0"); s.getWritableDatabase().delete("records","pkg=?",new String[]{PROBE}); return old;
    }).get(10,TimeUnit.SECONDS); paused=previous[0]; excluded=previous[1]; imageSetting=previous[2];
    if(Build.VERSION.SDK_INT>=33) shell("pm grant "+PROBE+" android.permission.POST_NOTIFICATIONS");
    if(Build.VERSION.SDK_INT>=28) shell("cmd notification allow_listener "+COMPONENT);
    else {
      listenerSetting=shell("settings get secure enabled_notification_listeners").trim();
      String oldListeners="null".equals(listenerSetting)?"":listenerSetting;
      shell("settings put secure enabled_notification_listeners "+(oldListeners.isEmpty()?COMPONENT:oldListeners+":"+COMPONENT));
    }
    long deadline=SystemClock.elapsedRealtime()+15000;
    while(!CaptureService.connected && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100);
    assertTrue("Notification listener did not connect",CaptureService.connected);
  }
  @Override protected void tearDown() throws Exception {
    try {
      probe(true);
      if(!wasGranted) {
        if(Build.VERSION.SDK_INT>=28) shell("cmd notification disallow_listener "+COMPONENT);
        else if("null".equals(listenerSetting) || listenerSetting==null || listenerSetting.isEmpty()) shell("settings delete secure enabled_notification_listeners");
        else shell("settings put secure enabled_notification_listeners "+listenerSetting);
      }
      Store.IO.submit(()->{
        Store s=Store.get(c); s.getWritableDatabase().delete("records","pkg=?",new String[]{PROBE}); s.putSetting("save_images",imageSetting); s.putSetting("paused",paused); s.putSetting("exclude:"+PROBE,excluded);
      }).get(10,TimeUnit.SECONDS);
    } finally { super.tearDown(); }
  }
  public void testScreenshotSummaryIsRecordedAndSurvivesRemoval() throws Exception {
    probe(false);
    Record saved=awaitRecord(true); assertEquals("截圖已儲存",saved.title); assertTrue(saved.text.contains("通知包含圖片")); assertEquals(1,saved.imageCount); assertEquals("saved",saved.imageStatus);
    String hash=Store.IO.submit(()->Store.get(c).recordImages(saved.id).getString(0)).get();
    byte[] original=Store.IO.submit(()->Store.get(c).image(hash)).get(); assertNotNull(original);
    assertEquals(1,Store.IO.submit(()->Store.get(c).list("",PROBE,false,false,20).size()).get().intValue());
    probe(true);
    Record removed=awaitRecord(false,saved.id); assertEquals(saved.id,removed.id); assertEquals(saved.text,removed.text); assertEquals(1,removed.imageCount); assertTrue(java.util.Arrays.equals(original,Store.IO.submit(()->Store.get(c).image(hash)).get()));
    org.json.JSONObject backup=Store.IO.submit(()->Store.get(c).exportBackup()).get(); BackupFormat.validate(backup); assertEquals(2,backup.getInt("version"));
  }
  public void testMessagingImageRespectsAvailableUriGrants() throws Exception {
    kind="message-image"; probe(false); Record saved=awaitRecord(true);
    if(Build.VERSION.SDK_INT<28) {
      // Android 8 has no NLS URI auto-grant: first prove denial, then a source-owned explicit grant.
      assertEquals("unavailable",saved.imageStatus); assertTrue(saved.text.contains("圖片說明")); probe(true); awaitRecord(false,saved.id);
      kind="message-image-granted"; probe(false); saved=awaitRecord(true);
    }
    assertEquals("saved",saved.imageStatus); assertEquals(1,saved.imageCount);
    Record copied=saved;
    String hash=Store.IO.submit(()->Store.get(c).recordImages(copied.id).getString(0)).get();
    byte[] bytes=Store.IO.submit(()->Store.get(c).image(hash)).get(); assertNotNull(bytes);
    probe(true); Record removed=awaitRecord(false,saved.id); assertEquals(saved.id,removed.id);
    assertTrue(Arrays.equals(bytes,Store.IO.submit(()->Store.get(c).image(hash)).get()));
  }
  public void testUnreadableImageKeepsTheNotificationText() throws Exception {
    kind="denied-image"; probe(false); Record saved=awaitRecord(true);
    assertEquals("unavailable",saved.imageStatus); assertEquals(0,saved.imageCount); assertTrue(saved.text.contains("圖片說明"));
  }
  public void testDisabledImageRecordingKeepsTextWithoutSavingPixels() throws Exception {
    Store.IO.submit(()->Store.get(c).putSetting("save_images","0")).get();
    probe(false); Record saved=awaitRecord(true); assertEquals("disabled",saved.imageStatus); assertEquals(0,saved.imageCount);
  }
  private Record awaitRecord(boolean active) throws Exception { return awaitRecord(active,null); }
  private Record awaitRecord(boolean active,String id) throws Exception {
    long deadline=SystemClock.elapsedRealtime()+12000;
    while(SystemClock.elapsedRealtime()<deadline) {
      List<Record> records=Store.IO.submit(()->Store.get(c).list("",PROBE,false,false,20)).get(5,TimeUnit.SECONDS);
      for(Record r:records) if(r.active==active && (id==null || id.equals(r.id))) return r;
      SystemClock.sleep(100);
    } throw new AssertionError("Screenshot notification not recorded with active="+active+"; "+Store.error);
  }
  private void probe(boolean cancel) {
    android.content.Intent intent=new android.content.Intent().setClassName(PROBE,PROBE+".ProbeActivity")
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("id",901).putExtra("cancel",cancel).putExtra("kind",kind);
    c.startActivity(intent);
  }
  private String shell(String command) throws Exception {
    // A stalled shell must fail with its command instead of blocking the entire API 26 suite.
    java.util.concurrent.atomic.AtomicReference<ParcelFileDescriptor> descriptor=new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"capture-test-shell"); t.setDaemon(true); return t;});
    try {
      return executor.submit(()->{
        try(ParcelFileDescriptor fd=getInstrumentation().getUiAutomation().executeShellCommand(command); InputStream in=new FileInputStream(fd.getFileDescriptor()); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
          descriptor.set(fd); byte[] b=new byte[2048]; int n; while((n=in.read(b))!=-1) out.write(b,0,n); return out.toString("UTF-8");
        }
      }).get(20,TimeUnit.SECONDS);
    } catch(java.util.concurrent.TimeoutException e) { throw new AssertionError("Timed out running shell: "+command,e); }
    finally { ParcelFileDescriptor fd=descriptor.get(); if(fd!=null) try { fd.close(); } catch(IOException ignored) {} executor.shutdownNow(); }
  }
}
