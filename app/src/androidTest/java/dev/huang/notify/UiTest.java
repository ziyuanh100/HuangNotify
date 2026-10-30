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
