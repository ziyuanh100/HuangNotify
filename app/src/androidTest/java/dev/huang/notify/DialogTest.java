package dev.huang.notify;

import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

@SuppressWarnings("deprecation")
public class DialogTest extends ActivityInstrumentationTestCase2<MainActivity> {
  private MainActivity activity;
  private AppDialog dialog;
  public DialogTest() { super(MainActivity.class); }
  @Override protected void setUp() throws Exception {
    super.setUp();
    UpdateChecker.enable(getInstrumentation().getTargetContext(),false);
    getInstrumentation().getTargetContext().getSharedPreferences("permission_guide", 0).edit().putInt("step", 3).commit();
    activity = getActivity(); getInstrumentation().waitForIdleSync();
  }
  @Override protected void tearDown() throws Exception {
    getInstrumentation().runOnMainSync(() -> { if (dialog != null) dialog.dismiss(); AppDialog.dismissAll(activity); });
    super.tearDown();
  }
  public void testSingleChoiceInvokesCorrectSelection() throws Exception {
    int[] selected = {-1};
    getInstrumentation().runOnMainSync(() -> dialog = new AppDialog.Builder(activity).setTitle("保存多久的通知？")
        .setSingleChoiceItems(new String[] {"持續保留", "7 天", "30 天", "90 天", "365 天"}, 0,
            (d, i) -> { selected[0] = i; d.dismiss(); }).show());
    getInstrumentation().waitForIdleSync(); capture("choices-light.png");
    getInstrumentation().runOnMainSync(() -> ((View) find(dialog.getWindow().getDecorView(), "30 天").getParent()).performClick());
    assertEquals(2, selected[0]); assertFalse(dialog.isShowing());
  }
  public void testMultiSelectionAndExplicitConfirmation() throws Exception {
    boolean[] checked = {true, false}; boolean[] confirmed = {false};
    getInstrumentation().runOnMainSync(() -> dialog = new AppDialog.Builder(activity).setTitle("要記錄哪些 App？")
        .setMultiChoiceItems(new String[] {"行事曆", "郵件"}, checked, (d, i, value) -> checked[i] = value)
        .setNegativeButton("取消", null).setPositiveButton("儲存", (d, w) -> confirmed[0] = true).show());
    getInstrumentation().runOnMainSync(() -> ((View) find(dialog.getWindow().getDecorView(), "郵件").getParent()).performClick());
    assertTrue(checked[1]); assertTrue(dialog.isShowing()); assertFalse(confirmed[0]);
    getInstrumentation().runOnMainSync(() -> dialog.getButton(AppDialog.BUTTON_POSITIVE).performClick());
    assertTrue(confirmed[0]); assertFalse(dialog.isShowing());
  }
  public void testValidationCanKeepDialogOpen() throws Exception {
    getInstrumentation().runOnMainSync(() -> {
      dialog = new AppDialog.Builder(activity).setTitle("建立備份").setPositiveButton("加密匯出", null).create();
      dialog.setOnShowListener(d -> dialog.getButton(AppDialog.BUTTON_POSITIVE).setOnClickListener(v -> {})); dialog.show();
    });
    getInstrumentation().waitForIdleSync();
    getInstrumentation().runOnMainSync(() -> dialog.getButton(AppDialog.BUTTON_POSITIVE).performClick());
    assertTrue(dialog.isShowing());
  }
  public void testFirstUseGuideCanBeSkippedWithoutReprompt() throws Exception {
    getInstrumentation().runOnMainSync(() -> activity.permissionGuide.restart());
    getInstrumentation().waitForIdleSync(); capture("permission-guide.png");
    getInstrumentation().runOnMainSync(() -> activity.permissionGuide.dialog.getButton(AppDialog.BUTTON_NEGATIVE).performClick());
    assertEquals(3, activity.getSharedPreferences("permission_guide", 0).getInt("step", 0));
    getInstrumentation().runOnMainSync(() -> activity.permissionGuide.resume());
    assertFalse(activity.permissionGuide.dialog.isShowing());
  }
  public void testDarkDialogLargeTextRemainsScrollable() throws Exception {
    getInstrumentation().runOnMainSync(() -> {
      android.view.ContextThemeWrapper context = new android.view.ContextThemeWrapper(activity, android.R.style.Theme_Material_NoActionBar);
      android.content.res.Configuration config = new android.content.res.Configuration(activity.getResources().getConfiguration());
      config.uiMode = (config.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK) | android.content.res.Configuration.UI_MODE_NIGHT_YES;
      config.fontScale = 1.6f; context.applyOverrideConfiguration(config);
      dialog = new AppDialog.Builder(context).setTitle("要記錄哪些 App？")
          .setMessage("選擇你想保存的通知來源，之後可以隨時調整。")
          .setMultiChoiceItems(new String[] {"行事曆", "郵件", "訊息", "待辦事項"}, new boolean[] {true, true, false, true}, null)
          .setPositiveButton("儲存", null).setNegativeButton("取消", null).show();
    });
    getInstrumentation().waitForIdleSync(); capture("choices-dark-large.png");
    assertNotNull(dialog.getButton(AppDialog.BUTTON_POSITIVE));
    assertTrue(dialog.getWindow().getDecorView().getHeight() <= activity.getResources().getDisplayMetrics().heightPixels);
  }
  private void capture(String name) throws Exception {
    // UiAutomation executes one command directly; it does not interpret shell operators.
    String dir = "/data/local/tmp/huangnotify-ui-preview";
    shell("mkdir -p " + dir);
    shell("screencap -p " + dir + "/" + name);
    assertTrue(Long.parseLong(shell("stat -c %s " + dir + "/" + name).trim()) > 0);
  }
  private String shell(String command) throws Exception {
    try (android.os.ParcelFileDescriptor descriptor = getInstrumentation().getUiAutomation().executeShellCommand(command);
         java.io.FileInputStream input = new java.io.FileInputStream(descriptor.getFileDescriptor());
         java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
      byte[] buffer = new byte[1024]; int count;
      while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
      return output.toString("UTF-8");
    }
  }

  private View find(View v, String text) {
    if (v instanceof TextView && text.equals(((TextView) v).getText().toString())) return v;
    if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
      View found = find(((ViewGroup) v).getChildAt(i), text); if (found != null) return found;
    }
    return null;
  }
}
