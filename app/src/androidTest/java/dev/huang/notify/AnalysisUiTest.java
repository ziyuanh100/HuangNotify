package dev.huang.notify;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
    capture("analysis-overview.png");
    click(awaitText(dashboard, "今天"));
    awaitText(dashboard, today + " · 2 則");
    click(awaitText(dashboard, "近 7 天"));
    awaitText(dashboard, yesterday + " · 84 則");
    showRanking();
    awaitText(dashboard, "84 則 · 97.7%");
    capture("analysis-app-ranking.png");
    click(await(dashboard, v -> "查看 甲 App 的趨勢".equals(v.getContentDescription()), "App trend"));
    awaitText(dashboard, "甲 App · 顯示全部 App");
    dashboard = startDashboard(dashboard::recreate);
    awaitText(dashboard, "甲 App · 顯示全部 App");
    click(await(dashboard, v -> yesterday.toString().equals(v.getTag()), "yesterday bar"));
    awaitText(main, yesterday + " ～ " + yesterday);
    awaitText(main, "84 則");

    openDashboard();
    awaitText(dashboard, yesterday + " · 84 則");
    showRanking();
    click(await(dashboard, v -> ALPHA.equals(v.getTag()), "App records"));
    awaitText(main, today.minusDays(6) + " ～ " + today);
    awaitText(main, "84 則");
    awaitText(main, "甲 App ▾");
  }

  public void testCustomRangeAndEarlierChartPage() throws Exception {
    main = getActivity();
    openDashboard();
    click(awaitText(dashboard, "近 30 天"));
    awaitText(dashboard, today.minusDays(29) + " ～ " + today);
    click(awaitText(dashboard, "自訂日期"));
    AppDialog dialog = activeDialog(dashboard);
    View dialogView = dialog.getWindow().getDecorView();
    EditText start = (EditText) find(dialogView, v -> v instanceof EditText
        && "開始日期 YYYY-MM-DD".contentEquals(((EditText) v).getHint()));
    EditText end = (EditText) find(dialogView, v -> v instanceof EditText
        && "結束日期 YYYY-MM-DD".contentEquals(((EditText) v).getHint()));
    assertNotNull(start);
    assertNotNull(end);
    LocalDate first = today.minusDays(60), last = today.minusDays(15);
    getInstrumentation().runOnMainSync(() -> {
      start.setText(first.toString());
      end.setText(first.minusDays(1).toString());
      dialog.getButton(AppDialog.BUTTON_POSITIVE).performClick();
      assertNotNull(end.getError());
      end.setText(last.toString());
      dialog.getButton(AppDialog.BUTTON_POSITIVE).performClick();
    });
    awaitText(dashboard, first + " ～ " + last);
    LocalDate older = today.minusDays(55);
    awaitText(dashboard, older + " · 1 則");
    click(awaitText(dashboard, "前一段"));
    click(await(dashboard, v -> older.toString().equals(v.getTag()), "earlier date bar"));
    awaitText(main, older + " ～ " + older);
    awaitText(main, "1 則");
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
    dashboard = startDashboard(entry::performClick);
  }

  private AnalysisActivity startDashboard(Runnable action) {
    Instrumentation instrumentation = getInstrumentation();
    Instrumentation.ActivityMonitor monitor =
        instrumentation.addMonitor(AnalysisActivity.class.getName(), null, false);
    try {
      instrumentation.runOnMainSync(action);
      AnalysisActivity next =
          (AnalysisActivity) instrumentation.waitForMonitorWithTimeout(monitor, 10000);
      assertNotNull(next);
      return next;
    } finally {
      instrumentation.removeMonitor(monitor);
    }
  }

  private void showRanking() throws Exception {
    ListView list = (ListView) await(dashboard, v -> "analysis_list".equals(v.getTag()), "dashboard");
    getInstrumentation().runOnMainSync(() -> list.setSelectionFromTop(list.getHeaderViewsCount(), 0));
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
