package dev.huang.notify;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

@SuppressWarnings("deprecation")
public class BackupUiTest extends ActivityInstrumentationTestCase2<MainActivity> {
  MainActivity main;
  public BackupUiTest(){super(MainActivity.class);}
  @Override protected void setUp()throws Exception{super.setUp();Context c=getInstrumentation().getTargetContext();UpdateChecker.enable(c,false);c.getSharedPreferences("permission_guide",0).edit().putInt("step",3).commit();}
  @Override protected void tearDown()throws Exception{if(main!=null)getInstrumentation().runOnMainSync(()->{AppDialog.dismissAll(main);main.finish();});AutoBackup.IO.submit(()->{}).get(30,TimeUnit.SECONDS);super.tearDown();}
  private AppDialog dialog()throws Exception{java.lang.reflect.Field f=AppDialog.class.getDeclaredField("OPEN");f.setAccessible(true);@SuppressWarnings("unchecked") Map<Context,List<AppDialog>> open=(Map<Context,List<AppDialog>>)f.get(null);List<AppDialog> all=open.get(main);return all==null||all.isEmpty()?null:all.get(all.size()-1);}
  public void testCancelExportBeforeSnapshotLeavesRecordsAndCleansPrivateFiles()throws Exception{
    main=getActivity();getInstrumentation().waitForIdleSync();long before=Store.IO.submit(()->Store.get(main).counts()[0]).get();CountDownLatch release=new CountDownLatch(1);
    Store.IO.execute(()->{try{release.await(20,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
    try{
      getInstrumentation().runOnMainSync(()->main.backupScreen.prepareExport(null));getInstrumentation().waitForIdleSync();assertNotNull(main.backupScreen.progress);assertTrue(main.backupScreen.progress.isShowing());
      getInstrumentation().runOnMainSync(()->main.backupScreen.progress.getButton(AppDialog.BUTTON_NEGATIVE).performClick());release.countDown();
      AutoBackup.IO.submit(()->{}).get(30,TimeUnit.SECONDS);getInstrumentation().waitForIdleSync();AutoBackup.IO.submit(()->{}).get();
      assertEquals(before,Store.IO.submit(()->Store.get(main).counts()[0]).get().longValue());
      assertEquals(0,new java.io.File(main.getCacheDir(),"backup-work").listFiles().length);
    }finally{release.countDown();}
  }
  public void testPickerCancelAllowsAnotherOperation()throws Exception{
    main=getActivity();getInstrumentation().waitForIdleSync();
    IntentFilter filter=new IntentFilter(Intent.ACTION_OPEN_DOCUMENT);filter.addCategory(Intent.CATEGORY_OPENABLE);filter.addDataType("*/*");
    Instrumentation.ActivityMonitor monitor=getInstrumentation().addMonitor(filter,new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null),true);
    try{
      getInstrumentation().runOnMainSync(()->main.backupScreen.chooseImport());getInstrumentation().waitForIdleSync();
      assertTrue("Document picker was not intercepted",monitor.getHits()>0);
      getInstrumentation().runOnMainSync(()->main.backupScreen.exportDialog());getInstrumentation().waitForIdleSync();
      assertNotNull("Cancelled picker left backup busy",dialog());assertTrue(dialog().isShowing());assertNotNull(find(dialog().getWindow().getDecorView(),"建立備份"));
    }
    finally{getInstrumentation().removeMonitor(monitor);}
  }
  public void testDiagnosticsAndStorageUseScrollableCustomDialogs()throws Exception{
    main=getActivity();getInstrumentation().waitForIdleSync();getInstrumentation().runOnMainSync(()->Diagnostics.show(main));Store.IO.submit(()->{}).get();getInstrumentation().waitForIdleSync();
    assertNotNull(dialog());assertNotNull(find(dialog().getWindow().getDecorView(),"通知診斷"));assertNotNull(find(dialog().getWindow().getDecorView(),"重新連線通知服務"));capture("diagnostics.png");
    getInstrumentation().runOnMainSync(()->AppDialog.dismissAll(main));java.lang.reflect.Method storage=MainActivity.class.getDeclaredMethod("storageDialog");storage.setAccessible(true);
    getInstrumentation().runOnMainSync(()->{try{storage.invoke(main);}catch(Exception e){throw new RuntimeException(e);}});Store.IO.submit(()->{}).get();getInstrumentation().waitForIdleSync();
    assertNotNull(find(dialog().getWindow().getDecorView(),"儲存概況"));capture("image-storage.png");
    getInstrumentation().runOnMainSync(()->dialogUnchecked().getButton(AppDialog.BUTTON_NEUTRAL).performClick());getInstrumentation().waitForIdleSync();
    AppDialog.Toggle toggle=toggle(dialog().getWindow().getDecorView());assertNotNull(toggle);assertFalse(toggle.isChecked());getInstrumentation().runOnMainSync(()->dialogUnchecked().getButton(AppDialog.BUTTON_NEGATIVE).performClick());
  }
  private AppDialog dialogUnchecked(){try{return dialog();}catch(Exception e){throw new RuntimeException(e);}}
  private View find(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}return null;}
  private AppDialog.Toggle toggle(View v){if(v instanceof AppDialog.Toggle)return (AppDialog.Toggle)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){AppDialog.Toggle found=toggle(((ViewGroup)v).getChildAt(i));if(found!=null)return found;}return null;}
  private void capture(String name)throws Exception{for(String command:new String[]{"mkdir -p /data/local/tmp/huangnotify-ui-preview","screencap -p /data/local/tmp/huangnotify-ui-preview/"+name})try(android.os.ParcelFileDescriptor fd=getInstrumentation().getUiAutomation().executeShellCommand(command);java.io.InputStream in=new java.io.FileInputStream(fd.getFileDescriptor())){byte[] b=new byte[1024];while(in.read(b)!=-1){}}}
}
