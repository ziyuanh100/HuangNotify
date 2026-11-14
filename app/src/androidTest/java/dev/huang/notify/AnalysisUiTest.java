package dev.huang.notify;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.NumberPicker;
import android.widget.TextView;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

@SuppressWarnings("deprecation")
public class AnalysisUiTest extends ActivityInstrumentationTestCase2<MainActivity> {
  private static final String ALPHA = "analysis.fixture.alpha", BETA = "analysis.fixture.beta";
  private MainActivity main;
  private AnalysisActivity dashboard;
  private LocalDate today;
  private ZoneId zone;

  public AnalysisUiTest() { super(MainActivity.class); }

  @Override protected void setUp() throws Exception {
    super.setUp();
    Context context = getInstrumentation().getTargetContext();
    UpdateChecker.enable(context, false);
    context.getSharedPreferences("permission_guide", 0).edit().putInt("step", 3).commit();
    zone = ZoneId.systemDefault();
    today = LocalDate.now(zone);
    Store.IO.submit(() -> {
      Store store = Store.get(context);
      store.getWritableDatabase().delete("records", null, null);
      store.getWritableDatabase().delete("settings", null, null);
      save(store, "yesterday", ALPHA, "甲 App", today.minusDays(1), 84);
      save(store, "today", BETA, "乙 App", today, 2);
      save(store, "last-week", ALPHA, "甲 App", today.minusDays(8), 1);
      save(store, "older", BETA, "乙 App", today.minusDays(55), 1);
      save(store, "third", "analysis.fixture.gamma", "丙 App", today.minusDays(2), 1);
      save(store, "fourth", "analysis.fixture.delta", "丁 App", today.minusDays(3), 1);
    }).get(10, TimeUnit.SECONDS);
  }

  @Override protected void tearDown() throws Exception {
    getInstrumentation().runOnMainSync(() -> {
      if (dashboard != null) {
        AppDialog.dismissAll(dashboard);
        dashboard.finish();
      }
      if (main != null) main.finish();
    });
    Store.IO.submit(() -> Store.get(getInstrumentation().getTargetContext())
        .getWritableDatabase().delete("records", null, null)).get(10, TimeUnit.SECONDS);
    super.tearDown();
  }

  public void testDayAndAppDrillDownHaveExactCounts() throws Exception {
    main = getActivity();
    EditText originalSearch = (EditText) await(main,
        v -> v instanceof EditText && "搜尋通知、App 或備註".contentEquals(((EditText) v).getHint()),
        "records search");
    getInstrumentation().runOnMainSync(() -> originalSearch.setText("today"));
    LocalDate yesterday = today.minusDays(1);
    openDashboard();
    awaitText(dashboard, today.minusDays(6) + " ～ " + today);
    awaitText(dashboard, yesterday + " · 84 則");
    awaitText(dashboard, "84 則 · 95.5%");
    View more = awaitText(dashboard, "查看其餘 1 個 App");
    capture("analysis-overview.png");
    click(more);
    awaitText(dashboard, "丙 App");
    capture("analysis-app-ranking.png");
    scrollAnalysis(0);
    choosePeriod("今天");
    awaitText(dashboard, today + " · 2 則");
    choosePeriod("近 30 天");
    awaitText(dashboard, today.minusDays(29) + " ～ " + today);
    assertFullChart("analysis_daily_chart", 30);
    assertFullChart("analysis_hourly_chart", 24);
    dragAcrossChart("analysis_hourly_chart", 9, 23);
    awaitText(dashboard, "23:00–23:59 · 0 則");
    click(await(dashboard, v -> "上一個時段".equals(v.getContentDescription()), "previous hour"));
    awaitText(dashboard, "22:00–22:59 · 0 則");
    click(await(dashboard, v -> "00:00–00:59，0 則".equals(v.getContentDescription()), "midnight bar"));
    awaitText(dashboard, "00:00–00:59 · 0 則");
    awaitText(dashboard, "通知最多 09:00–09:59");
    scrollAnalysis(430);
    capture("analysis-charts.png");
    assertTrue(awaitText(dashboard, "近 30 天 ▾").getGlobalVisibleRect(new android.graphics.Rect()));
    assertTrue(awaitText(dashboard, "全部 App ▾").getGlobalVisibleRect(new android.graphics.Rect()));
    scrollAnalysis(0);
    click(await(dashboard, v -> "查看 甲 App 的趨勢".equals(v.getContentDescription()), "App trend"));
    awaitText(dashboard, "甲 App ▾");
    View trend = await(dashboard, v -> "analysis_daily_section".equals(v.getTag())
        && v.getGlobalVisibleRect(new android.graphics.Rect()), "selected App trend");
    int trendTop = locationY(trend);
    chooseApp("甲 App ▾", BETA);
    awaitText(dashboard, "乙 App ▾");
    awaitText(dashboard, today + " · 2 則");
    awaitText(dashboard, today.minusDays(29) + " ～ " + today);
    View newTrend = await(dashboard, v -> "analysis_daily_section".equals(v.getTag()) && v != trend
        && v.getGlobalVisibleRect(new android.graphics.Rect()), "App trend after switching");
    assertEquals(trendTop, locationY(newTrend));
    chooseApp("乙 App ▾", ALPHA);
    awaitText(dashboard, "甲 App ▾");
    dashboard = startDashboard(() -> getInstrumentation().runOnMainSync(dashboard::recreate));
    awaitText(dashboard, "甲 App ▾");
    View day = await(dashboard, v -> (yesterday + "，84 則").equals(v.getContentDescription()), "yesterday bar");
    click(day);
    assertTrue(day.isSelected());
    click(await(dashboard, v -> "analysis_daily_records".equals(v.getTag()), "view selected date"));
    awaitText(main, "分析紀錄");
    awaitText(main, yesterday.toString());
    awaitText(main, "84 則");
    awaitText(main, "甲 App");
    View nav = await(main, v -> v instanceof GlassNavigation, "main navigation");
    assertEquals(View.GONE, nav.getVisibility());
    EditText scopedSearch = (EditText) await(main,
        v -> v instanceof EditText && "搜尋這些通知".contentEquals(((EditText) v).getHint()),
        "search within the analysis scope");
    getInstrumentation().runOnMainSync(() -> scopedSearch.setText("yesterday 83"));
    awaitText(main, "1 則");

    getInstrumentation().waitForIdleSync();
    dashboard = startDashboard(() -> getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK));
    awaitText(dashboard, today.minusDays(29) + " ～ " + today);
    awaitText(dashboard, "甲 App ▾");
    View restored = await(dashboard, v -> (yesterday + "，84 則").equals(v.getContentDescription()), "restored date");
    assertTrue(restored.isSelected());
    choosePeriod("今天");
    awaitText(dashboard, "這段期間沒有已保存的通知");
    assertNull(dashboard.getWindow().getDecorView().findViewWithTag("analysis_daily_chart"));
    click(awaitText(dashboard, "查看近 30 天"));
    awaitText(dashboard, "甲 App ▾");
    chooseApp("甲 App ▾", null);
    awaitText(dashboard, "App 排行");
    int scrollBeforeDrill = scrollAnalysis(96);
    assertTrue("The ranking should be scrollable", scrollBeforeDrill < 0);
    click(await(dashboard, v -> ALPHA.equals(v.getTag()), "App records"));
    awaitText(main, today.minusDays(29) + " ～ " + today);
    awaitText(main, "85 則");
    awaitText(main, "甲 App");
    View back = awaitText(main, "返回分析");
    dashboard = startDashboard(() -> click(back));
    awaitText(dashboard, "App 排行");
    assertEquals(scrollBeforeDrill, scrollAnalysis(null));
    click(awaitText(dashboard, "返回"));
    awaitText(main, "通知紀錄");
    await(main, v -> v instanceof EditText && "today".contentEquals(((EditText) v).getText()),
        "restored records search");
    assertEquals(View.VISIBLE, nav.getVisibility());
  }

  public void testCustomRangeAndEarlierChartPage() throws Exception {
    main = getActivity();
    openDashboard();
    choosePeriod("近 30 天");
    awaitText(dashboard, today.minusDays(29) + " ～ " + today);
    choosePeriod("自訂日期");
    AppDialog dialog = activeDialog(dashboard);
    View dialogView = dialog.getWindow().getDecorView();
    LocalDate first = today.minusDays(60), last = today.minusDays(15);
    jumpToMonth(dialogView, YearMonth.from(first));
    View firstDay = dialogView.findViewWithTag(first.toString());
    assertNotNull(firstDay);
    click(firstDay);
    assertTrue(dialogView.findViewWithTag("analysis_end_date").isSelected());
    if (first.getDayOfMonth() > 1) {
      View earlier = dialogView.findViewWithTag(first.minusDays(1).toString());
      assertNotNull(earlier);
      assertFalse(earlier.isEnabled());
    }
    jumpToMonth(dialogView, YearMonth.from(last));
    View lastDay = dialogView.findViewWithTag(last.toString());
    assertNotNull(lastDay);
    click(lastDay);
    assertTrue(lastDay.isSelected());
    assertSame(dialog, activeDialog(dashboard));
    assertNotNull(find(dialogView, v -> v instanceof TextView && "共 46 天".contentEquals(((TextView) v).getText())));
    getInstrumentation().waitForIdleSync();
    View apply = dialog.getButton(AppDialog.BUTTON_POSITIVE);
    android.graphics.Rect visible = new android.graphics.Rect();
    assertTrue(apply.getGlobalVisibleRect(visible));
    assertEquals(apply.getHeight(), visible.height());
    capture("analysis-date-range.png");
    click(apply);
    awaitText(dashboard, first + " ～ " + last);
    LocalDate older = today.minusDays(55);
    awaitText(dashboard, older + " · 1 則");
    click(awaitText(dashboard, "前一段"));
    click(await(dashboard, v -> (older + "，1 則").equals(v.getContentDescription()), "earlier date bar"));
    click(await(dashboard, v -> "analysis_daily_records".equals(v.getTag()), "view earlier date"));
    awaitText(main, older.toString());
    awaitText(main, "1 則");
  }

  private void choosePeriod(String name) throws Exception {
    click(await(dashboard, v -> v.getContentDescription() != null
        && v.getContentDescription().toString().startsWith("日期範圍，"), "date range filter"));
    View option = find(activeDialog(dashboard).getWindow().getDecorView(),
        v -> name.equals(v.getContentDescription()));
    assertNotNull(option);
    click(option);
  }

  private void chooseApp(String current, String target) throws Exception {
    click(awaitText(dashboard, current));
    AppDialog picker = activeDialog(dashboard);
    View decor = picker.getWindow().getDecorView();
    if (target != null) {
      EditText search = (EditText) find(decor, v -> v instanceof EditText);
      assertNotNull(search);
      getInstrumentation().runOnMainSync(() -> search.setText(target));
    }
    String tag = target == null ? "analysis_picker_all" : target;
    long deadline = SystemClock.elapsedRealtime() + 10000;
    View option;
    do {
      getInstrumentation().waitForIdleSync();
      option = decor.findViewWithTag(tag);
      if (option != null) break;
      SystemClock.sleep(40);
    } while (SystemClock.elapsedRealtime() < deadline);
    assertNotNull("Missing App filter option " + tag, option);
    click(option);
  }

  private void jumpToMonth(View calendar, YearMonth target) throws Exception {
    View month = find(calendar, v -> v.getContentDescription() != null
        && v.getContentDescription().toString().startsWith("跳到年月，"));
    assertNotNull(month);
    click(month);
    AppDialog dialog = activeDialog(dashboard);
    View root = dialog.getWindow().getDecorView();
    NumberPicker year = (NumberPicker) find(root, v -> v instanceof NumberPicker
        && "年".equals(v.getContentDescription()));
    NumberPicker monthNumber = (NumberPicker) find(root, v -> v instanceof NumberPicker
        && "月".equals(v.getContentDescription()));
    assertNotNull(year);
    assertNotNull(monthNumber);
    getInstrumentation().runOnMainSync(() -> {
      year.setValue(target.getYear());
      monthNumber.setValue(target.getMonthValue());
    });
    click(dialog.getButton(AppDialog.BUTTON_POSITIVE));
  }

  private int locationY(View view) {
    int[] position = new int[2];
    getInstrumentation().runOnMainSync(() -> view.getLocationOnScreen(position));
    return position[1];
  }

  private void dragAcrossChart(String tag, int from, int to) throws Exception {
    ViewGroup chart = (ViewGroup) await(dashboard, v -> tag.equals(v.getTag()) && v.getWidth() > 0, tag);
    getInstrumentation().runOnMainSync(() -> {
      ViewGroup plot = (ViewGroup) chart.getChildAt(0);
      View start = plot.getChildAt(from), end = plot.getChildAt(to);
      float startX = start.getLeft() + start.getWidth() / 2f;
      float endX = end.getLeft() + end.getWidth() / 2f;
      float y = plot.getHeight() / 2f;
      long down = SystemClock.uptimeMillis();
      for (int step = 0; step <= 4; step++) {
        int action = step == 0 ? MotionEvent.ACTION_DOWN
            : step == 4 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
        MotionEvent event = MotionEvent.obtain(down, down + step * 16L, action,
            startX + (endX - startX) * step / 4, y, 0);
        try {
          chart.dispatchTouchEvent(event);
        } finally {
          event.recycle();
        }
      }
      assertTrue(end.isSelected());
    });
  }

  private void assertFullChart(String tag, int count) throws Exception {
    ViewGroup chart = (ViewGroup) await(dashboard, v -> tag.equals(v.getTag()) && v.getWidth() > 0, tag);
    getInstrumentation().runOnMainSync(() -> {
      ViewGroup plot = (ViewGroup) chart.getChildAt(0);
      assertEquals(count, plot.getChildCount());
      assertTrue(plot.getWidth() > 0);
      assertEquals(0, plot.getChildAt(0).getLeft());
      assertEquals(plot.getWidth(), plot.getChildAt(count - 1).getRight());
      for (int i = 0; i < count; i++) assertTrue(plot.getChildAt(i).getWidth() > 0);
    });
  }

  private void save(Store store, String key, String pkg, String name, LocalDate day, int count) {
    for (int i = 0; i < count; i++) {
      store.capture(key + "-" + i, pkg, name, key + " " + i, "通知", i + 1);
    }
    long time = day.atTime(9, 30).atZone(zone).toInstant().toEpochMilli();
    store.getWritableDatabase().execSQL("UPDATE records SET first_seen=? WHERE nkey LIKE ?",
        new Object[]{time, key + "-%"});
  }

  private void openDashboard() throws Exception {
    View entry = awaitText(main, "通知分析");
    dashboard = startDashboard(() -> click(entry));
  }

  private AnalysisActivity startDashboard(Runnable action) {
    Instrumentation instrumentation = getInstrumentation();
    Instrumentation.ActivityMonitor monitor =
        instrumentation.addMonitor(AnalysisActivity.class.getName(), null, false);
    try {
      action.run();
      AnalysisActivity next =
          (AnalysisActivity) instrumentation.waitForMonitorWithTimeout(monitor, 10000);
      assertNotNull(next);
      return next;
    } finally {
      instrumentation.removeMonitor(monitor);
    }
  }

  private int scrollAnalysis(Integer pixels) throws Exception {
    ListView list = (ListView) await(dashboard, v -> "analysis_list".equals(v.getTag()), "dashboard");
    if (pixels != null) getInstrumentation().runOnMainSync(() -> list.setSelectionFromTop(0, -pixels));
    getInstrumentation().waitForIdleSync();
    AtomicReference<Integer> offset = new AtomicReference<>();
    getInstrumentation().runOnMainSync(() -> offset.set(list.getChildAt(0).getTop()));
    return offset.get();
  }

  private void click(View view) {
    getInstrumentation().runOnMainSync(() -> assertTrue(view.performClick()));
  }

  private View awaitText(Activity activity, String label) throws Exception {
    return await(activity, v -> v instanceof TextView && label.contentEquals(((TextView) v).getText()), label);
  }

  private View await(Activity activity, Predicate<View> test, String label) throws Exception {
    AtomicReference<View> found = new AtomicReference<>();
    long deadline = SystemClock.elapsedRealtime() + 10000;
    do {
      getInstrumentation().waitForIdleSync();
      getInstrumentation().runOnMainSync(() -> found.set(find(activity.getWindow().getDecorView(), test)));
      if (found.get() != null) return found.get();
      SystemClock.sleep(40);
    } while (SystemClock.elapsedRealtime() < deadline);
    fail("Missing UI view: " + label);
    return null;
  }

  private View find(View view, Predicate<View> test) {
    if (test.test(view)) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View match = find(group.getChildAt(i), test);
        if (match != null) return match;
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private AppDialog activeDialog(Activity activity) throws Exception {
    Field open = AppDialog.class.getDeclaredField("OPEN");
    open.setAccessible(true);
    Map<Context, List<AppDialog>> dialogs = (Map<Context, List<AppDialog>>) open.get(null);
    List<AppDialog> active = dialogs.get(activity);
    assertNotNull(active);
    return active.get(active.size() - 1);
  }

  private void capture(String file) throws Exception {
    String path = "/data/local/tmp/huangnotify-ui-preview";
    shell("mkdir -p " + path);
    shell("screencap -p " + path + "/" + file);
  }

  private void shell(String command) throws Exception {
    try (ParcelFileDescriptor fd = getInstrumentation().getUiAutomation().executeShellCommand(command);
         FileInputStream stream = new FileInputStream(fd.getFileDescriptor())) {
      byte[] buffer = new byte[1024];
      while (stream.read(buffer) != -1) {}
    }
  }
}
