package dev.huang.notify;

import static org.junit.Assert.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class SearchQueryTest {
  @Test public void wordsReuseBoundPatternsAndTreatPunctuationLiterally() {
    SearchQuery query = new SearchQuery("　LINE　123  ｌｉｎｅ\t []?*_% ");
    List<String> args = new ArrayList<>();
    query.addPatterns(args);
    assertEquals(3, args.size());
    assertEquals("*[1１][2２][3３]*", args.get(1));
    assertEquals("*[[]][?][*]_%*", args.get(2));
    assertTrue(query.predicate("v", 4).contains("v.title GLOB ?4"));
    assertTrue(query.predicate("v", 4).contains("r.app GLOB ?6"));
    assertTrue(query.matchesField("LＩne"));
    assertFalse(SearchQuery.hasText("　\t\u00a0"));
  }

  @Test public void excerptIncludesFullWidthMatchWithoutSplittingEmoji() {
    SearchQuery query = new SearchQuery("123");
    String text = "前".repeat(95) + "🙂" + "段".repeat(7) + "１2３" + "尾".repeat(110);
    String excerpt = query.excerpt(text);
    assertTrue(excerpt.startsWith("…🙂"));
    assertTrue(excerpt.contains("１2３"));
    assertTrue(excerpt.endsWith("…"));
  }

  @Test public void recentDatesIncludeTodayAndUseLocalMidnights() {
    ZoneId zone = ZoneId.of("America/New_York");
    LocalDate today = LocalDate.of(2026, 3, 8);
    for (int days : new int[]{1, 7, 30}) {
      assertArrayEquals(RecordFilters.bounds(today.minusDays(days - 1).toString(), today.toString(), zone),
          RecordFilters.recentBounds(today, days, zone));
    }
  }
}
