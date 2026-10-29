package dev.huang.notify;

import android.test.AndroidTestCase;
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
