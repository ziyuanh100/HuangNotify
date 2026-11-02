package dev.huang.notify;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.os.*;
import android.test.ActivityInstrumentationTestCase2;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

@SuppressWarnings("deprecation")
public class Version130UiTest extends ActivityInstrumentationTestCase2<MainActivity> {
  MainActivity main;int originalNight;String originalFont;
  public Version130UiTest(){super(MainActivity.class);}
  @Override protected void setUp()throws Exception{
    super.setUp();Context c=getInstrumentation().getTargetContext();originalNight=c.getSystemService(UiModeManager.class).getNightMode();originalFont=android.provider.Settings.System.getString(c.getContentResolver(),android.provider.Settings.System.FONT_SCALE);
    UpdateChecker.enable(c,false);c.getSharedPreferences("permission_guide",0).edit().putInt("step",3).commit();c.getSharedPreferences("diagnostic_test",0).edit().clear().commit();
    Store.IO.submit(()->{Store s=Store.get(c);s.getWritableDatabase().delete("records",null,null);s.getWritableDatabase().delete("settings",null,null);s.pruneImages();s.clearDiagnostics();s.capture("with-image","ui130.fixture","Fixture","有圖片的紀錄","sample",1,new NotificationImages.Result(Collections.singletonList(ImageStoreTest.picture(0xff146c59)),"saved"));s.capture("text-only","ui130.fixture","Fixture","純文字紀錄","sample",2);android.content.ContentValues v=new android.content.ContentValues();v.put("first_seen",java.time.LocalDate.of(2026,11,2).atTime(12,0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());s.getWritableDatabase().update("records",v,null,null);return null;}).get(10,TimeUnit.SECONDS);
  }
  @Override protected void tearDown()throws Exception{
    if(main!=null)getInstrumentation().runOnMainSync(()->{AppDialog.dismissAll(main);main.finish();});shell("cmd uimode night "+(originalNight==2?"yes":originalNight==1?"no":"auto"));if(originalFont==null)shell("settings delete system font_scale");else shell("settings put system font_scale "+originalFont);
    Store.IO.submit(()->{Store s=Store.get(getInstrumentation().getTargetContext());s.getWritableDatabase().delete("records",null,null);s.getWritableDatabase().delete("settings",null,null);s.pruneImages();s.clearDiagnostics();}).get();super.tearDown();
  }
  AppDialog dialog(){try{Field f=AppDialog.class.getDeclaredField("OPEN");f.setAccessible(true);@SuppressWarnings("unchecked")Map<Context,List<AppDialog>> all=(Map<Context,List<AppDialog>>)f.get(null);List<AppDialog> own=all.get(main);return own==null||own.isEmpty()?null:own.get(own.size()-1);}catch(Exception e){throw new RuntimeException(e);}}
  public void testDateValidationCombinedFilterClearAndRotation()throws Exception{
    main=getActivity();getInstrumentation().waitForIdleSync();getInstrumentation().runOnMainSync(main::dateFilterDialog);AppDialog d=dialog();assertNotNull(d);
    getInstrumentation().runOnMainSync(()->((View)find(d.getWindow().getDecorView(),"自訂日期").getParent()).performClick());EditText start=hint(d.getWindow().getDecorView(),"開始日期 YYYY-MM-DD"),end=hint(d.getWindow().getDecorView(),"結束日期 YYYY-MM-DD");
    getInstrumentation().runOnMainSync(()->{start.setText("2026-11-02");end.setText("2026-11-01");d.getButton(AppDialog.BUTTON_POSITIVE).performClick();});assertTrue(d.isShowing());assertNotNull(end.getError());
    getInstrumentation().runOnMainSync(()->{end.setText("2026-11-02");d.getButton(AppDialog.BUTTON_POSITIVE).performClick();});awaitRows(2);
    View image=find(main.getWindow().getDecorView(),"只看有圖片");assertNotNull(image);getInstrumentation().runOnMainSync(image::performClick);awaitRows(1);
    Field field=MainActivity.class.getDeclaredField("rows");field.setAccessible(true);@SuppressWarnings("unchecked")List<Record> rows=(List<Record>)field.get(main);assertEquals("有圖片的紀錄",rows.get(0).title);capture("filters-date-image.png");
    Instrumentation.ActivityMonitor monitor=getInstrumentation().addMonitor(MainActivity.class.getName(),null,false);try{getInstrumentation().runOnMainSync(main::recreate);main=(MainActivity)getInstrumentation().waitForMonitorWithTimeout(monitor,10000);assertNotNull(main);getInstrumentation().waitForIdleSync();awaitRows(1);}finally{getInstrumentation().removeMonitor(monitor);}
    assertNotNull(find(main.getWindow().getDecorView(),"有圖片 ✓"));View clear=find(main.getWindow().getDecorView(),"清除篩選");getInstrumentation().runOnMainSync(clear::performClick);awaitRows(2);assertNotNull(find(main.getWindow().getDecorView(),"所有日期 ▾"));
  }
  public void testGuidedDiagnosticSourceResultAndTimeWindow()throws Exception{
    main=getActivity();getInstrumentation().waitForIdleSync();getInstrumentation().runOnMainSync(()->Diagnostics.show(main));awaitText("開始新的通知測試");
    getInstrumentation().runOnMainSync(()->find(dialog().getWindow().getDecorView(),"開始新的通知測試").performClick());Store.IO.submit(()->{}).get();getInstrumentation().waitForIdleSync();
    Store.IO.submit(()->{Diagnostics.record(Store.get(main),"received","diagnostic.fixture");Diagnostics.record(Store.get(main),"excluded","diagnostic.fixture");}).get();getInstrumentation().runOnMainSync(()->find(dialog().getWindow().getDecorView(),"重新整理").performClick());awaitText("本次測試 ▾");
    getInstrumentation().runOnMainSync(()->find(dialog().getWindow().getDecorView(),"全部來源 ▾").performClick());getInstrumentation().waitForIdleSync();getInstrumentation().runOnMainSync(()->((View)find(dialog().getWindow().getDecorView(),"diagnostic.fixture").getParent()).performClick());Store.IO.submit(()->{}).get();getInstrumentation().waitForIdleSync();
    getInstrumentation().runOnMainSync(()->find(dialog().getWindow().getDecorView(),"全部結果 ▾").performClick());getInstrumentation().waitForIdleSync();getInstrumentation().runOnMainSync(()->((View)find(dialog().getWindow().getDecorView(),"暫停／排除").getParent()).performClick());Store.IO.submit(()->{}).get();getInstrumentation().waitForIdleSync();
    assertNotNull(contains(dialog().getWindow().getDecorView(),"來源已排除"));capture("diagnostics-guided-filter.png");
    AppDialog.Toggle enabled=toggle(dialog().getWindow().getDecorView());assertNotNull(enabled);getInstrumentation().runOnMainSync(enabled::performClick);Store.IO.submit(()->{}).get();getInstrumentation().waitForIdleSync();
    assertEquals(0,Store.IO.submit(()->Store.get(main).diagnosticEvents().length()).get().intValue());assertTrue(main.getSharedPreferences("diagnostic_test",0).getAll().isEmpty());
  }
  public void testDark200PercentDateAndDiagnosticActionsRemainReachable()throws Exception{
    Context c=getInstrumentation().getTargetContext();shell("cmd uimode night yes");shell("settings put system font_scale 2.0");long deadline=SystemClock.elapsedRealtime()+8000;
    while(SystemClock.elapsedRealtime()<deadline&&(c.getResources().getConfiguration().fontScale<1.9f||(c.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)!=Configuration.UI_MODE_NIGHT_YES)){getInstrumentation().waitForIdleSync();SystemClock.sleep(100);}
    main=getActivity();getInstrumentation().waitForIdleSync();assertTrue(main.getResources().getConfiguration().fontScale>=1.9f);getInstrumentation().runOnMainSync(main::dateFilterDialog);getInstrumentation().waitForIdleSync();AppDialog date=dialog();ScrollView scroll=scroll(date.getWindow().getDecorView());assertNotNull(scroll);getInstrumentation().runOnMainSync(()->scroll.fullScroll(View.FOCUS_DOWN));getInstrumentation().waitForIdleSync();android.graphics.Rect visible=new android.graphics.Rect();assertTrue(date.getButton(AppDialog.BUTTON_POSITIVE).getGlobalVisibleRect(visible));capture("date-dark-200.png");
    getInstrumentation().runOnMainSync(()->{AppDialog.dismissAll(main);Diagnostics.show(main);});Store.IO.submit(()->{}).get();getInstrumentation().waitForIdleSync();AppDialog diagnostic=dialog();for(int which:new int[]{AppDialog.BUTTON_POSITIVE,AppDialog.BUTTON_NEGATIVE,AppDialog.BUTTON_NEUTRAL})assertTrue(diagnostic.getButton(which).getGlobalVisibleRect(visible));capture("diagnostics-dark-200.png");
  }
  private void awaitRows(int count)throws Exception{Field f=MainActivity.class.getDeclaredField("rows");f.setAccessible(true);Field busy=MainActivity.class.getDeclaredField("refreshing");busy.setAccessible(true);long deadline=SystemClock.elapsedRealtime()+8000;while(SystemClock.elapsedRealtime()<deadline){Store.IO.submit(()->{}).get();getInstrumentation().waitForIdleSync();if(!busy.getBoolean(main)&&((List<?>)f.get(main)).size()==count)return;SystemClock.sleep(50);}fail("Unexpected filtered row count");}
  private void awaitText(String text)throws Exception{long deadline=SystemClock.elapsedRealtime()+8000;while(SystemClock.elapsedRealtime()<deadline){Store.IO.submit(()->{}).get();getInstrumentation().waitForIdleSync();if(dialog()!=null&&find(dialog().getWindow().getDecorView(),text)!=null)return;SystemClock.sleep(50);}fail("Missing UI text: "+text);}
  private View find(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View r=find(((ViewGroup)v).getChildAt(i),text);if(r!=null)return r;}return null;}
  private View contains(View v,String text){if(v instanceof TextView&&((TextView)v).getText().toString().contains(text))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View r=contains(((ViewGroup)v).getChildAt(i),text);if(r!=null)return r;}return null;}
  private EditText hint(View v,String text){if(v instanceof EditText&&text.contentEquals(((EditText)v).getHint()))return (EditText)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText r=hint(((ViewGroup)v).getChildAt(i),text);if(r!=null)return r;}return null;}
  private AppDialog.Toggle toggle(View v){if(v instanceof AppDialog.Toggle)return (AppDialog.Toggle)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){AppDialog.Toggle r=toggle(((ViewGroup)v).getChildAt(i));if(r!=null)return r;}return null;}
  private ScrollView scroll(View v){if(v instanceof ScrollView)return (ScrollView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){ScrollView r=scroll(((ViewGroup)v).getChildAt(i));if(r!=null)return r;}return null;}
  private void shell(String command)throws Exception{try(ParcelFileDescriptor fd=getInstrumentation().getUiAutomation().executeShellCommand(command);InputStream in=new FileInputStream(fd.getFileDescriptor())){byte[] b=new byte[1024];while(in.read(b)!=-1){}}}
  private void capture(String name)throws Exception{shell("mkdir -p /data/local/tmp/huangnotify-ui-preview");shell("screencap -p /data/local/tmp/huangnotify-ui-preview/"+name);}
}
