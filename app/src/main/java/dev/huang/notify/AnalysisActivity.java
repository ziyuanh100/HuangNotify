package dev.huang.notify;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.text.NumberFormat;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Local dashboard with links back to the records that make up each count. */
public final class AnalysisActivity extends Activity {
  static final String FIRST_DATE = "first_date", LAST_DATE = "last_date";
  static final String PACKAGE = "package", APP_NAME = "app_name";
  private static final int TODAY = 0, WEEK = 1, MONTH = 2, CUSTOM = 3;
  private static final int CHART_DAYS = 30;
  private static final String[] PERIODS = {"今天", "近 7 天", "近 30 天", "自訂日期"};
  private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("M/d", Locale.TAIWAN);

  private final NumberFormat integers = NumberFormat.getIntegerInstance(Locale.TAIWAN);
  private final NumberFormat averages = NumberFormat.getNumberInstance(Locale.TAIWAN);
  private final NumberFormat percentages = NumberFormat.getPercentInstance(Locale.TAIWAN);
  private final TextView[] periods = new TextView[PERIODS.length];
  private Ui u;
  private ListView list;
  private TextView range, scope;
  private LinearLayout body;
  private RankAdapter ranking;
  private NotificationAnalysis analysis;
  private CancellationSignal loading;
  private int period = WEEK;
  private String customFirst = "", customLast = "", pkg, appName;
  private LocalDate first, last, chartEnd;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    u = new Ui(this);
    averages.setMaximumFractionDigits(1);
    percentages.setMaximumFractionDigits(1);
    if (state != null) {
      period = state.getInt("period", WEEK);
      customFirst = state.getString(FIRST_DATE, "");
      customLast = state.getString(LAST_DATE, "");
      pkg = state.getString(PACKAGE);
      appName = state.getString(APP_NAME);
      String end = state.getString("chart_end");
      if (end != null) chartEnd = LocalDate.parse(end);
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

    list = new ListView(this);
    list.setTag("analysis_list");
    list.setDivider(null);
    list.setItemsCanFocus(true);
    LinearLayout header = u.column();
    header.setPadding(u.d(16), u.d(4), u.d(16), u.d(12));
    buildPeriodControls(header);
    body = u.column();
    header.addView(body);
    list.addHeaderView(header, null, false);
    ranking = new RankAdapter();
    list.setAdapter(ranking);
    root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(root);
    root.requestApplyInsets();
  }

  @Override protected void onResume() {
    super.onResume();
    loadAnalysis();
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
    state.putInt("period", period);
    state.putString(FIRST_DATE, customFirst);
    state.putString(LAST_DATE, customLast);
    state.putString(PACKAGE, pkg);
    state.putString(APP_NAME, appName);
    if (chartEnd != null) state.putString("chart_end", chartEnd.toString());
  }

  private void buildPeriodControls(LinearLayout header) {
    int columns = getResources().getConfiguration().fontScale >= 1.5f
        || getResources().getConfiguration().screenWidthDp < 320 ? 2 : 4;
    for (int offset = 0; offset < periods.length; offset += columns) {
      LinearLayout row = u.row();
      for (int i = offset; i < offset + columns; i++) {
        int choice = i;
        TextView button = u.button(PERIODS[i], false);
        button.setPadding(u.d(4), u.d(8), u.d(4), u.d(8));
        button.setOnClickListener(v -> {
          if (choice == CUSTOM) chooseDates();
          else if (period != choice) {
            period = choice;
            chartEnd = null;
            loadAnalysis();
          }
        });
        periods[i] = button;
        addColumn(row, button);
      }
      header.addView(row);
      u.gap(header, 6);
    }
    range = u.text("", 13, u.muted, true);
    header.addView(range);
    scope = u.button("", false);
    scope.setOnClickListener(v -> {
      pkg = appName = null;
      loadAnalysis();
    });
    header.addView(scope);
    u.gap(header, 10);
  }

  private void chooseDates() {
    LinearLayout fields = u.column();
    EditText start = u.field("開始日期 YYYY-MM-DD");
    EditText end = u.field("結束日期 YYYY-MM-DD");
    start.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
    end.setInputType(start.getInputType());
    start.setText(customFirst.isEmpty() ? first.toString() : customFirst);
    end.setText(customLast.isEmpty() ? last.toString() : customLast);
    fields.addView(u.text("開始日期", 13, u.muted, false));
    fields.addView(start);
    u.gap(fields, 8);
    fields.addView(u.text("結束日期", 13, u.muted, false));
    fields.addView(end);
    AppDialog dialog = new AppDialog.Builder(this).setTitle("自訂日期")
        .setView(fields).setNegativeButton("取消", null).setPositiveButton("套用", null).create();
    dialog.setOnShowListener(d -> dialog.getButton(AppDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
      String from = start.getText().toString().trim();
      String to = end.getText().toString().trim();
      try {
        if (from.isEmpty() || to.isEmpty()) throw new IllegalArgumentException("請輸入開始與結束日期");
        RecordFilters.bounds(from, to, ZoneId.systemDefault());
      } catch (DateTimeException e) {
        end.setError("日期不存在，請重新輸入");
        return;
      } catch (IllegalArgumentException e) {
        end.setError(e.getMessage());
        return;
      }
      customFirst = from;
      customLast = to;
      period = CUSTOM;
      chartEnd = null;
      dialog.dismiss();
      loadAnalysis();
    }));
    dialog.show();
  }

  private void loadAnalysis() {
    if (loading != null) loading.cancel();
    ZoneId zone = ZoneId.systemDefault();
    last = period == CUSTOM ? LocalDate.parse(customLast) : LocalDate.now(zone);
    first = period == CUSTOM ? LocalDate.parse(customFirst)
        : last.minusDays(period == MONTH ? 29 : period == WEEK ? 6 : 0);
    if (chartEnd == null || chartEnd.isBefore(first) || chartEnd.isAfter(last)) chartEnd = last;
    for (int i = 0; i < periods.length; i++) {
      boolean selected = i == period;
      periods[i].setSelected(selected);
      periods[i].setTextColor(selected ? u.onAccent : u.accent);
      u.ripple(periods[i], selected ? u.accent : u.pale, 16);
    }
    range.setText(dateRange(first, last));
    scope.setVisibility(pkg == null ? View.GONE : View.VISIBLE);
    scope.setText(pkg == null ? "" : appName + " · 顯示全部 App");
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
          render();
          ranking.setApps(apps);
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
          retry.setOnClickListener(v -> loadAnalysis());
          body.addView(retry);
        });
      }
    });
  }

  private boolean isCurrent(CancellationSignal request) {
    return loading == request && !request.isCanceled() && !isFinishing() && !isDestroyed();
  }

  private void render() {
    body.removeAllViews();
    LinearLayout overview = u.column();
    overview.setPadding(u.d(16), u.d(14), u.d(16), u.d(14));
    overview.setBackground(u.shape(u.hero, 22));
    LinearLayout metrics = u.row();
    metrics.setGravity(Gravity.TOP);
    addMetric(metrics, "已保存通知", integers.format(analysis.total()), u.lime);
    addMetric(metrics, "每日平均", averages.format(analysis.dailyAverage()), u.heroInk);
    overview.addView(metrics);
    u.gap(overview, 10);
    LocalDate busiest = analysis.busiestDay();
    String peak = busiest == null ? "—" : busiest + " · " + count(analysis.dailyCount(busiest));
    overview.addView(u.text("通知最多的一天", 13, u.heroMuted, false));
    overview.addView(u.text(peak, 16, u.heroInk, true));
    if (analysis.total() == 0) {
      u.gap(overview, 8);
      overview.addView(u.text("這段期間沒有已保存的通知。", 14, u.heroInk, false));
    }
    body.addView(overview);
    u.gap(body, 12);

    LinearLayout daily = card();
    renderDaily(daily);
    body.addView(daily);
    u.gap(body, 12);

    LinearLayout hourly = card();
    addHeading(hourly, "時段分布");
    hourly.addView(u.text("左右滑動查看 00–23 時", 13, u.muted, false));
    LinearLayout hours = chart(hourly);
    for (int hour = 0; hour < 24; hour++) {
      String label = String.format(Locale.TAIWAN, "%02d 時", hour);
      hours.addView(bar(label, analysis.hourlyCount(hour), analysis.busiestHourCount(), 54));
    }
    body.addView(hourly);
    if (pkg == null) {
      u.gap(body, 16);
      addHeading(body, "App 排行");
      if (analysis.total() == 0) {
        body.addView(u.text("這段期間沒有 App 資料。", 14, u.muted, false));
      }
    }
  }

  private void renderDaily(LinearLayout daily) {
    daily.removeAllViews();
    addHeading(daily, "每日趨勢");
    LocalDate start = chartEnd.minusDays(CHART_DAYS - 1);
    if (start.isBefore(first)) start = first;
    daily.addView(u.text(dateRange(start, chartEnd), 13, u.muted, false));
    String hint = chartEnd.isAfter(start.plusDays(6))
        ? "左右滑動，點選日期查看通知" : "點選日期查看通知";
    daily.addView(u.text(hint, 13, u.muted, false));
    LinearLayout days = chart(daily);
    long max = analysis.dailyCount(analysis.busiestDay());
    for (LocalDate day = start; !day.isAfter(chartEnd); day = day.plusDays(1)) {
      LocalDate date = day;
      long value = analysis.dailyCount(date);
      LinearLayout bar = bar(SHORT_DATE.format(date), value, max, 82);
      u.ripple(bar, u.surface, 10);
      bar.setTag(date.toString());
      bar.setContentDescription(date + "，" + count(value) + "，查看通知");
      bar.setFocusable(true);
      bar.setOnClickListener(v -> showRecords(date, date, pkg, appName));
      days.addView(bar, new LinearLayout.LayoutParams(-2, -2, 1));
    }
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

  private LinearLayout chart(LinearLayout card) {
    HorizontalScrollView scroll = new HorizontalScrollView(this);
    LinearLayout bars = u.row();
    bars.setGravity(Gravity.BOTTOM);
    scroll.setFillViewport(true);
    scroll.addView(bars);
    card.addView(scroll);
    return bars;
  }

  private LinearLayout bar(String label, long value, long max, int height) {
    LinearLayout item = u.column();
    item.setGravity(Gravity.CENTER_HORIZONTAL);
    item.setPadding(u.d(5), u.d(5), u.d(5), u.d(8));
    item.setMinimumWidth(u.d(48));
    TextView number = u.text(integers.format(value), 12, u.ink, true);
    TextView caption = u.text(label, 12, u.muted, false);
    number.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    item.setContentDescription(label + "，" + count(value));
    item.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    item.addView(number);
    FrameLayout track = new FrameLayout(this);
    track.setBackground(u.shape(u.pale, 5));
    LinearLayout.LayoutParams trackSize = new LinearLayout.LayoutParams(u.d(18), u.d(height));
    trackSize.topMargin = trackSize.bottomMargin = u.d(4);
    item.addView(track, trackSize);
    if (value > 0) {
      View fill = new View(this);
      fill.setBackground(u.shape(u.accent, 5));
      int fillHeight = Math.max(u.d(2), (int) Math.round(u.d(height) * (value / (double) max)));
      track.addView(fill, new FrameLayout.LayoutParams(-1, fillHeight, Gravity.BOTTOM));
    }
    item.addView(caption);
    return item;
  }

  private void showRecords(LocalDate from, LocalDate to, String packageName, String label) {
    if (analysis == null) return;
    if (!analysis.zone.equals(ZoneId.systemDefault())) {
      loadAnalysis();
      return;
    }
    setResult(RESULT_OK, new Intent()
        .putExtra(FIRST_DATE, from.toString()).putExtra(LAST_DATE, to.toString())
        .putExtra(PACKAGE, packageName).putExtra(APP_NAME, label));
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
      RankRow row = convertView instanceof RankRow ? (RankRow) convertView : new RankRow();
      row.bind(getItem(position));
      return row;
    }
  }

  private final class RankRow extends LinearLayout {
    private final LinearLayout details;
    private final ImageView icon;
    private final TextView name, amount, trend;

    RankRow() {
      super(AnalysisActivity.this);
      setPadding(u.d(16), 0, u.d(16), u.d(8));
      LinearLayout card = u.row();
      card.setPadding(u.d(6), u.d(4), u.d(6), u.d(4));
      card.setBackground(u.outline(u.surface, 16));
      addView(card, new LinearLayout.LayoutParams(-1, -2));
      details = u.row();
      details.setMinHeight(u.d(48));
      details.setPadding(u.d(6), u.d(5), u.d(6), u.d(5));
      details.setFocusable(true);
      u.ripple(details, u.surface, 12);
      icon = new ImageView(AnalysisActivity.this);
      icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      details.addView(icon, new LinearLayout.LayoutParams(u.d(30), u.d(30)));
      LinearLayout text = u.column();
      text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
      text.setPadding(u.d(8), 0, 0, 0);
      name = u.text("", 15, u.ink, true);
      amount = u.text("", 13, u.muted, false);
      text.addView(name);
      text.addView(amount);
      details.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
      card.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
      trend = u.button("看趨勢", false);
      trend.setPadding(u.d(10), u.d(8), u.d(10), u.d(8));
      card.addView(trend);
    }

    void bind(NotificationAnalysis.AppCount app) {
      icon.setImageDrawable(u.appIcon(app.pkg));
      name.setText(app.name());
      String share = percentages.format(app.count() / (double) analysis.total());
      String value = count(app.count()) + " · " + share;
      amount.setText(value);
      details.setContentDescription(app.name() + "，" + value + "，查看通知");
      details.setTag(app.pkg);
      details.setOnClickListener(v -> showRecords(first, last, app.pkg, app.name()));
      trend.setContentDescription("查看 " + app.name() + " 的趨勢");
      trend.setOnClickListener(v -> {
        pkg = app.pkg;
        appName = app.name();
        loadAnalysis();
      });
    }
  }
}
