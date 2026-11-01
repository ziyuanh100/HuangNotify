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
  private Context c; private boolean wasGranted; private String paused,excluded,listenerSetting;
  @Override protected void setUp() throws Exception {
    super.setUp(); c=getInstrumentation().getTargetContext(); wasGranted=PermissionGuide.hasAccess(c);
    String[] previous=Store.IO.submit(()->{
      Store s=Store.get(c); String[] old={s.setting("paused","0"),s.setting("exclude:"+PROBE,"0")};
      s.putSetting("paused","0"); s.putSetting("exclude:"+PROBE,"0"); s.getWritableDatabase().delete("records","pkg=?",new String[]{PROBE}); return old;
    }).get(10,TimeUnit.SECONDS); paused=previous[0]; excluded=previous[1];
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
      shell("am start -W -n "+PROBE+"/.ProbeActivity --ei id 901 --ez cancel true");
      if(!wasGranted) {
        if(Build.VERSION.SDK_INT>=28) shell("cmd notification disallow_listener "+COMPONENT);
        else if("null".equals(listenerSetting) || listenerSetting==null || listenerSetting.isEmpty()) shell("settings delete secure enabled_notification_listeners");
        else shell("settings put secure enabled_notification_listeners "+listenerSetting);
      }
      Store.IO.submit(()->{
        Store s=Store.get(c); s.getWritableDatabase().delete("records","pkg=?",new String[]{PROBE}); s.putSetting("paused",paused); s.putSetting("exclude:"+PROBE,excluded);
      }).get(10,TimeUnit.SECONDS);
    } finally { super.tearDown(); }
  }
  public void testScreenshotSummaryIsRecordedAndSurvivesRemoval() throws Exception {
    shell("am start -W -n "+PROBE+"/.ProbeActivity --ei id 901 --es kind screenshot");
    Record saved=awaitRecord(true); assertEquals("截圖已儲存",saved.title); assertTrue(saved.text.contains("未保存圖片"));
    assertEquals(1,Store.IO.submit(()->Store.get(c).list("",PROBE,false,false,20).size()).get().intValue());
    shell("am start -W -n "+PROBE+"/.ProbeActivity --ei id 901 --ez cancel true");
    Record removed=awaitRecord(false); assertEquals(saved.id,removed.id); assertEquals(saved.text,removed.text);
    org.json.JSONObject backup=Store.IO.submit(()->Store.get(c).exportBackup()).get(); BackupFormat.validate(backup);
  }
  private Record awaitRecord(boolean active) throws Exception {
    long deadline=SystemClock.elapsedRealtime()+12000;
    while(SystemClock.elapsedRealtime()<deadline) {
      List<Record> records=Store.IO.submit(()->Store.get(c).list("",PROBE,false,false,20)).get(5,TimeUnit.SECONDS);
      for(Record r:records) if(r.title.equals("截圖已儲存") && r.active==active) return r;
      SystemClock.sleep(100);
    } throw new AssertionError("Screenshot notification not recorded with active="+active+"; "+Store.error);
  }
  private String shell(String command) throws Exception {
    try(ParcelFileDescriptor fd=getInstrumentation().getUiAutomation().executeShellCommand(command); InputStream in=new FileInputStream(fd.getFileDescriptor()); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
      byte[] b=new byte[2048]; int n; while((n=in.read(b))!=-1) out.write(b,0,n); return out.toString("UTF-8");
    }
  }
}
