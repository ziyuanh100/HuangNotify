package dev.huang.notify;

import android.app.Instrumentation;
import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import java.io.FileInputStream;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

@SuppressWarnings("deprecation")
public class ExperienceUiTest extends ActivityInstrumentationTestCase2<MainActivity> {
  private MainActivity main;
  private int originalNight;
  private String originalFont;

  public ExperienceUiTest() { super(MainActivity.class); }

  @Override protected void setUp() throws Exception {
    super.setUp();
    Context context = getInstrumentation().getTargetContext();
    originalNight = context.getSystemService(UiModeManager.class).getNightMode();
    originalFont = android.provider.Settings.System.getString(context.getContentResolver(), "font_scale");
    UpdateChecker.enable(context, false);
    context.getSharedPreferences("permission_guide", 0).edit().putInt("step", 3).commit();
    Store.IO.submit(() -> {
      Store store = Store.get(context);
      store.getWritableDatabase().delete("records", null, null);
      store.getWritableDatabase().delete("settings", null, null);
      String[] sources = {"LINE", "行事曆", "Gmail"};
      String[] titles = {"週末一起去走走", "明天的行程提醒", "你的訂單已寄出"};
      String[] messages = {"下午三點在車站集合，記得帶雨傘。", "下午 2:00，討論下週計畫。", "包裹正在配送中，可以查看最新進度。"};
      for (int i = 0; i < 40; i++) {
        store.capture("experience-" + i, "experience.fixture." + i % 3, sources[i % 3],
            titles[i % 3] + " " + i, messages[i % 3], i);
      }
      store.getWritableDatabase().execSQL("UPDATE records SET favorite=1 WHERE app='LINE'");
    }).get(10, TimeUnit.SECONDS);
  }

  @Override protected void tearDown() throws Exception {
    try {
      if (main != null) getInstrumentation().runOnMainSync(() -> { AppDialog.dismissAll(main); main.finish(); });
      shell("cmd uimode night " + (originalNight == 2 ? "yes" : originalNight == 1 ? "no" : "auto"));
      shell(originalFont == null ? "settings delete system font_scale" : "settings put system font_scale " + originalFont);
      Store.IO.submit(() -> {
        Store store = Store.get(getInstrumentation().getTargetContext());
        store.getWritableDatabase().delete("records", null, null);
        store.getWritableDatabase().delete("settings", null, null);
      }).get(10, TimeUnit.SECONDS);
    } finally { super.tearDown(); }
  }

  public void testTabsKeepIndependentSearchAcrossRecreation() throws Exception {
    main = getActivity();
    await(() -> list().getAdapter().getCount() > 10);
    setQuery("訂單");
    await(() -> contains(root(), "你的訂單已寄出"));
    clickTag("nav_favorites");
    assertEquals("", search(root()).getText().toString());
    setQuery("週末");
    clickTag("nav_settings");
    recreate();
    clickTag("nav_records");
    assertEquals("訂單", search(root()).getText().toString());
    await(() -> contains(root(), "你的訂單已寄出"));
    clickTag("nav_favorites");
    assertEquals("週末", search(root()).getText().toString());
    await(() -> contains(root(), "週末一起去走走"));
    getInstrumentation().runOnMainSync(main::onBackPressed);
    assertEquals("訂單", search(root()).getText().toString());
  }

  public void testReturningFromSettingsKeepsReadingPosition() throws Exception {
    main = getActivity();
    await(() -> list().getAdapter().getCount() > 20);
    capture("experience-records.png");
    ListView original = list();
    getInstrumentation().runOnMainSync(() -> original.setSelectionFromTop(9, -18));
    getInstrumentation().waitForIdleSync();
    int position = original.getFirstVisiblePosition(), top = original.getChildAt(0).getTop();
    assertTrue(position >= 8);
    clickTag("nav_settings");
    capture("experience-settings.png");
    clickTag("nav_records");
    await(() -> list().getFirstVisiblePosition() == position);
    assertEquals(top, list().getChildAt(0).getTop());
    capture("experience-restored-position.png");
  }

  public void testLargeTextFiltersWrapAndLandscapeKeepsContentReachable() throws Exception {
    shell("cmd uimode night yes");
    shell("settings put system font_scale 2.0");
    main = getActivity();
    await(() -> main.getResources().getConfiguration().fontScale >= 1.9f
        && (main.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
        == Configuration.UI_MODE_NIGHT_YES);
    await(() -> list().getAdapter().getCount() > 10);
    assertFilterBounds();
    View nav = root().findViewWithTag("floating_nav");
    for (String page : new String[]{"records", "favorites", "analysis", "settings"}) {
      View tab = nav.findViewWithTag("nav_" + page);
      assertTrue(tab.getWidth() >= new Ui(main).d(48));
      assertTrue(tab.getHeight() >= new Ui(main).d(48));
    }
    capture("experience-dark-large.png");
    Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(MainActivity.class.getName(), null, false);
    try {
      getInstrumentation().runOnMainSync(() -> main.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
      MainActivity next = (MainActivity) getInstrumentation().waitForMonitorWithTimeout(monitor, 10000);
      assertNotNull(next);
      main = next;
      await(() -> list().getAdapter().getCount() > 10);
      assertFilterBounds();
      assertTrue("Short windows must retain a scrollable content viewport", list().getHeight() >= new Ui(main).d(100));
      capture("experience-landscape-large.png");
    } finally { getInstrumentation().removeMonitor(monitor); }
  }

  private void assertFilterBounds() {
    getInstrumentation().runOnMainSync(() -> {
      ViewGroup filters = root().findViewWithTag("search_filters");
      assertNotNull(filters);
      Rect previous = null;
      for (int i = 0; i < filters.getChildCount(); i++) {
        View child = filters.getChildAt(i);
        if (child.getVisibility() == View.GONE) continue;
        assertTrue(child.getLeft() >= filters.getPaddingLeft());
        assertTrue(child.getRight() <= filters.getWidth() - filters.getPaddingRight());
        Rect bounds = new Rect(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
        if (previous != null) assertFalse(Rect.intersects(previous, bounds));
        previous = bounds;
      }
    });
  }

  private void recreate() {
    Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(MainActivity.class.getName(), null, false);
    try {
      getInstrumentation().runOnMainSync(main::recreate);
      main = (MainActivity) getInstrumentation().waitForMonitorWithTimeout(monitor, 10000);
      assertNotNull(main);
      getInstrumentation().waitForIdleSync();
    } finally { getInstrumentation().removeMonitor(monitor); }
  }

  private View root() { return main.getWindow().getDecorView(); }
  private ListView list() { return root().findViewWithTag("records_list"); }
  private void setQuery(String text) { getInstrumentation().runOnMainSync(() -> search(root()).setText(text)); }
  private void clickTag(String tag) {
    getInstrumentation().runOnMainSync(() -> assertTrue(root().findViewWithTag(tag).performClick()));
    getInstrumentation().waitForIdleSync();
  }
  private EditText search(View view) {
    if (view instanceof EditText) return (EditText) view;
    if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
      EditText match = search(((ViewGroup) view).getChildAt(i));
      if (match != null) return match;
    }
    return null;
  }
  private boolean contains(View view, String text) {
    if (view instanceof TextView && ((TextView) view).getText().toString().startsWith(text)) return true;
    if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
      if (contains(((ViewGroup) view).getChildAt(i), text)) return true;
    return false;
  }
  private void await(BooleanSupplier condition) {
    long deadline = SystemClock.elapsedRealtime() + 10000;
    while (SystemClock.elapsedRealtime() < deadline) {
      getInstrumentation().waitForIdleSync();
      if (condition.getAsBoolean()) return;
      SystemClock.sleep(40);
    }
    fail("Expected UI state did not appear");
  }
  private void shell(String command) throws Exception {
    try (ParcelFileDescriptor fd = getInstrumentation().getUiAutomation().executeShellCommand(command);
         FileInputStream input = new FileInputStream(fd.getFileDescriptor())) {
      byte[] buffer = new byte[4096];
      while (input.read(buffer) != -1) {}
    }
  }
  private void capture(String name) throws Exception {
    getInstrumentation().waitForIdleSync();
    shell("mkdir -p /data/local/tmp/huangnotify-ui-preview");
    shell("screencap -p /data/local/tmp/huangnotify-ui-preview/" + name);
  }
}
