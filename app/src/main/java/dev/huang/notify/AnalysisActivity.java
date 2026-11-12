package dev.huang.notify;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.DatePicker;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntConsumer;

/** Local dashboard with links back to the records that make up each count. */
public final class AnalysisActivity extends Activity {
  static final String FIRST_DATE = "first_date", LAST_DATE = "last_date";
  static final String PACKAGE = "package", APP_NAME = "app_name";
  static final String DASHBOARD_STATE = "dashboard_state";
  private static final int TODAY = 0, WEEK = 1, MONTH = 2, CUSTOM = 3;
  private static final int CHART_DAYS = 30;
  private static final int TOP_APPS = 3;
  private static final String[] PERIODS = {"今天", "近 7 天", "近 30 天", "自訂日期"};
  private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("M/d", Locale.TAIWAN);

  private final NumberFormat integers = NumberFormat.getIntegerInstance(Locale.TAIWAN);
  private final NumberFormat averages = NumberFormat.getNumberInstance(Locale.TAIWAN);
  private final NumberFormat percentages = NumberFormat.getPercentInstance(Locale.TAIWAN);
  private Ui u;
  private ListView list;
  private TextView periodButton, range, scope;
  private LinearLayout body;
  private RankAdapter ranking;
  private NotificationAnalysis analysis;
  private CancellationSignal loading;
  private int period = WEEK;
  private int scrollPosition, scrollTop, selectedHour = -1;
  private String customFirst = "", customLast = "", pkg, appName;
  private LocalDate first, last, chartEnd, selectedDay;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    u = new Ui(this);
    averages.setMaximumFractionDigits(1);
    percentages.setMaximumFractionDigits(1);
    if (state == null) state = getIntent().getBundleExtra(DASHBOARD_STATE);
    if (state != null) {
      period = state.getInt("period", WEEK);
      customFirst = state.getString(FIRST_DATE, "");
      customLast = state.getString(LAST_DATE, "");
      pkg = state.getString(PACKAGE);
      appName = state.getString(APP_NAME);
      String end = state.getString("chart_end");
      if (end != null) chartEnd = LocalDate.parse(end);
      String day = state.getString("selected_day");
      if (day != null) selectedDay = LocalDate.parse(day);
      selectedHour = state.getInt("selected_hour", -1);
      scrollPosition = state.getInt("scroll_position");
      scrollTop = state.getInt("scroll_top");
    }

    LinearLayout root = u.column();
    root.setBackgroundColor(u.bg);
    getWindow().setStatusBarColor(u.bg);
    getWindow().setNavigationBarColor(u.bg);
    getWindow().getDecorView().setSystemUiVisibility(u.dark ? 0
        : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
    root.setOnApplyWindowInsetsListener((view, insets) -> {
      if (Build.VERSION.SDK_INT >= 30) {
        Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
        return WindowInsets.CONSUMED;
      }
      view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
          insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
      return insets.consumeSystemWindowInsets();
    });

    LinearLayout top = u.row();
    top.setPadding(u.d(16), u.d(8), u.d(16), u.d(8));
    TextView back = u.button("返回", false);
    back.setOnClickListener(v -> finish());
    top.addView(back);
    TextView title = u.text("通知分析", 24, u.ink, true);
    title.setPadding(u.d(14), 0, 0, 0);
    u.heading(title);
    top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(top);
    buildPeriodControls(root);

    list = new ListView(this);
    list.setTag("analysis_list");
    list.setDivider(null);
    list.setSelector(new ColorDrawable(Color.TRANSPARENT));
    list.setItemsCanFocus(true);
    LinearLayout header = u.column();
    header.setPadding(u.d(16), u.d(4), u.d(16), u.d(12));
    body = u.column();
    header.addView(body);
    list.addHeaderView(header, null, true);
    ranking = new RankAdapter();
    list.setAdapter(ranking);
    root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(root);
    root.requestApplyInsets();
  }

  @Override protected void onResume() {
    super.onResume();
    loadAnalysis(true);
  }

  @Override protected void onStop() {
    if (loading != null) loading.cancel();
    super.onStop();
  }

  @Override protected void onDestroy() {
    AppDialog.dismissAll(this);
    super.onDestroy();
  }

  @Override protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    saveDashboard(state);
  }

  private void saveDashboard(Bundle state) {
    rememberScroll();
    state.putInt("period", period);
    state.putString(FIRST_DATE, customFirst);
    state.putString(LAST_DATE, customLast);
    state.putString(PACKAGE, pkg);
    state.putString(APP_NAME, appName);
    if (chartEnd != null) state.putString("chart_end", chartEnd.toString());
    if (selectedDay != null) state.putString("selected_day", selectedDay.toString());
    state.putInt("selected_hour", selectedHour);
    state.putInt("scroll_position", scrollPosition);
    state.putInt("scroll_top", scrollTop);
  }

  private void rememberScroll() {
    if (analysis == null || list.getChildCount() == 0) return;
    scrollPosition = list.getFirstVisiblePosition();
    scrollTop = list.getChildAt(0).getTop();
  }

  private void buildPeriodControls(LinearLayout root) {
    LinearLayout controls = u.column();
    controls.setPadding(u.d(16), 0, u.d(16), u.d(8));
    LinearLayout row = u.row();
    periodButton = u.button("", false);
    periodButton.setOnClickListener(v -> choosePeriod());
    scope = u.button("", false);
    scope.setOnClickListener(v -> new AnalysisAppPicker(this, u, pkg, this::selectApp).show());
    for (TextView button : new TextView[]{periodButton, scope}) {
      button.setMaxLines(2);
      button.setEllipsize(TextUtils.TruncateAt.END);
      button.setPadding(u.d(10), u.d(7), u.d(10), u.d(7));
      addColumn(row, button);
    }
    controls.addView(row);
    u.gap(controls, 6);
    range = u.text("", 13, u.muted, true);
    controls.addView(range);
    root.addView(controls);
  }

  private void choosePeriod() {
    new AppDialog.Builder(this).setTitle("日期範圍")
        .setSingleChoiceItems(PERIODS, period, (dialog, choice) -> {
          dialog.dismiss();
          if (choice == CUSTOM) chooseDates();
          else if (period != choice) selectPeriod(choice);
        }).setNegativeButton("取消", null).show();
  }

  private void chooseDates() {
    LinearLayout fields = u.column();
    LocalDate[] dates = {customFirst.isEmpty() ? first : LocalDate.parse(customFirst),
        customLast.isEmpty() ? last : LocalDate.parse(customLast)};
    TextView start = u.button("", false), end = u.button("", false);
    start.setTag("analysis_start_date");
    end.setTag("analysis_end_date");
    int[] edge = {0};
    boolean[] switching = {false};
    ZoneId zone = ZoneId.systemDefault();
    DatePicker calendar = new DatePicker(this);
    calendar.setTag("analysis_calendar");
    calendar.setMinDate(RecordFilters.MIN_DATE.atStartOfDay(zone).toInstant().toEpochMilli());
    calendar.setMaxDate(RecordFilters.MAX_DATE.atStartOfDay(zone).toInstant().toEpochMilli());
    TextView span = u.text("", 14, u.muted, true);
    Runnable update = () -> {
      start.setText("開始日期\n" + dates[0]);
      start.setContentDescription("選擇開始日期，" + dates[0]);
      end.setText("結束日期\n" + dates[1]);
      end.setContentDescription("選擇結束日期，" + dates[1]);
      start.setSelected(edge[0] == 0);
      end.setSelected(edge[0] == 1);
      start.setTextColor(edge[0] == 0 ? u.onAccent : u.accent);
      end.setTextColor(edge[0] == 1 ? u.onAccent : u.accent);
      u.ripple(start, edge[0] == 0 ? u.accent : u.pale, 16);
      u.ripple(end, edge[0] == 1 ? u.accent : u.pale, 16);
      span.setText("共 " + integers.format(ChronoUnit.DAYS.between(dates[0], dates[1]) + 1) + " 天");
    };
    calendar.init(dates[0].getYear(), dates[0].getMonthValue() - 1, dates[0].getDayOfMonth(),
        (view, year, month, day) -> {
          if (switching[0]) return;
          LocalDate date = LocalDate.of(year, month + 1, day);
          dates[edge[0]] = date;
          if (dates[1].isBefore(dates[0])) dates[1] = dates[0];
          update.run();
        });
    IntConsumer activate = index -> {
      edge[0] = index;
      switching[0] = true;
      LocalDate minimum = index == 0 ? RecordFilters.MIN_DATE : dates[0];
      calendar.setMinDate(minimum.atStartOfDay(zone).toInstant().toEpochMilli());
      LocalDate date = dates[index];
      calendar.updateDate(date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth());
      switching[0] = false;
      update.run();
    };
    start.setOnClickListener(v -> activate.accept(0));
    end.setOnClickListener(v -> activate.accept(1));
    LinearLayout edges = u.row();
    addColumn(edges, start);
    addColumn(edges, end);
    fields.addView(edges);
    u.gap(fields, 6);
    fields.addView(span);
    fields.addView(calendar, new LinearLayout.LayoutParams(-1, -2));
    update.run();
    new AppDialog.Builder(this).setTitle("自訂日期").setView(fields).setNegativeButton("取消", null)
        .setPositiveButton("套用", (dialog, which) -> {
          customFirst = dates[0].toString();
          customLast = dates[1].toString();
          selectPeriod(CUSTOM);
        }).show();
  }

  private void selectApp(String packageName, String name) {
    if (Objects.equals(pkg, packageName)) return;
    pkg = packageName;
    appName = name;
    loadAnalysis(false);
  }

  private void selectPeriod(int choice) {
    period = choice;
    selectedDay = chartEnd = null;
    selectedHour = -1;
    loadAnalysis(false);
  }

  private void loadAnalysis(boolean keepPosition) {
    if (keepPosition) rememberScroll();
    else scrollPosition = scrollTop = 0;
    if (loading != null) loading.cancel();
    ZoneId zone = ZoneId.systemDefault();
    last = period == CUSTOM ? LocalDate.parse(customLast) : LocalDate.now(zone);
    first = period == CUSTOM ? LocalDate.parse(customFirst)
        : last.minusDays(period == MONTH ? 29 : period == WEEK ? 6 : 0);
    if (chartEnd == null || chartEnd.isBefore(first) || chartEnd.isAfter(last)) chartEnd = last;
    periodButton.setText(PERIODS[period] + " ▾");
    periodButton.setContentDescription("日期範圍，" + PERIODS[period]);
    range.setText(dateRange(first, last));
    String scopeName = pkg == null ? "全部 App" : appName;
    scope.setText(scopeName + " ▾");
    scope.setContentDescription("依 App 篩選，" + scopeName);
    scope.setSelected(pkg != null);
    scope.setTextColor(pkg == null ? u.accent : u.onAccent);
    u.ripple(scope, pkg == null ? u.pale : u.accent, 16);
    analysis = null;
    ranking.setApps(Collections.emptyList());
    body.removeAllViews();
    body.addView(u.text("正在統計通知…", 15, u.muted, false));
    list.setSelection(0);

    LocalDate requestedFirst = first, requestedLast = last;
    String requestedPkg = pkg;
    CancellationSignal request = new CancellationSignal();
    loading = request;
    Store.IO.execute(() -> {
      try {
        NotificationAnalysis result = Store.get(this)
            .analyze(requestedFirst, requestedLast, zone, requestedPkg, request);
        request.throwIfCanceled();
        List<NotificationAnalysis.AppCount> apps = requestedPkg == null
            ? result.ranking() : Collections.emptyList();
        runOnUiThread(() -> {
          if (!isCurrent(request)) return;
          analysis = result;
          render(apps);
          list.setSelectionFromTop(Math.min(scrollPosition, list.getCount() - 1), scrollTop);
        });
      } catch (OperationCanceledException ignored) {
        // The period or App changed, or the dashboard left the foreground.
      } catch (RuntimeException e) {
        runOnUiThread(() -> {
          if (!isCurrent(request)) return;
          body.removeAllViews();
          body.addView(u.text("無法讀取通知分析", 15, u.ink, true));
          u.gap(body, 8);
          TextView retry = u.button("重試", false);
          retry.setOnClickListener(v -> loadAnalysis(true));
          body.addView(retry);
        });
      }
    });
  }

  private boolean isCurrent(CancellationSignal request) {
    return loading == request && !request.isCanceled() && !isFinishing() && !isDestroyed();
  }

  private void render(List<NotificationAnalysis.AppCount> apps) {
    body.removeAllViews();
    LinearLayout overview = u.column();
    overview.setPadding(u.d(16), u.d(14), u.d(16), u.d(14));
    overview.setBackground(u.shape(u.hero, 22));
    LinearLayout metrics = u.row();
    metrics.setGravity(Gravity.TOP);
    addMetric(metrics, "已保存通知", integers.format(analysis.total()), u.lime);
    addMetric(metrics, "每日平均", averages.format(analysis.dailyAverage()), u.heroInk);
    overview.addView(metrics);
    LocalDate busiest = analysis.busiestDay();
    if (busiest != null) {
      u.gap(overview, 10);
      overview.addView(u.text("通知最多的一天", 13, u.heroMuted, false));
      overview.addView(u.text(busiest + " · " + count(analysis.dailyCount(busiest)), 16, u.heroInk, true));
    }
    body.addView(overview);
    u.gap(body, 12);
    if (analysis.total() == 0) {
      renderEmpty();
      return;
    }

    int shown = Math.min(TOP_APPS, apps.size());
    if (shown > 0) {
      LinearLayout preview = card();
      addHeading(preview, "App 排行");
      for (int i = 0; i < shown; i++) {
        RankRow row = new RankRow(true);
        row.bind(apps.get(i));
        preview.addView(row);
      }
      if (shown < apps.size()) {
        TextView more = u.button("查看其餘 " + integers.format(apps.size() - shown) + " 個 App", false);
        more.setOnClickListener(v -> list.setSelectionFromTop(list.getHeaderViewsCount(), 0));
        preview.addView(more);
      }
      body.addView(preview);
      u.gap(body, 12);
    }

    LinearLayout daily = card();
    renderDaily(daily);
    body.addView(daily);
    u.gap(body, 12);
    renderHourly();

    if (shown < apps.size()) {
      u.gap(body, 16);
      addHeading(body, "其餘 App");
      ranking.setApps(apps.subList(shown, apps.size()));
    }
  }

  private void renderEmpty() {
    LinearLayout empty = card();
    addHeading(empty, "這段期間沒有已保存的通知");
    u.gap(empty, 10);
    if (period != MONTH) {
      TextView month = u.button("查看近 30 天", true);
      month.setOnClickListener(v -> selectPeriod(MONTH));
      empty.addView(month);
      u.gap(empty, 6);
    }
    TextView dates = u.button("變更日期", false);
    dates.setOnClickListener(v -> chooseDates());
    empty.addView(dates);
    body.addView(empty);
  }

  private void renderHourly() {
    LinearLayout hourly = card();
    addHeading(hourly, "時段分布");
    long[] values = new long[24];
    String[] labels = new String[24], descriptions = new String[24];
    int peak = 0;
    for (int hour = 0; hour < values.length; hour++) {
      values[hour] = analysis.hourlyCount(hour);
      if (values[hour] > values[peak]) peak = hour;
      labels[hour] = String.format(Locale.TAIWAN, "%02d", hour);
      descriptions[hour] = hourRange(hour) + "，" + count(values[hour]);
    }
    hourly.addView(u.text("通知最多 " + hourRange(peak), 13, u.muted, false));
    u.gap(hourly, 8);
    TextView detail = u.text("", 14, u.ink, true);
    detail.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    int[] ticks = compactControls() ? new int[]{0, 12, 23} : new int[]{0, 6, 12, 18, 23};
    AnalysisBars hours = new AnalysisBars(u, values, analysis.busiestHourCount(), 72,
        labels, descriptions, ticks, hour -> {
          selectedHour = hour;
          detail.setText(hourRange(hour) + " · " + count(values[hour]));
        });
    hours.setTag("analysis_hourly_chart");
    if (selectedHour < 0) selectedHour = peak;
    hours.select(selectedHour);
    detail.setText(hourRange(selectedHour) + " · " + count(values[selectedHour]));
    hourly.addView(hours);
    u.gap(hourly, 6);
    hourly.addView(detail);
    body.addView(hourly);
  }

  private void renderDaily(LinearLayout daily) {
    daily.removeAllViews();
    addHeading(daily, "每日趨勢");
    LocalDate earliest = chartEnd.minusDays(CHART_DAYS - 1);
    LocalDate start = earliest.isBefore(first) ? first : earliest;
    daily.addView(u.text(dateRange(start, chartEnd), 13, u.muted, false));
    u.gap(daily, 8);
    int length = (int) ChronoUnit.DAYS.between(start, chartEnd) + 1;
    long[] values = new long[length];
    String[] labels = new String[length], descriptions = new String[length];
    int peak = 0;
    for (int i = 0; i < length; i++) {
      LocalDate date = start.plusDays(i);
      values[i] = analysis.dailyCount(date);
      if (values[i] > values[peak]) peak = i;
      labels[i] = SHORT_DATE.format(date);
      descriptions[i] = date + "，" + count(values[i]);
    }
    int selected = selectedDay == null || selectedDay.isBefore(start) || selectedDay.isAfter(chartEnd)
        ? peak : (int) ChronoUnit.DAYS.between(start, selectedDay);
    LinearLayout detailRow = u.row();
    TextView detail = u.text("", 14, u.ink, true);
    detail.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    detailRow.addView(detail, new LinearLayout.LayoutParams(0, -2, 1));
    TextView records = u.button("查看紀錄", false);
    records.setTag("analysis_daily_records");
    detailRow.addView(records);
    records.setOnClickListener(v -> showRecords(selectedDay, selectedDay, pkg, appName));
    IntConsumer update = index -> {
      selectedDay = start.plusDays(index);
      detail.setText(selectedDay + " · " + count(values[index]));
      records.setContentDescription("查看 " + selectedDay + " 的通知紀錄");
      records.setEnabled(values[index] > 0);
    };
    int[] ticks;
    if ((length <= 7 && !compactControls()) || length <= 2) {
      ticks = new int[length];
      for (int i = 0; i < length; i++) ticks[i] = i;
    } else {
      ticks = new int[]{0, (length - 1) / 2, length - 1};
    }
    AnalysisBars days = new AnalysisBars(u, values, analysis.dailyCount(analysis.busiestDay()),
        92, labels, descriptions, ticks, update);
    days.setTag("analysis_daily_chart");
    days.select(selected);
    update.accept(selected);
    daily.addView(days);
    u.gap(daily, 8);
    daily.addView(detailRow);

    if (start.isAfter(first) || chartEnd.isBefore(last)) {
      LocalDate previousEnd = start.minusDays(1);
      LinearLayout actions = u.row();
      TextView previous = u.button("前一段", false);
      previous.setEnabled(!previousEnd.isBefore(first));
      previous.setOnClickListener(v -> {
        chartEnd = previousEnd;
        renderDaily(daily);
      });
      TextView next = u.button("下一段", false);
      next.setEnabled(chartEnd.isBefore(last));
      next.setOnClickListener(v -> {
        LocalDate nextEnd = chartEnd.plusDays(CHART_DAYS);
        chartEnd = nextEnd.isAfter(last) ? last : nextEnd;
        renderDaily(daily);
      });
      addColumn(actions, previous);
      addColumn(actions, next);
      daily.addView(actions);
    }
  }

  private static String hourRange(int hour) {
    return String.format(Locale.TAIWAN, "%02d:00–%02d:59", hour, hour);
  }

  private boolean compactControls() {
    return getResources().getConfiguration().fontScale >= 1.5f
        || getResources().getConfiguration().screenWidthDp < 360;
  }

  private void showRecords(LocalDate from, LocalDate to, String packageName, String label) {
    if (analysis == null) return;
    if (!analysis.zone.equals(ZoneId.systemDefault())) {
      loadAnalysis(true);
      return;
    }
    Bundle state = new Bundle();
    saveDashboard(state);
    setResult(RESULT_OK, new Intent()
        .putExtra(FIRST_DATE, from.toString()).putExtra(LAST_DATE, to.toString())
        .putExtra(PACKAGE, packageName).putExtra(APP_NAME, label).putExtra(DASHBOARD_STATE, state));
    finish();
  }

  private LinearLayout card() {
    LinearLayout card = u.column();
    card.setPadding(u.d(12), u.d(12), u.d(12), u.d(10));
    card.setBackground(u.outline(u.surface, 20));
    return card;
  }

  private void addHeading(LinearLayout parent, String title) {
    TextView heading = u.text(title, 18, u.ink, true);
    u.heading(heading);
    parent.addView(heading);
  }

  private void addMetric(LinearLayout row, String label, String value, int color) {
    LinearLayout metric = u.column();
    metric.addView(u.text(label, 13, u.heroMuted, false));
    metric.addView(u.text(value, 25, color, true));
    addColumn(row, metric);
  }

  private void addColumn(LinearLayout row, View child) {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
    if (row.getChildCount() > 0) params.setMarginStart(u.d(6));
    row.addView(child, params);
  }

  private String count(long value) { return integers.format(value) + " 則"; }

  private static String dateRange(LocalDate start, LocalDate end) {
    return start.equals(end) ? start.toString() : start + " ～ " + end;
  }

  private final class RankAdapter extends BaseAdapter {
    private List<NotificationAnalysis.AppCount> apps = Collections.emptyList();

    void setApps(List<NotificationAnalysis.AppCount> apps) {
      this.apps = apps;
      notifyDataSetChanged();
    }

    @Override public int getCount() { return apps.size(); }
    @Override public NotificationAnalysis.AppCount getItem(int position) { return apps.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
      RankRow row = convertView instanceof RankRow ? (RankRow) convertView : new RankRow(false);
      row.bind(getItem(position));
      return row;
    }
  }

  private final class RankRow extends LinearLayout {
    private final ImageView icon;
    private final TextView name, amount, trend, records;

    RankRow(boolean preview) {
      super(AnalysisActivity.this);
      boolean stacked = compactControls();
      setPadding(u.d(preview ? 0 : 16), 0, u.d(preview ? 0 : 16), u.d(6));
      LinearLayout tile = stacked ? u.column() : u.row();
      tile.setPadding(u.d(6), u.d(4), u.d(6), u.d(4));
      if (!preview) tile.setBackground(u.outline(u.surface, 16));
      addView(tile, new LinearLayout.LayoutParams(-1, -2));
      LinearLayout details = u.row();
      icon = new ImageView(AnalysisActivity.this);
      icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      details.addView(icon, new LinearLayout.LayoutParams(u.d(30), u.d(30)));
      LinearLayout text = u.column();
      text.setPadding(u.d(8), 0, 0, 0);
      name = u.text("", 15, u.ink, true);
      amount = u.text("", 13, u.muted, false);
      text.addView(name);
      text.addView(amount);
      details.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
      tile.addView(details, stacked ? new LinearLayout.LayoutParams(-1, -2)
          : new LinearLayout.LayoutParams(0, -2, 1));

      LinearLayout actions = u.row();
      trend = u.button("看趨勢", false);
      records = u.button("查看紀錄", false);
      for (TextView action : new TextView[]{trend, records}) {
        action.setTextSize(13);
        action.setPadding(u.d(7), u.d(6), u.d(7), u.d(6));
        action.setMinHeight(u.d(48));
        if (stacked) addColumn(actions, action);
        else {
          LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
          params.setMarginStart(u.d(4));
          actions.addView(action, params);
        }
      }
      if (stacked) u.gap(tile, 6);
      tile.addView(actions, new LinearLayout.LayoutParams(stacked ? -1 : -2, -2));
    }

    void bind(NotificationAnalysis.AppCount app) {
      icon.setImageDrawable(u.appIcon(app.pkg));
      name.setText(app.name());
      String share = percentages.format(app.count() / (double) analysis.total());
      String value = count(app.count()) + " · " + share;
      amount.setText(value);
      records.setContentDescription("查看 " + app.name() + " 的紀錄");
      records.setTag(app.pkg);
      records.setOnClickListener(v -> showRecords(first, last, app.pkg, app.name()));
      trend.setContentDescription("查看 " + app.name() + " 的趨勢");
      trend.setOnClickListener(v -> selectApp(app.pkg, app.name()));
    }
  }
}
