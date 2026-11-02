package dev.huang.notify;
import org.junit.Test;
import static org.junit.Assert.*;
import java.time.*;
public class RecordFiltersTest {
  @Test public void inclusiveLocalDatesAndDst(){
    ZoneId zone=ZoneId.of("Asia/Taipei");long[] bounds=RecordFilters.bounds("2026-11-01","2026-11-02",zone);
    assertEquals(LocalDate.of(2026,11,1).atStartOfDay(zone).toInstant().toEpochMilli(),bounds[0]);assertEquals(48*3600000L,bounds[1]-bounds[0]);
    long[] shortDay=RecordFilters.bounds("2026-03-08","2026-03-08",ZoneId.of("America/New_York"));assertEquals(23*3600000L,shortDay[1]-shortDay[0]);
    long[] longDay=RecordFilters.bounds("2026-11-01","2026-11-01",ZoneId.of("America/New_York"));assertEquals(25*3600000L,longDay[1]-longDay[0]);
  }
  @Test public void invalidRangeAndLeapDates(){ZoneId z=ZoneId.of("UTC");assertArrayEquals(new long[]{-1,-1},RecordFilters.bounds("","",z));assertThrows(IllegalArgumentException.class,()->RecordFilters.bounds("2026-11-02","2026-11-01",z));assertThrows(DateTimeException.class,()->RecordFilters.bounds("2026-02-29","2026-03-01",z));assertThrows(IllegalArgumentException.class,()->RecordFilters.bounds("2026-1-1","2026-01-01",z));assertEquals(2*86400000L,RecordFilters.bounds("2024-02-29","2024-03-01",z)[1]-RecordFilters.bounds("2024-02-29","2024-03-01",z)[0]);}
}
