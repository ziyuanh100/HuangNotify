package dev.huang.notify;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Counts each retained notification once, by the local time it was first saved. */
final class NotificationAnalysis {
  final LocalDate first, last;
  final ZoneId zone;
  private final Map<LocalDate, Long> days = new HashMap<>();
  private final Map<String, AppCount> apps = new HashMap<>();
  private final long[] hours = new long[24];
  private long total;
  private LocalDate busiestDay;
  private long busiestHourCount;

  NotificationAnalysis(LocalDate first, LocalDate last, ZoneId zone) {
    this.first = first;
    this.last = last;
    this.zone = zone;
  }

  // Store feeds the rows in first-seen order, so the latest saved label wins.
  void add(long firstSeen, String pkg, String label) {
    ZonedDateTime time = Instant.ofEpochMilli(firstSeen).atZone(zone);
    LocalDate date = time.toLocalDate();
    long count = days.getOrDefault(date, 0L) + 1;
    days.put(date, count);
    if (busiestDay == null || count > dailyCount(busiestDay)
        || (count == dailyCount(busiestDay) && date.isBefore(busiestDay))) {
      busiestDay = date;
    }
    busiestHourCount = Math.max(busiestHourCount, ++hours[time.getHour()]);
    AppCount app = apps.get(pkg);
    if (app == null) {
      app = new AppCount(pkg);
      apps.put(pkg, app);
    }
    if (label != null && !label.trim().isEmpty()) app.name = label;
    app.count++;
    total++;
  }

  long total() { return total; }
  double dailyAverage() { return total / (double) (ChronoUnit.DAYS.between(first, last) + 1); }
  LocalDate busiestDay() { return busiestDay; }
  long dailyCount(LocalDate date) { return days.getOrDefault(date, 0L); }
  long hourlyCount(int hour) { return hours[hour]; }
  long busiestHourCount() { return busiestHourCount; }

  List<AppCount> ranking() {
    List<AppCount> ranked = new ArrayList<>(apps.values());
    ranked.sort(Comparator.comparingLong(AppCount::count).reversed()
        .thenComparing(AppCount::name, String.CASE_INSENSITIVE_ORDER)
        .thenComparing(app -> app.pkg));
    return ranked;
  }

  static final class AppCount {
    final String pkg;
    private String name;
    private long count;

    AppCount(String pkg) {
      this.pkg = pkg;
      name = pkg;
    }

    String name() { return name; }
    long count() { return count; }
  }
}
