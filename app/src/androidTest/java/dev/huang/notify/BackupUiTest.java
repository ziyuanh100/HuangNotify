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
  @Override protected void tearDown()throws Exception{if(main!=null)getInstrumentation().runOnMainSync(()->{if(BackupOperation.current()!=null)BackupOperation.current().cancel();AppDialog.dismissAll(main);main.finish();});AutoBackup.IO.submit(()->{}).get(30,TimeUnit.SECONDS);super.tearDown();}
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
    assertNotNull(find(dialog().getWindow().getDecorView(),"儲存概況"));
    assertNotNull(findContaining(dialog().getWindow().getDecorView(),"含每則通知的初次記錄"));
    capture("image-storage.png");
    getInstrumentation().runOnMainSync(()->dialogUnchecked().getButton(AppDialog.BUTTON_NEUTRAL).performClick());getInstrumentation().waitForIdleSync();
    AppDialog.Toggle toggle=toggle(dialog().getWindow().getDecorView());assertNotNull(toggle);assertFalse(toggle.isChecked());getInstrumentation().runOnMainSync(()->dialogUnchecked().getButton(AppDialog.BUTTON_NEGATIVE).performClick());
  }
  public void testRotationPreservesQueuedExportAndCancellation()throws Exception{
    main=getActivity();getInstrumentation().waitForIdleSync();CountDownLatch release=new CountDownLatch(1);
    Store.IO.execute(()->{try{release.await(30,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
    try{
      getInstrumentation().runOnMainSync(()->main.backupScreen.prepareExport(null));BackupOperation op=BackupOperation.current();assertNotNull(op);recreateMain();
      assertSame(op,BackupOperation.current());assertFalse(op.task.cancelled.get());assertNotNull(main.backupScreen.progress);assertTrue(main.backupScreen.progress.isShowing());
      getInstrumentation().runOnMainSync(()->main.backupScreen.progress.getButton(AppDialog.BUTTON_NEGATIVE).performClick());getInstrumentation().runOnMainSync(()->{main.backupScreen.progress.dismiss();main.backupScreen.update();});assertTrue("Cancelling operation lost its progress after dismissal",main.backupScreen.progress.isShowing());release.countDown();AutoBackup.IO.submit(()->{}).get(30,TimeUnit.SECONDS);getInstrumentation().waitForIdleSync();assertNull(BackupOperation.current());
    }finally{release.countDown();}
  }
  public void testRotationRetainsPendingPickerAndRestorePreviewChoice()throws Exception{
    main=getActivity();getInstrumentation().waitForIdleSync();BackupOperation[] reference=new BackupOperation[1];
    getInstrumentation().runOnMainSync(()->{reference[0]=BackupOperation.begin(main,BackupOperation.State.IMPORT_PICKER);reference[0].pickerLaunched=true;});recreateMain();assertSame(reference[0],BackupOperation.current());
    getInstrumentation().runOnMainSync(()->main.onActivityResult(102,Activity.RESULT_CANCELED,null));getInstrumentation().waitForIdleSync();assertNull(BackupOperation.current());
    BackupFiles.Session session=AutoBackup.IO.submit(()->BackupFiles.export(main.getApplicationContext(),null,new BackupTask())).get(30,TimeUnit.SECONDS);
    session.stage=BackupArchive.read(session.ready,session.database,new BackupTask());long before=Store.IO.submit(()->Store.get(main).counts()[0]).get();
    getInstrumentation().runOnMainSync(()->{reference[0]=BackupOperation.begin(main,BackupOperation.State.PREVIEW);reference[0].session=session;main.backupScreen.update();});getInstrumentation().waitForIdleSync();
    AppDialog.Toggle settings=toggle(dialog().getWindow().getDecorView());assertNotNull(settings);getInstrumentation().runOnMainSync(settings::performClick);assertTrue(reference[0].restoreSettings);recreateMain();
    assertSame(reference[0],BackupOperation.current());assertTrue(toggle(dialog().getWindow().getDecorView()).isChecked());capture("restore-preview-rotated.png");
    getInstrumentation().runOnMainSync(()->dialogUnchecked().getButton(AppDialog.BUTTON_NEGATIVE).performClick());getInstrumentation().waitForIdleSync();assertNull(BackupOperation.current());assertEquals(before,Store.IO.submit(()->Store.get(main).counts()[0]).get().longValue());
  }
  private void recreateMain()throws Exception{
    Instrumentation.ActivityMonitor monitor=getInstrumentation().addMonitor(MainActivity.class.getName(),null,false);
    try{getInstrumentation().runOnMainSync(()->main.recreate());Activity replacement=getInstrumentation().waitForMonitorWithTimeout(monitor,10000);assertNotNull("Activity recreation did not finish",replacement);main=(MainActivity)replacement;getInstrumentation().waitForIdleSync();}finally{getInstrumentation().removeMonitor(monitor);}
  }
  private AppDialog dialogUnchecked(){try{return dialog();}catch(Exception e){throw new RuntimeException(e);}}
  private View find(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}return null;}
  private View findContaining(View view, String text) {
    if (view instanceof TextView && ((TextView) view).getText().toString().contains(text)) return view;
    if (view instanceof ViewGroup) {
      ViewGroup children = (ViewGroup) view;
      for (int i = 0; i < children.getChildCount(); i++) {
        View found = findContaining(children.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }
  private AppDialog.Toggle toggle(View v){if(v instanceof AppDialog.Toggle)return (AppDialog.Toggle)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){AppDialog.Toggle found=toggle(((ViewGroup)v).getChildAt(i));if(found!=null)return found;}return null;}
  private void capture(String name)throws Exception{for(String command:new String[]{"mkdir -p /data/local/tmp/huangnotify-ui-preview","screencap -p /data/local/tmp/huangnotify-ui-preview/"+name})try(android.os.ParcelFileDescriptor fd=getInstrumentation().getUiAutomation().executeShellCommand(command);java.io.InputStream in=new java.io.FileInputStream(fd.getFileDescriptor())){byte[] b=new byte[1024];while(in.read(b)!=-1){}}}
}
