package dev.huang.notify;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.DialogInterface;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.time.LocalDate;
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
    click(awaitText(dashboard, "今天"));
    awaitText(dashboard, today + " · 2 則");
    click(awaitText(dashboard, "近 30 天"));
    awaitText(dashboard, today.minusDays(29) + " ～ " + today);
    assertFullChart("analysis_daily_chart", 30);
    assertFullChart("analysis_hourly_chart", 24);
    click(await(dashboard, v -> "00:00–00:59，0 則".equals(v.getContentDescription()), "midnight bar"));
    awaitText(dashboard, "00:00–00:59 · 0 則");
    awaitText(dashboard, "通知最多 09:00–09:59");
    scrollAnalysis(430);
    capture("analysis-charts.png");
    scrollAnalysis(0);
    click(await(dashboard, v -> "查看 甲 App 的趨勢".equals(v.getContentDescription()), "App trend"));
    awaitText(dashboard, "甲 App · 顯示全部 App");
    dashboard = startDashboard(() -> getInstrumentation().runOnMainSync(dashboard::recreate));
    awaitText(dashboard, "甲 App · 顯示全部 App");
    View day = await(dashboard, v -> (yesterday + "，84 則").equals(v.getContentDescription()), "yesterday bar");
    click(day);
    assertTrue(day.isSelected());
    click(await(dashboard, v -> "analysis_daily_records".equals(v.getTag()), "view selected date"));
    awaitText(main, yesterday + " ～ " + yesterday);
    awaitText(main, "84 則");

    dashboard = startDashboard(() -> getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK));
    awaitText(dashboard, today.minusDays(29) + " ～ " + today);
    awaitText(dashboard, "甲 App · 顯示全部 App");
    View restored = await(dashboard, v -> (yesterday + "，84 則").equals(v.getContentDescription()), "restored date");
    assertTrue(restored.isSelected());
    click(awaitText(dashboard, "今天"));
    awaitText(dashboard, "這段期間沒有已保存的通知");
    assertNull(dashboard.getWindow().getDecorView().findViewWithTag("analysis_daily_chart"));
    click(awaitText(dashboard, "查看近 30 天"));
    awaitText(dashboard, "甲 App · 顯示全部 App");
    click(awaitText(dashboard, "甲 App · 顯示全部 App"));
    awaitText(dashboard, "App 排行");
    int scrollBeforeDrill = scrollAnalysis(96);
    assertTrue("The ranking should be scrollable", scrollBeforeDrill < 0);
    click(await(dashboard, v -> ALPHA.equals(v.getTag()), "App records"));
    awaitText(main, today.minusDays(29) + " ～ " + today);
    awaitText(main, "85 則");
    awaitText(main, "甲 App ▾");
    View back = awaitText(main, "返回通知分析");
    dashboard = startDashboard(() -> click(back));
    awaitText(dashboard, "App 排行");
    assertEquals(scrollBeforeDrill, scrollAnalysis(null));
  }

  public void testCustomRangeAndEarlierChartPage() throws Exception {
    main = getActivity();
    openDashboard();
    click(awaitText(dashboard, "近 30 天"));
    awaitText(dashboard, today.minusDays(29) + " ～ " + today);
    click(awaitText(dashboard, "自訂日期"));
    AppDialog dialog = activeDialog(dashboard);
    View dialogView = dialog.getWindow().getDecorView();
    LocalDate first = today.minusDays(60), last = today.minusDays(15);
    chooseDate(dialogView.findViewWithTag("analysis_start_date"), first, RecordFilters.MIN_DATE);
    chooseDate(dialogView.findViewWithTag("analysis_end_date"), last, first);
    assertNotNull(find(dialogView, v -> v instanceof TextView && "共 46 天".contentEquals(((TextView) v).getText())));
    click(dialog.getButton(AppDialog.BUTTON_POSITIVE));
    awaitText(dashboard, first + " ～ " + last);
    LocalDate older = today.minusDays(55);
    awaitText(dashboard, older + " · 1 則");
    click(awaitText(dashboard, "前一段"));
    click(await(dashboard, v -> (older + "，1 則").equals(v.getContentDescription()), "earlier date bar"));
    click(await(dashboard, v -> "analysis_daily_records".equals(v.getTag()), "view earlier date"));
    awaitText(main, older + " ～ " + older);
    awaitText(main, "1 則");
  }

  private void chooseDate(View button, LocalDate date, LocalDate minimum) throws Exception {
    assertNotNull(button);
    click(button);
    Field field = AnalysisActivity.class.getDeclaredField("datePicker");
    field.setAccessible(true);
    DatePickerDialog picker = (DatePickerDialog) field.get(dashboard);
    assertNotNull(picker);
    getInstrumentation().runOnMainSync(() -> {
      assertEquals(minimum.atStartOfDay(zone).toInstant().toEpochMilli(), picker.getDatePicker().getMinDate());
      picker.updateDate(date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth());
      picker.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
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
