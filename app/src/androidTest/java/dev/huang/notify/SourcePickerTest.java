package dev.huang.notify;

import android.test.ActivityInstrumentationTestCase2;
import android.view.*;
import java.util.*;

@SuppressWarnings("deprecation")
public class SourcePickerTest extends ActivityInstrumentationTestCase2<MainActivity> {
  private MainActivity activity; private SourcePicker picker; private Map<String,Boolean> saved;
  public SourcePickerTest() { super(MainActivity.class); }
  @Override protected void setUp() throws Exception {
    super.setUp(); android.content.Context c=getInstrumentation().getTargetContext(); UpdateChecker.enable(c,false);
    c.getSharedPreferences("permission_guide",0).edit().putInt("step",3).commit();
    UpgradeManager.prefs(c).edit().clear().commit(); activity=getActivity(); getInstrumentation().waitForIdleSync();
  }
  @Override protected void tearDown() throws Exception {
    getInstrumentation().runOnMainSync(()->{ if(picker!=null) picker.dialog.dismiss(); AppDialog.dismissAll(activity); }); super.tearDown();
  }
  private List<AppSources.Entry> fixture() {
    return Arrays.asList(new AppSources.Entry("sample.fresh","新 App",true,false,true,0,true),
      new AppSources.Entry("sample.blocked","已排除",true,false,true,0,false),
      new AppSources.Entry("system.hidden","系統 App",true,true,false,0,true),
      new AppSources.Entry("removed.history","歷史來源",false,false,false,3,true));
  }
  private void open(List<AppSources.Entry> entries) {
    getInstrumentation().runOnMainSync(()->{ picker=new SourcePicker(activity,entries,changes->saved=changes); picker.show(); }); getInstrumentation().waitForIdleSync();
  }
  public void testSearchAndBulkSelectionAffectOnlyMatchingPackages() throws Exception {
    open(fixture()); assertEquals(4,picker.list.getAdapter().getCount());
    getInstrumentation().runOnMainSync(()->{ picker.search.setText("FRESH"); picker.selectVisible(false); });
    assertEquals(1,picker.list.getAdapter().getCount()); assertEquals(Boolean.FALSE,picker.changes().get("sample.fresh")); assertEquals(1,picker.changes().size());
    getInstrumentation().runOnMainSync(()->{ picker.search.setText(""); picker.filter(2); picker.selectVisible(false); picker.search.setText("不存在"); picker.selectVisible(true); });
    assertEquals(0,picker.list.getAdapter().getCount()); assertEquals(2,picker.changes().size());
    getInstrumentation().runOnMainSync(()->picker.dialog.getButton(AppDialog.BUTTON_POSITIVE).performClick());
    assertNotNull(saved); assertEquals(Boolean.FALSE,saved.get("system.hidden")); assertFalse(saved.containsKey("sample.blocked"));
  }
  public void testCancelDoesNotSaveAndRecycledRowsKeepTheirOwnState() {
    open(fixture());
    getInstrumentation().runOnMainSync(()->{
      android.widget.Adapter adapter=picker.list.getAdapter();
      AppDialog.Toggle row=(AppDialog.Toggle)adapter.getView(0,null,picker.list); row.performClick();
      assertFalse(row.isChecked());
      row=(AppDialog.Toggle)adapter.getView(1,row,picker.list); assertFalse(row.isChecked()); row.performClick();
      row=(AppDialog.Toggle)adapter.getView(0,row,picker.list); assertFalse(row.isChecked());
      picker.dialog.getButton(AppDialog.BUTTON_NEGATIVE).performClick();
    }); assertNull(saved);
  }
  public void testSettingsOpensInstalledAppsEvenWithoutNotificationHistory() throws Exception {
    View setting=find(activity.getWindow().getDecorView(),"設定");
    getInstrumentation().runOnMainSync(()->((View)setting.getParent()).performClick()); getInstrumentation().waitForIdleSync();
    View source=find(activity.getWindow().getDecorView(),"選擇記錄來源");
    getInstrumentation().runOnMainSync(()->((View)source.getParent().getParent()).performClick());
    Store.IO.submit(()->{}).get(); getInstrumentation().waitForIdleSync(); picker=activity.sourcePicker;
    assertNotNull(picker); assertTrue(picker.dialog.isShowing());
    getInstrumentation().runOnMainSync(()->picker.search.setText("dev.huang.notify.probe"));
    getInstrumentation().waitForIdleSync(); getInstrumentation().getUiAutomation().waitForIdle(100,5000);
    assertEquals(1,picker.list.getAdapter().getCount()); assertNotNull(picker.list.findViewWithTag("dev.huang.notify.probe")); capture("all-app-source-search.png");
  }
  public void testLargeInventoryIsRecycledAndActionsStayVisible() throws Exception {
    List<AppSources.Entry> entries=new ArrayList<>();
    for(int i=0;i<1000;i++) entries.add(new AppSources.Entry("fixture.app"+i,"測試 App "+i,true,false,true,0,true));
    open(entries); assertEquals(1000,picker.list.getAdapter().getCount()); assertTrue(picker.list.getChildCount()<20);
    assertTrue(picker.list.getHeight()>0); android.graphics.Rect area=new android.graphics.Rect();
    assertTrue(picker.dialog.getButton(AppDialog.BUTTON_POSITIVE).getGlobalVisibleRect(area));
    assertTrue(area.height()>=new Ui(activity).d(40)); capture("all-app-source-list.png");
  }
  public void testDarkLargeTextStillAllowsSearchingAndSaving() throws Exception {
    getInstrumentation().runOnMainSync(()->{
      android.view.ContextThemeWrapper context=new android.view.ContextThemeWrapper(activity,android.R.style.Theme_Material_NoActionBar);
      android.content.res.Configuration config=new android.content.res.Configuration(activity.getResources().getConfiguration());
      config.uiMode=(config.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK) | android.content.res.Configuration.UI_MODE_NIGHT_YES;
      config.fontScale=1.8f; context.applyOverrideConfiguration(config);
      picker=new SourcePicker(context,fixture(),changes->saved=changes); picker.show(); picker.search.setText("sample.fresh");
    }); getInstrumentation().waitForIdleSync();
    assertEquals(1,picker.list.getAdapter().getCount()); assertTrue(picker.list.getHeight()>0);
    android.graphics.Rect area=new android.graphics.Rect(); assertTrue(picker.dialog.getButton(AppDialog.BUTTON_POSITIVE).getGlobalVisibleRect(area));
    capture("all-app-source-dark-large.png");
  }
  public void testShortWindowKeepsSearchResultsAndSaveVisible() {
    open(fixture()); getInstrumentation().runOnMainSync(()->{
      picker.dialog.getWindow().setLayout(new Ui(activity).d(340),new Ui(activity).d(400)); picker.search.setText("sample.fresh");
    }); getInstrumentation().waitForIdleSync();
    assertTrue(picker.list.getHeight()>0); assertEquals(1,picker.list.getAdapter().getCount());
    android.graphics.Rect area=new android.graphics.Rect(); assertTrue(picker.dialog.getButton(AppDialog.BUTTON_POSITIVE).getGlobalVisibleRect(area));
  }
  private View find(View v,String text) {
    if(v instanceof android.widget.TextView && text.equals(((android.widget.TextView)v).getText().toString())) return v;
    if(v instanceof ViewGroup) for(int i=0;i<((ViewGroup)v).getChildCount();i++) { View result=find(((ViewGroup)v).getChildAt(i),text); if(result!=null) return result; }
    return null;
  }
  private void capture(String name) throws Exception {
    getInstrumentation().waitForIdleSync(); getInstrumentation().getUiAutomation().waitForIdle(100,5000);
    for(String command:new String[]{"mkdir -p /data/local/tmp/huangnotify-ui-preview","screencap -p /data/local/tmp/huangnotify-ui-preview/"+name})
      try(android.os.ParcelFileDescriptor fd=getInstrumentation().getUiAutomation().executeShellCommand(command); java.io.FileInputStream in=new java.io.FileInputStream(fd.getFileDescriptor())) { byte[] bytes=new byte[1024]; while(in.read(bytes)!=-1) {} }
  }
}
