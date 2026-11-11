package dev.huang.notify;

import android.os.CancellationSignal;
import android.test.AndroidTestCase;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.json.JSONObject;

/** Runs against real Android SQLite and JSON implementations, not mocked behavior. */
@SuppressWarnings("deprecation")
public class StoreTest extends AndroidTestCase {
  private Store store;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    store = Store.get(getContext());
    store.getWritableDatabase().delete("records", null, null);
    store.getWritableDatabase().delete("settings", null, null);
  }

  public void testUpdateDoesNotInflateCountAndRemovalKeepsContent() throws Exception {
    store.capture("key", "sample.app", "範例", "會議", "三點", 100);
    store.capture("key", "sample.app", "範例", "會議", "三點", 101);
    store.capture("key", "sample.app", "範例", "會議", "四點", 102);
    assertEquals(1L, store.counts()[0]);
    Record r = store.list("", null, false, false, 10).get(0);
    assertEquals(2, r.versions);
    assertEquals("四點", r.text);
    store.removed("key", 8);
    r = store.list("", null, false, false, 10).get(0);
    assertFalse(r.active);
    assertEquals("四點", r.text);
    store.capture("key", "sample.app", "範例", "會議", "四點", 103);
    assertEquals(2L, store.counts()[0]);
  }

  public void testAnalysisMatchesFirstSeenDateAndPackageFilters() {
    ZoneId zone = ZoneId.of("America/New_York");
    LocalDate first = LocalDate.of(2026, 11, 1);
    LocalDate last = first.plusDays(2);
    long[] bounds = RecordFilters.bounds(first.toString(), last.toString(), zone);
    ZonedDateTime overlap = first.atTime(1, 30).atZone(zone);
    saveAt("before", "sample.alpha", bounds[0] - 1);
    saveAt("start", "sample.alpha", bounds[0]);
    saveAt("early", "sample.alpha", overlap.toInstant().toEpochMilli());
    saveAt("late", "sample.beta", overlap.withLaterOffsetAtOverlap().toInstant().toEpochMilli());
    saveAt("end", "sample.beta", bounds[1] - 1);
    saveAt("after", "sample.alpha", bounds[1]);
    store.capture("early", "sample.alpha", "更新的 App 名稱", "已更新", "第二個版本", 2);
    store.removed("late", 8);

    NotificationAnalysis totals = store.analyze(first, last, zone, null, new CancellationSignal());
    assertEquals(4L, totals.total());
    assertEquals(4.0 / 3, totals.dailyAverage(), .0001);
    assertEquals(first, totals.busiestDay());
    assertEquals(3L, totals.dailyCount(first));
    assertEquals(0L, totals.dailyCount(first.plusDays(1)));
    assertEquals(1L, totals.dailyCount(last));
    assertEquals(2L, totals.hourlyCount(1));
    assertEquals(1L, totals.hourlyCount(23));
    assertEquals(2, totals.ranking().size());
    assertEquals("sample.beta", totals.ranking().get(0).pkg);
    assertEquals("更新的 App 名稱", totals.ranking().get(1).name());

    String pkg = "sample.alpha";
    NotificationAnalysis scoped = store.analyze(first, last, zone, pkg, new CancellationSignal());
    assertEquals(2L, scoped.total());
    assertEquals(scoped.total(), store.countInScope(pkg, bounds[0], bounds[1]));
    assertEquals(scoped.total(), store.listFiltered("", pkg, false, bounds[0], bounds[1], false, 10).size());
    NotificationAnalysis empty = store.analyze(first, last, zone, "sample.empty", new CancellationSignal());
    assertEquals(0L, empty.total());
    assertNull(empty.busiestDay());
    assertTrue(empty.ranking().isEmpty());
  }

  private void saveAt(String key, String pkg, long firstSeen) {
    store.capture(key, pkg, pkg, "通知", "內容", 1);
    store.getWritableDatabase().execSQL("UPDATE records SET first_seen=? WHERE nkey=?",
        new Object[]{firstSeen, key});
  }

  public void testBackupMergeIsIdempotentAndPreservesFavorite() throws Exception {
    store.capture("key", "sample.app", "範例", "備份", "內容", 100);
    Record r = store.list("", null, false, false, 10).get(0);
    store.favorite(r.id, true);
    JSONObject backup = store.exportBackup();
    store.delete(r.id);
    assertEquals(1, store.restore(backup, false)[0]);
    assertEquals(1, store.restore(backup, false)[1]);
    assertEquals(1L, store.counts()[0]);
    assertEquals(1L, store.counts()[2]);
    assertFalse(store.list("", null, false, false, 10).get(0).active);
  }

  public void testMalformedImportDoesNotPartiallyWrite() throws Exception {
    store.capture("key", "sample.app", "範例", "備份", "內容", 100);
    JSONObject backup = store.exportBackup();
    JSONObject invalid = new JSONObject(backup.getJSONArray("records").getJSONObject(0).toString());
    invalid.put("id", "not-a-uuid");
    backup.getJSONArray("records").put(invalid);
    try {
      store.restore(backup, false);
      fail("must reject invalid backup");
    } catch (IllegalArgumentException expected) {
    }
    assertEquals(1L, store.counts()[0]);
  }

  public void testVersionCollisionRollsBackNewRecordsAndSettings() throws Exception {
    store.capture("key","sample.app","範例","原本","保留",100);
    JSONObject backup=store.exportBackup();
    JSONObject imported=backup.getJSONArray("records").getJSONObject(0);
    imported.put("id",java.util.UUID.randomUUID().toString());
    backup.getJSONObject("settings").put("retention","7");
    try { store.restore(backup,true); fail("must reject version assigned to another record"); } catch(IllegalArgumentException expected) { }
    assertEquals(1L,store.counts()[0]); assertEquals("保留",store.list("",null,false,false,10).get(0).text);
    assertEquals("0",store.setting("retention","0"));
  }
  public void testExistingRecordAndVersionCannotChangeIdentity() throws Exception {
    store.capture("key","sample.app","範例","原本","保留",100);
    JSONObject backup=store.exportBackup(), row=backup.getJSONArray("records").getJSONObject(0);
    row.put("pkg","foreign.app");
    try { store.restore(backup,false); fail(); } catch(IllegalArgumentException expected) { }
    row.put("pkg","sample.app"); row.getJSONArray("versions").getJSONObject(0).put("body","被竄改");
    try { store.restore(backup,false); fail(); } catch(IllegalArgumentException expected) { }
    Record r=store.list("",null,false,false,10).get(0);
    assertEquals("sample.app",r.pkg); assertEquals("保留",store.versions(r.id).getJSONObject(0).getString("body"));
  }
  public void testBackupRejectsCoercedAndFractionalNumbers() throws Exception {
    store.capture("key","sample.app","範例","原本","保留",100);
    JSONObject backup=store.exportBackup();
    for(String field:new String[]{"version","created","posted","favorite","reason","time"}) {
      for(Object invalid:new Object[]{"1",1.5,1.0,Boolean.TRUE}) {
        JSONObject changed=new JSONObject(backup.toString());
        JSONObject target=field.equals("version") || field.equals("created")?changed:changed.getJSONArray("records").getJSONObject(0);
        if(field.equals("time")) target=target.getJSONArray("versions").getJSONObject(0);
        target.put(field,invalid);
        try { store.restore(changed,false); fail("accepted invalid "+field+" "+invalid); } catch(IllegalArgumentException expected) { }
      }
    }
    assertEquals(1L,store.counts()[0]);
  }

  public void testExclusionsAndPausePreventCapture() {
    store.putSetting("exclude:sample.app", "1");
    store.capture("key", "sample.app", "範例", "a", "b", 1);
    assertEquals(0L, store.counts()[0]);
    store.putSetting("exclude:sample.app", "0");
    store.putSetting("paused", "1");
    store.capture("key", "sample.app", "範例", "a", "b", 1);
    assertEquals(0L, store.counts()[0]);
  }

  public void testLiteralSearchAndRetentionProtectFavorites() {
    store.capture("key", "sample.app", "範例", "100%_", "內容", 1);
    Record r = store.list("", null, false, false, 10).get(0);
    assertEquals(1, store.list("%_", null, false, false, 10).size());
    assertEquals(0, store.list("missing", null, false, false, 10).size());
    store.favorite(r.id, true);
    store.getWritableDatabase().execSQL("UPDATE records SET first_seen=1");
    store.cleanup(7);
    assertEquals(1L, store.counts()[0]);
    store.favorite(r.id, false);
    store.cleanup(7);
    assertEquals(0L, store.counts()[0]);
  }

  public void testReconnectMarksUnknownWithoutInventingRemoval() {
    store.capture("key", "sample.app", "範例", "a", "b", 1);
    store.reconcile(new java.util.HashSet<>());
    Record r = store.list("", null, false, false, 10).get(0);
    assertFalse(r.active);
    assertEquals(0L, r.removed);
    assertEquals(0, r.reason);
  }
}
