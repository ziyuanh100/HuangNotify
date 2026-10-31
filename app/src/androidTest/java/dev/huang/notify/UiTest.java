package dev.huang.notify;

import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

@SuppressWarnings("deprecation")
public class UiTest extends ActivityInstrumentationTestCase2<MainActivity> {
  public UiTest() {
    super(MainActivity.class);
  }

  @Override protected void setUp() throws Exception {
    super.setUp();
    getInstrumentation().getTargetContext().getSharedPreferences("permission_guide", 0).edit().putInt("step", 3).commit();
  }

  public void testNavigationAndEmptyState() throws Exception {
    MainActivity activity = getActivity();
    getInstrumentation().waitForIdleSync();
    assertNotNull(find(activity.getWindow().getDecorView(), "讓通知，好好留下。"));
    View settings = find(activity.getWindow().getDecorView(), "設定");
    assertNotNull(settings);
    getInstrumentation().runOnMainSync(() -> ((View) settings.getParent()).performClick());
    getInstrumentation().waitForIdleSync();
    assertNotNull(find(activity.getWindow().getDecorView(), "照你的方式保存"));
    assertNotNull(find(activity.getWindow().getDecorView(), "建立備份"));
    View star = find(activity.getWindow().getDecorView(), "收藏");
    getInstrumentation().runOnMainSync(() -> ((View) star.getParent()).performClick());
    getInstrumentation().waitForIdleSync();
    assertNotNull(find(activity.getWindow().getDecorView(), "值得留下的通知"));
  }

  public void testRecordingSwitchPersistsAcrossPages() throws Exception {
    MainActivity activity=getActivity();
    long deadline=android.os.SystemClock.elapsedRealtime()+8000;
    while (android.os.SystemClock.elapsedRealtime()<deadline && !recordSwitch(activity.getWindow().getDecorView()).isEnabled()) { getInstrumentation().waitForIdleSync(); android.os.SystemClock.sleep(50); }
    RecordSwitch control=recordSwitch(activity.getWindow().getDecorView()); assertTrue(control.isEnabled());
    boolean previous=control.isChecked();
    try {
      getInstrumentation().runOnMainSync(()->control.performClick());
      Store.IO.submit(()->{}).get(); getInstrumentation().waitForIdleSync();
      assertEquals(previous?"1":"0",Store.IO.submit(()->Store.get(activity).setting("paused","0")).get());
      getInstrumentation().runOnMainSync(()->AppDialog.dismissAll(activity));
      View settings=find(activity.getWindow().getDecorView(),"設定");
      getInstrumentation().runOnMainSync(()->((View)settings.getParent()).performClick()); getInstrumentation().waitForIdleSync();
      assertEquals(!previous,recordSwitch(activity.getWindow().getDecorView()).isChecked());
      try(android.os.ParcelFileDescriptor fd=getInstrumentation().getUiAutomation().executeShellCommand("screencap -p /data/local/tmp/huangnotify-ui-preview/record-switch.png"); java.io.FileInputStream in=new java.io.FileInputStream(fd.getFileDescriptor())) { byte[] bytes=new byte[1024]; while(in.read(bytes)!=-1) {} }
    } finally { Store.IO.submit(()->Store.get(activity).putSetting("paused",previous?"0":"1")).get(); }
  }
  private RecordSwitch recordSwitch(View v) {
    if(v instanceof RecordSwitch) return (RecordSwitch)v;
    if(v instanceof ViewGroup) for(int i=0;i<((ViewGroup)v).getChildCount();i++) { RecordSwitch result=recordSwitch(((ViewGroup)v).getChildAt(i)); if(result!=null)return result; }
    return null;
  }

  private View find(View v, String text) {
    if (v instanceof TextView && text.equals(((TextView) v).getText().toString())) return v;
    if (v instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) v;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = find(group.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }
}
