package dev.huang.notify;

import android.content.Context;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs once on API 35 as a separate CI instrument phase. */
final class SearchBenchmark {
  private static final int RECORDS = 2000;
  private static final String MATCH_ID = new UUID(0, 0).toString();
  private static final String HIT = "Fixture 取件 728";
  private static final String MISS = "缺少這個關鍵字";

  static void run(Context context) throws Exception {
    Store store = Store.get(context);
    JSONArray sizes = new JSONArray();
    try {
      for (int count : new int[]{400_000, 2_000_000}) {
        Store.IO.submit(() -> {
          reset(store);
          HistoryBackupTest.seedCompactVersions(store, RECORDS, count);
          store.getWritableDatabase().execSQL("UPDATE versions SET title=?,body=? WHERE id=?",
              new Object[]{"取件通知", "這筆只有舊版本保留取件代碼 ７2８", MATCH_ID});
        }).get(180, TimeUnit.SECONDS);
        long hit = median(store, HIT, true, 1);
        long miss = median(store, MISS, true, 0);
        long current = median(store, "Fixture t", false, 81);
        JSONObject parallel = writeDuringSearch(store, count);
        long cancel = cancelSearch(store);
        sizes.put(new JSONObject().put("versions", count).put("history_hit_ms", hit)
            .put("history_miss_ms", miss).put("current_first_page_ms", current)
            .put("parallel_write", parallel).put("cancel_ms", cancel));
      }
      JSONObject report = new JSONObject().put("app_version", BuildConfig.VERSION_NAME)
          .put("api", android.os.Build.VERSION.SDK_INT).put("records", RECORDS)
          .put("warmups", 1).put("measured_runs", 3).put("sizes", sizes);
      try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "search-performance.json"))) {
        out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
      }
    } finally {
      Store.IO.submit(() -> reset(store)).get(180, TimeUnit.SECONDS);
    }
  }

  private static long median(Store store, String input, boolean history, int expected) throws Exception {
    long[] samples = new long[3];
    for (int i = -1; i < samples.length; i++) {
      CancellationSignal signal = new CancellationSignal();
      long start = SystemClock.elapsedRealtime();
      List<Record> rows = Store.SEARCH.submit(() -> search(store, input, history, signal)).get(30, TimeUnit.SECONDS);
      long elapsed = SystemClock.elapsedRealtime() - start;
      require(rows.size() == expected, "Unexpected result count for " + input);
      if (input.equals(HIT)) require(MATCH_ID.equals(rows.get(0).historyMatch.id), "Wrong historical version");
      if (i >= 0) samples[i] = elapsed;
    }
    Arrays.sort(samples);
    return samples[1];
  }

  private static JSONObject writeDuringSearch(Store store, int size) throws Exception {
    CountDownLatch ready = new CountDownLatch(1);
    CancellationSignal signal = new CancellationSignal();
    Future<List<Record>> reading = Store.SEARCH.submit(() -> {
      ready.countDown();
      return search(store, MISS, true, signal);
    });
    try {
      require(ready.await(5, TimeUnit.SECONDS), "Reader did not start");
      SystemClock.sleep(20);
      long start = SystemClock.elapsedRealtime();
      String key = "bench-write-" + size;
      Store.IO.submit(() -> store.capture(key, "bench.concurrent", "Fixture", "搜尋期間寫入", "寫入驗證", 1))
          .get(10, TimeUnit.SECONDS);
      long elapsed = SystemClock.elapsedRealtime() - start;
      boolean finishedWhileReading = !reading.isDone();
      require(reading.get(30, TimeUnit.SECONDS).isEmpty(), "Missing term returned a match");
      require(store.list("寫入驗證", "bench.concurrent", false, false, 1).size() == 1, "Concurrent write was lost");
      return new JSONObject().put("saved", true).put("while_search_running", finishedWhileReading)
          .put("write_ms", elapsed);
    } finally {
      signal.cancel();
    }
  }

  private static long cancelSearch(Store store) throws Exception {
    CancellationSignal signal = new CancellationSignal();
    CountDownLatch ready = new CountDownLatch(1);
    Future<Boolean> reading = Store.SEARCH.submit(() -> {
      try {
        ready.countDown();
        search(store, MISS, true, signal);
        return false;
      } catch (OperationCanceledException expected) {
        return true;
      }
    });
    require(ready.await(5, TimeUnit.SECONDS), "Cancellable search did not start");
    SystemClock.sleep(10);
    long start = SystemClock.elapsedRealtime();
    signal.cancel();
    require(reading.get(5, TimeUnit.SECONDS), "Long search ignored or completed before cancellation");
    return SystemClock.elapsedRealtime() - start;
  }

  private static List<Record> search(Store store, String input, boolean history, CancellationSignal signal) {
    return store.search(new SearchQuery(input), null, false, -1, -1, false, history, null, 81, signal);
  }

  private static void reset(Store store) {
    store.getWritableDatabase().delete("records", null, null);
    store.getWritableDatabase().delete("settings", null, null);
    store.pruneImages();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
