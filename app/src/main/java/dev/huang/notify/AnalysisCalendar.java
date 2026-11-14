package dev.huang.notify;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** An inclusive date range edited on one compact calendar. */
final class AnalysisCalendar extends LinearLayout {
  private static final Locale LOCALE = Locale.TAIWAN;
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("uuuu 年 M 月", LOCALE);
  private static final String[] WEEKDAYS = {"日", "一", "二", "三", "四", "五", "六"};
  private final Ui u;
  private final TextView start, end, length, monthLabel, previous, next;
  private final TextView[] days = new TextView[42];
  private LocalDate first, last;
  private YearMonth month;
  private boolean selectingEnd;

  AnalysisCalendar(Ui u, LocalDate first, LocalDate last) {
    super(u.context);
    this.u = u;
    this.first = first;
    this.last = last;
    month = YearMonth.from(first);
    setOrientation(VERTICAL);

    LinearLayout edges = u.row();
    start = edge(edges, "analysis_start_date", false);
    end = edge(edges, "analysis_end_date", true);
    addView(edges);
    length = u.text("", 13, u.muted, true);
    length.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    length.setPadding(0, u.d(6), 0, 0);
    addView(length);

    LinearLayout navigation = u.row();
    previous = monthArrow(navigation, "‹", "上個月", -1);
    monthLabel = u.button("", false);
    monthLabel.setTextSize(14);
    monthLabel.setPadding(u.d(6), u.d(6), u.d(6), u.d(6));
    monthLabel.setOnClickListener(v -> chooseMonth());
    navigation.addView(monthLabel, new LayoutParams(0, -2, 1));
    next = monthArrow(navigation, "›", "下個月", 1);
    addView(navigation);

    LinearLayout weekdays = u.row();
    weekdays.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    for (String weekday : WEEKDAYS) {
      TextView label = u.text(weekday, 12, u.muted, true);
      label.setGravity(Gravity.CENTER);
      weekdays.addView(label, new LayoutParams(0, -2, 1));
    }
    addView(weekdays);
    for (int week = 0; week < 6; week++) {
      LinearLayout row = u.row();
      for (int weekday = 0; weekday < 7; weekday++) {
        TextView day = u.button("", false);
        day.setTextSize(14);
        day.setPadding(0, 0, 0, 0);
        day.setMinimumHeight(0);
        day.setMinHeight(u.d(38));
        days[week * 7 + weekday] = day;
        row.addView(day, new LayoutParams(0, -2, 1));
      }
      addView(row);
    }
    render();
  }

  LocalDate first() { return first; }
  LocalDate last() { return last; }

  private TextView edge(LinearLayout row, String tag, boolean isEnd) {
    TextView button = u.button("", false);
    button.setTag(tag);
    button.setTextSize(13);
    button.setPadding(u.d(3), u.d(6), u.d(3), u.d(6));
    button.setOnClickListener(v -> {
      selectingEnd = isEnd;
      month = YearMonth.from(isEnd ? last : first);
      render();
    });
    LayoutParams params = new LayoutParams(0, -2, 1);
    if (isEnd) params.setMarginStart(u.d(6));
    row.addView(button, params);
    return button;
  }

  private TextView monthArrow(LinearLayout row, String glyph, String label, int delta) {
    TextView arrow = u.button(glyph, false);
    arrow.setTextSize(24);
    arrow.setPadding(0, 0, 0, 0);
    arrow.setContentDescription(label);
    arrow.setOnClickListener(v -> {
      month = month.plusMonths(delta);
      render();
    });
    row.addView(arrow, new LayoutParams(u.d(48), -2));
    return arrow;
  }

  private void render() {
    updateEdge(start, "開始日期", first, !selectingEnd);
    updateEdge(end, "結束日期", last, selectingEnd);
    length.setText(String.format(LOCALE, "共 %,d 天", ChronoUnit.DAYS.between(first, last) + 1));
    monthLabel.setText(MONTH.format(month) + " ▾");
    monthLabel.setContentDescription("跳到年月，" + MONTH.format(month));
    previous.setEnabled(month.isAfter(YearMonth.from(RecordFilters.MIN_DATE)));
    next.setEnabled(month.isBefore(YearMonth.from(RecordFilters.MAX_DATE)));
    int offset = month.atDay(1).getDayOfWeek().getValue() % 7;
    for (int i = 0; i < days.length; i++) {
      TextView day = days[i];
      int number = i - offset + 1;
      if (number < 1 || number > month.lengthOfMonth()) {
        day.setVisibility(INVISIBLE);
        day.setTag(null);
        continue;
      }
      LocalDate date = month.atDay(number);
      boolean endpoint = date.equals(first) || date.equals(last);
      boolean inRange = !date.isBefore(first) && !date.isAfter(last);
      day.setVisibility(VISIBLE);
      day.setText(Integer.toString(number));
      day.setTag(date.toString());
      day.setSelected(endpoint);
      day.setTextColor(endpoint ? u.onAccent : u.ink);
      day.setEnabled(!selectingEnd || !date.isBefore(first));
      String label = date + (date.equals(first) ? "，開始日期" : "")
          + (date.equals(last) ? "，結束日期" : "");
      day.setContentDescription(label);
      u.ripple(day, endpoint ? u.accent : inRange ? u.pale : u.surface, 12);
      day.setOnClickListener(v -> {
        if (selectingEnd) {
          last = date;
        } else {
          first = date;
          if (last.isBefore(first)) last = first;
          selectingEnd = true;
        }
        render();
      });
    }
  }

  private void updateEdge(TextView button, String label, LocalDate date, boolean active) {
    button.setText(label + "\n" + date);
    button.setContentDescription(label + "，" + date);
    button.setSelected(active);
    button.setTextColor(active ? u.onAccent : u.accent);
    u.ripple(button, active ? u.accent : u.pale, 16);
  }

  private void chooseMonth() {
    LinearLayout row = u.row();
    NumberPicker year = monthField(row, "年", RecordFilters.MIN_DATE.getYear(),
        RecordFilters.MAX_DATE.getYear(), month.getYear());
    NumberPicker monthNumber = monthField(row, "月", 1, 12, month.getMonthValue());
    new AppDialog.Builder(u.context).setTitle("跳到年月").setView(row)
        .setNegativeButton("取消", null).setPositiveButton("跳到", (dialog, which) -> {
          year.clearFocus();
          monthNumber.clearFocus();
          month = YearMonth.of(year.getValue(), monthNumber.getValue());
          render();
        }).show();
  }

  private NumberPicker monthField(LinearLayout row, String label, int min, int max, int value) {
    LinearLayout column = u.column();
    column.setGravity(Gravity.CENTER_HORIZONTAL);
    TextView title = u.text(label, 14, u.ink, true);
    column.addView(title);
    NumberPicker picker = new NumberPicker(u.context);
    picker.setMinValue(min);
    picker.setMaxValue(max);
    picker.setWrapSelectorWheel(false);
    picker.setValue(value);
    picker.setContentDescription(label);
    column.addView(picker);
    row.addView(column, new LayoutParams(0, -2, 1));
    return picker;
  }
}
