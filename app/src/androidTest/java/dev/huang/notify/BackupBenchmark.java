package dev.huang.notify;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Debug;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;

/** Test-only fixture for measuring the installed version at its exact plaintext limit. */
final class BackupBenchmark {
  private static final Uri TREE = Uri.parse("content://dev.huang.notify.testdocs/tree/root");
  private static final char[] PASSWORD = "benchmark-password-123".toCharArray();
  private static final int TRIALS = 5, NOTE_LIMIT = 100000;

  static void run(Context context, String kind, int expectedMiB, java.util.function.Consumer<String> progress)
      throws Exception {
    if (!kind.equals("text") && !kind.equals("images")) throw new IllegalArgumentException("Unknown fixture");
    long limit = BackupStreamCrypto.MAX_BYTES;
    if (expectedMiB != 0 && expectedMiB * 1024L * 1024L != limit) {
      throw new AssertionError("Installed version does not have the requested limit");
    }
    JSONObject report = new JSONObject().put("app_version", BuildConfig.VERSION_NAME)
        .put("api", android.os.Build.VERSION.SDK_INT).put("device", android.os.Build.MODEL)
        .put("abi", android.os.Build.SUPPORTED_ABIS[0]).put("limit_bytes", limit)
        .put("kind", kind).put("warmups_per_mode", 1).put("measured_runs_per_mode", TRIALS)
        .put("destination", "local debug DocumentsProvider").put("cases", new JSONArray());
    try {
      progress.accept("Preparing " + kind + " at " + limit + " bytes");
      long[] counts = Store.IO.submit(() -> seed(context, kind, limit)).get();
      report.put("records", counts[0]).put("images", counts[1]);
      for (boolean encrypted : new boolean[]{false, true}) {
        JSONArray runs = new JSONArray();
        JSONObject mode = new JSONObject().put("encrypted", encrypted).put("runs", runs);
        report.getJSONArray("cases").put(mode);
        for (int trial = 0; trial <= TRIALS; trial++) {
          progress.accept(BuildConfig.VERSION_NAME + " " + kind + " " + (encrypted ? "encrypted" : "plain")
              + " trial " + trial + "/" + TRIALS);
          JSONObject sample = measure(context, encrypted, limit, counts);
          if (trial == 0) mode.put("warmup", sample);
          else runs.put(sample);
        }
        mode.put("export_median_ms", median(runs, "export_ms"))
            .put("restore_median_ms", median(runs, "restore_ms"));
      }
      report.put("completed", true);
    } finally {
      try (OutputStream out = new FileOutputStream(new File(context.getFilesDir(), "backup-performance.json"))) {
        out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
      }
    }
  }

  private static JSONObject measure(Context context, boolean encrypted, long limit, long[] counts) throws Exception {
    AutoBackup.Documents docs = new AutoBackup.Documents(context, TREE);
    String id = docs.create("ceiling-" + (encrypted ? "encrypted.hnbk" : "plain.hnjson"));
    Uri uri = Uri.parse(id);
    BackupTask export = new BackupTask();
    File temp = new File(context.getCacheDir(), "backup-work");
    long exportMs, bytes, exportTemp;
    JSONObject exportPhases;
    try {
      try (Sampling memory = new Sampling(temp)) {
        long start = System.nanoTime();
        try (BackupFiles.Session session = BackupFiles.export(context, encrypted ? PASSWORD : null, export)) {
          bytes = session.ready.length();
          long expected = encrypted ? BackupStreamCrypto.MAX_FILE_BYTES : limit;
          if (bytes != expected) throw new AssertionError("Expected exactly " + expected + " bytes, got " + bytes);
          BackupFiles.save(context, session, uri, export);
        }
        exportMs = millis(start);
        exportPhases = new JSONObject(export.timingsMillis());
        exportTemp = memory.temp;
      }
      // Prepare an empty database outside the timer so this measures a real restore.
      Store store = Store.get(context);
      Store.IO.submit(() -> { reset(store); return null; }).get();
      BackupTask restore = new BackupTask();
      try (Sampling memory = new Sampling(temp)) {
        long start = System.nanoTime();
        try (BackupFiles.Session session = BackupFiles.read(context, uri, restore)) {
          if (encrypted) BackupFiles.unlock(session, PASSWORD, restore);
          if (session.stage.records != counts[0] || session.stage.images != counts[1]) {
            throw new AssertionError("Archive counts changed");
          }
          int restored = Store.IO.submit(() -> store.restoreStage(session.stage, true, restore)).get()[0];
          if (restored != counts[0]) throw new AssertionError("Restore lost records");
        }
        long restoreMs = millis(start);
        JSONObject restorePhases = new JSONObject(restore.timingsMillis());
        if (store.counts()[0] != counts[0] || store.imageUsage()[0] != counts[1]) {
          throw new AssertionError("Restored database counts changed");
        }
        return new JSONObject().put("output_bytes", bytes).put("export_ms", exportMs).put("restore_ms", restoreMs)
            .put("export_phases_ms", exportPhases).put("restore_phases_ms", restorePhases)
            .put("export_temp_bytes", exportTemp).put("restore_temp_bytes", memory.temp)
            .put("sampled_restore_pss_bytes", memory.pss);
      }
    } finally {
      docs.delete(id);
    }
  }

  private static void reset(Store store) {
    SQLiteDatabase db = store.getWritableDatabase();
    db.delete("records", null, null);
    db.delete("settings", null, null);
    store.pruneImages();
    try (Cursor checkpoint = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) { checkpoint.moveToFirst(); }
    db.execSQL("VACUUM");
  }


  private static long[] seed(Context context, String kind, long limit) throws Exception {
    Store store = Store.get(context);
    reset(store);
    SQLiteDatabase db = store.getWritableDatabase();
    Random random = new Random(8130);
    long estimate = 0;
    int index = 0;
    db.beginTransaction();
    try {
      if (kind.equals("images")) {
        int width = 256, height = 240;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];
        try {
          while (estimate < limit - 3 * 1024 * 1024) {
            for (int p = 0; p < pixels.length; p++) pixels[p] = 0xff000000 | random.nextInt(0x1000000);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            NotificationImages.Attachment image = NotificationImages.normalize(bitmap);
            if (image == null) throw new AssertionError("Image fixture could not be encoded");
            store.capture("image-" + index, "benchmark.fixture", "Fixture", "Image", "Synthetic", index,
                new NotificationImages.Result(Collections.singletonList(image), "saved"));
            estimate += ((image.data.length + 2L) / 3) * 4 + 1200;
            index++;
          }
        } finally {
          bitmap.recycle();
        }
      } else {
        String body = repeat('資', 90000);
        int rows = (int) ((limit - 2 * 1024 * 1024) / 541000);
        for (; index < rows; index++) store.capture("text-" + index, "benchmark.fixture", "Fixture", "Text", body, index);
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    // Fill the gap using real notes so the JSON is exactly at the version's byte limit.
    long bytes = bytes(db);
    if (bytes >= limit) throw new AssertionError("Fixture exceeded the target before adjustment");
    int fillers = (int) ((limit - bytes + NOTE_LIMIT - 1) / NOTE_LIMIT);
    String[] ids = new String[fillers];
    try (Cursor cursor = db.rawQuery("SELECT id FROM records ORDER BY id DESC LIMIT " + fillers, null)) {
      for (int i = 0; i < fillers; i++) {
        if (!cursor.moveToNext()) throw new AssertionError("Not enough records for exact-size adjustment");
        ids[i] = cursor.getString(0);
      }
    }
    db.beginTransaction();
    try {
      for (String id : ids) {
        int length = (int) Math.min(NOTE_LIMIT, limit - bytes);
        store.note(id, repeat('n', length));
        bytes += length;
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    if (bytes != limit || bytes(db) != limit) throw new AssertionError("Fixture is not exactly at the limit");
    return new long[]{store.counts()[0], store.imageUsage()[0]};
  }

  private static long bytes(SQLiteDatabase db) throws Exception {
    BackupTask count = new BackupTask();
    BackupArchive.write(db, new OutputStream() {
      @Override public void write(int value) {}
      @Override public void write(byte[] bytes, int offset, int length) {}
    }, count);
    return count.done;
  }

  private static String repeat(char value, int count) {
    char[] chars = new char[count];
    Arrays.fill(chars, value);
    return new String(chars);
  }

  private static long median(JSONArray values, String field) throws Exception {
    long[] sorted = new long[values.length()];
    for (int i = 0; i < sorted.length; i++) sorted[i] = values.getJSONObject(i).getLong(field);
    Arrays.sort(sorted);
    return sorted[sorted.length / 2];
  }

  private static long millis(long start) { return (System.nanoTime() - start) / 1_000_000; }

  private static final class Sampling implements AutoCloseable {
    volatile long temp, pss;
    final Thread thread;

    Sampling(File root) {
      thread = new Thread(() -> {
        while (!Thread.currentThread().isInterrupted()) {
          temp = Math.max(temp, directoryBytes(root));
          pss = Math.max(pss, Debug.getPss() * 1024L);
          try { Thread.sleep(100); } catch (InterruptedException e) { return; }
        }
      }, "backup-sampling");
      thread.start();
    }

    public void close() throws InterruptedException {
      thread.interrupt();
      thread.join();
    }

    private static long directoryBytes(File root) {
      if (root.isFile()) return root.length();
      File[] children = root.listFiles();
      long bytes = 0;
      if (children != null) for (File child : children) bytes += directoryBytes(child);
      return bytes;
    }
  }
}
