package dev.huang.notify;

import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.test.AndroidTestCase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.json.JSONArray;

@SuppressWarnings("deprecation")
public class SearchStoreTest extends AndroidTestCase {
  private Store store;
  private static final String PACKAGE = "test.search.source";

  @Override protected void setUp() throws Exception {
    super.setUp();
    store = Store.get(getContext());
    clear();
  }

  @Override protected void tearDown() throws Exception {
    try { clear(); }
    finally { super.tearDown(); }
  }

  private void clear() throws Exception {
    Store.IO.submit(() -> {
      store.getWritableDatabase().delete("records", null, null);
      store.getWritableDatabase().delete("settings", null, null);
      store.pruneImages();
    }).get();
  }

  public void testOldVersionsMatchWithinSameVersionAndRespectFilters() throws Exception {
    NotificationImages.Result pic = new NotificationImages.Result(
        Collections.singletonList(ImageStoreTest.picture(0xff146c59)), "saved");
    store.capture("old", PACKAGE, "ＬＩＮＥ", "取貨通知", "原取貨碼 １２3", 1, pic);
    store.capture("old", PACKAGE, "ＬＩＮＥ", "取貨通知", "新取貨碼 1２３", 2);
    store.capture("old", PACKAGE, "ＬＩＮＥ", "包裹已領取", "完畢", 3);
    Record original = store.list("", PACKAGE, false, false, 1).get(0);
    store.favorite(original.id, true);
    store.note(original.id, "自取完成");
    assertTrue(search("LINE 取貨 123", false, null, 10).isEmpty());

    List<Record> hit = store.search(new SearchQuery("　LINE 取貨 １２3 自取"), PACKAGE, true,
        original.firstSeen, original.firstSeen + 1, true, true, null, 10, new CancellationSignal());
    assertEquals(1, hit.size());
    assertEquals(original.id, hit.get(0).id);
    assertEquals("新取貨碼 1２３", hit.get(0).historyMatch.body);
    String selected = hit.get(0).historyMatch.id;
    assertEquals("新取貨碼 1２３", store.version(original.id, selected).getString("body"));
    assertNull(store.version("other-record", selected));
    assertTrue(search("原取貨碼 新取貨碼", true, null, 10).isEmpty());
    assertNull(search("LINE 領取", true, null, 10).get(0).historyMatch);
    assertTrue(store.search(new SearchQuery("LINE 取貨"), PACKAGE, false, original.firstSeen + 1, -1,
        false, true, null, 10, new CancellationSignal()).isEmpty());

    JSONArray first = store.versionsPage(original.id, null, 1, selected);
    assertEquals("完畢", first.getJSONObject(0).getString("body"));
    store.capture("old", PACKAGE, "ＬＩＮＥ", "新通知", "新內容", 4);
    JSONArray next = store.versionsPage(original.id, first.getJSONObject(0).getString("id"), 10, selected);
    assertEquals(1, next.length());
    assertEquals("原取貨碼 １２3", next.getJSONObject(0).getString("body"));
  }

  public void testLiteralSymbolsAndPagesWithoutDuplicates() {
    for (int n = 0; n < 7; n++) store.capture("literal-" + n, PACKAGE, "Fixture", "參考 [],?* 12_%", "通知", n);
    store.capture("wild", PACKAGE, "Fixture", "看起來有點類似", "通知", 1);
    store.getWritableDatabase().execSQL("UPDATE records SET first_seen=1700000000000");
    List<Record> all = new ArrayList<>(search("[],?* １2_%", true, null, 4));
    assertEquals(4, all.size());
    all.addAll(search("[],?* １2_%", true, all.get(3), 4));
    assertEquals(7, all.size());
    HashSet<String> unique = new HashSet<>();
    for (Record record : all) assertTrue(unique.add(record.id));
    assertTrue(search("[],?* １2_%", true, all.get(6), 4).isEmpty());

    CancellationSignal signal = new CancellationSignal();
    signal.cancel();
    try {
      store.search(new SearchQuery("通知"), null, false, -1, -1, false, true, null, 4, signal);
      fail("Cancelled query returned results");
    } catch (OperationCanceledException expected) { }
  }

  private List<Record> search(String query, boolean history, Record after, int limit) {
    return store.search(new SearchQuery(query), null, false, -1, -1, false, history, after, limit,
        new CancellationSignal());
  }
}
