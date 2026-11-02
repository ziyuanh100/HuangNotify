package dev.huang.notify;

import java.time.*;

/** First-seen local-calendar date bounds. End is exclusive, including DST transitions. */
final class RecordFilters {
  static long[] bounds(String start,String end,ZoneId zone){
    if(start.isEmpty()&&end.isEmpty())return new long[]{-1,-1};
    if(!start.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||!end.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException("請輸入 YYYY-MM-DD 格式日期");
    LocalDate first=LocalDate.parse(start),last=LocalDate.parse(end);
    if(first.isBefore(LocalDate.of(1970,1,1))||last.isAfter(LocalDate.of(2999,12,31)))throw new IllegalArgumentException("日期需介於 1970 至 2999 年");
    if(last.isBefore(first))throw new IllegalArgumentException("結束日期不能早於開始日期");
    return new long[]{first.atStartOfDay(zone).toInstant().toEpochMilli(),last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()};
  }
}
