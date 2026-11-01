package dev.huang.notify;

import android.test.ActivityInstrumentationTestCase2;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("deprecation")
public class UpdateFlowTest extends ActivityInstrumentationTestCase2<MainActivity> {
  private UpdateChecker.Source old;
  private MainActivity activity;
  private AtomicInteger calls;
  public UpdateFlowTest() { super(MainActivity.class); }
  @Override protected void setUp() throws Exception {
    super.setUp(); android.content.Context c=getInstrumentation().getTargetContext();
    c.getSharedPreferences("permission_guide",0).edit().putInt("step",3).commit();
    UpdateChecker.prefs(c).edit().clear().putBoolean("enabled",true).putLong("checked",System.currentTimeMillis()).commit();
    UpgradeManager.prefs(c).edit().clear().commit(); old=UpdateChecker.source; calls=new AtomicInteger();
    UpdateChecker.source=(context,previews)->{ calls.incrementAndGet(); return new UpdateChecker.NetworkResult(
      new UpdateChecker.Result("v9.0.0","https://github.com/ziyuanh100/HuangNotify/releases/tag/v9.0.0","發現新版本 v9.0.0",
      "https://github.com/ziyuanh100/HuangNotify/releases/download/v9.0.0/HuangNotify.apk",new String(new char[64]).replace('\0','a'),100),"test"); };
  }
  @Override protected void tearDown() throws Exception {
    if(activity!=null) getInstrumentation().runOnMainSync(()->{ activity.updateFlow.destroy(); AppDialog.dismissAll(activity); });
    UpdateChecker.source=old; UpdateChecker.enable(getInstrumentation().getTargetContext(),false);
    UpgradeManager.prefs(getInstrumentation().getTargetContext()).edit().clear().commit(); super.tearDown();
  }
  private void awaitDialog(String title) throws Exception {
    long end=android.os.SystemClock.elapsedRealtime()+8000;
    while(android.os.SystemClock.elapsedRealtime()<end) {
      getInstrumentation().waitForIdleSync();
      AppDialog dialog=activity.updateFlow.dialog;
      if(dialog!=null && dialog.isShowing() && dialog.getButton(AppDialog.BUTTON_POSITIVE)!=null
          && (title==null || dialog.getButton(AppDialog.BUTTON_POSITIVE).getText().toString().equals(title))) return;
      android.os.SystemClock.sleep(50);
    } fail("更新提示未顯示");
  }
  public void testEntryOfferLaterAndNextForegroundEntry() throws Exception {
    activity=getActivity(); awaitDialog("現在更新"); assertEquals(1,calls.get()); capture("update-offer.png");
    getInstrumentation().runOnMainSync(()->activity.updateFlow.dialog.getButton(AppDialog.BUTTON_NEGATIVE).performClick());
    getInstrumentation().runOnMainSync(()->{ activity.updateFlow.pause(); activity.updateFlow.resume(); });
    getInstrumentation().waitForIdleSync(); assertEquals(1,calls.get());
    getInstrumentation().runOnMainSync(()->{ activity.updateFlow.pause(); activity.updateFlow.leave(); activity.updateFlow.resume(); });
    awaitDialog("現在更新"); assertEquals(2,calls.get());
  }
  public void testNowUpdateStaysInAppWithProgress() throws Exception {
    activity=getActivity(); awaitDialog("現在更新");
    boolean[] requested={false};
    getInstrumentation().runOnMainSync(()->{
      activity.updateFlow.download=result->{ requested[0]=true; UpgradeManager.stage(activity,"queued","準備下載更新…"); };
      activity.updateFlow.dialog.getButton(AppDialog.BUTTON_POSITIVE).performClick();
    }); getInstrumentation().waitForIdleSync();
    assertTrue(requested[0]); assertTrue(activity.updateFlow.dialog.isShowing()); assertFalse(activity.isFinishing());
    assertTrue(UpgradeManager.prefs(activity).getBoolean("show_progress",false)); capture("in-app-update-progress.png");
    getInstrumentation().runOnMainSync(()->activity.updateFlow.dialog.getButton(AppDialog.BUTTON_NEGATIVE).performClick());
    assertFalse(UpgradeManager.prefs(activity).getBoolean("show_progress",true));
  }
  public void testFailedDownloadHasWorkingRetryButton() throws Exception {
    activity=getActivity(); awaitDialog("現在更新"); AtomicInteger attempts=new AtomicInteger();
    getInstrumentation().runOnMainSync(()->{
      activity.updateFlow.download=result->{ attempts.incrementAndGet(); UpgradeManager.stage(activity,"failed","網路中斷"); };
      activity.updateFlow.dialog.getButton(AppDialog.BUTTON_POSITIVE).performClick();
    });
    awaitDialog("重新下載"); assertTrue(activity.updateFlow.dialog.getButton(AppDialog.BUTTON_POSITIVE).isEnabled());
    getInstrumentation().runOnMainSync(()->activity.updateFlow.dialog.getButton(AppDialog.BUTTON_POSITIVE).performClick());
    assertEquals(2,attempts.get());
  }
  public void testDisabledChecksSwitchRemainsOff() throws Exception {
    UpdateChecker.enable(getInstrumentation().getTargetContext(),false); activity=getActivity(); getInstrumentation().waitForIdleSync();
    assertEquals(0,calls.get());
    getInstrumentation().runOnMainSync(()->{
      android.view.View settings=find(activity.getWindow().getDecorView(),"設定"); ((android.view.View)settings.getParent()).performClick();
    }); getInstrumentation().waitForIdleSync();
    RecordSwitch control=activity.getWindow().getDecorView().findViewWithTag("update_check"); assertNotNull(control); assertFalse(control.isChecked());
  }
  public void testCompletionSurvivesReentryAndCanBeAcknowledged() throws Exception {
    android.content.Context c=getInstrumentation().getTargetContext(); UpdateChecker.enable(c,false);
    UpdateCompletion.mark(c); assertTrue(UpgradeManager.prefs(c).getBoolean("completion_pending",false));
    activity=getActivity(); awaitDialog("完成並重新開啟"); capture("update-complete.png");
    getInstrumentation().runOnMainSync(()->activity.updateFlow.dialog.getButton(AppDialog.BUTTON_NEGATIVE).performClick());
    assertFalse(UpgradeManager.prefs(c).getBoolean("completion_pending",true));
    UpdateCompletion.mark(c); assertFalse(UpgradeManager.prefs(c).getBoolean("completion_pending",true));
  }
  private android.view.View find(android.view.View view,String value) {
    if(view instanceof android.widget.TextView && value.equals(((android.widget.TextView)view).getText().toString()))return view;
    if(view instanceof android.view.ViewGroup) for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++) { android.view.View child=find(((android.view.ViewGroup)view).getChildAt(i),value); if(child!=null)return child; }
    return null;
  }
  private void capture(String name) throws Exception {
    for(String command:new String[]{"mkdir -p /data/local/tmp/huangnotify-ui-preview","screencap -p /data/local/tmp/huangnotify-ui-preview/"+name})
      try(android.os.ParcelFileDescriptor fd=getInstrumentation().getUiAutomation().executeShellCommand(command); java.io.FileInputStream input=new java.io.FileInputStream(fd.getFileDescriptor())) { byte[] bytes=new byte[1024]; while(input.read(bytes)!=-1) {} }
  }
}
