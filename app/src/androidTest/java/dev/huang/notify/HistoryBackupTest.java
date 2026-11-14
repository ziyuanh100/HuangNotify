package dev.huang.notify;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;
import android.test.AndroidTestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

@SuppressWarnings("deprecation")
public class HistoryBackupTest extends AndroidTestCase {
  private static final int RECORDS = 2684;
  private static final int VERSIONS = 250000;
  private static final long TIME = 1_700_000_000_000L;
  private static final String PACKAGE = "backup.history.fixture";
  private static final Uri FOLDER = Uri.parse("content://dev.huang.notify.testdocs/tree/root");
  private Store store;

  @Override protected void setUp() throws Exception {
    super.setUp();
    store = Store.get(getContext());
    clearRecords();
  }

  @Override protected void tearDown() throws Exception {
    try { clearRecords(); }
    finally { super.tearDown(); }
  }

  private void clearRecords() {
    SQLiteDatabase db = store.getWritableDatabase();
    db.delete("records", null, null);
    db.delete("settings", null, null);
    store.pruneImages();
  }

  public void testFullEncryptedBackupRestoresQuarterMillionVersionsAndAttachments() throws Exception {
    Context context = getContext();
    String record = UUID.randomUUID().toString();
    NotificationImages.Attachment picture = ImageStoreTest.picture(0xff146c59);
    seed(record, picture);

    AutoBackup.Documents destination = new AutoBackup.Documents(context, FOLDER);
    String document = destination.create("history-regression-" + UUID.randomUUID() + ".hnbk");
    char[] password = "history-test-password".toCharArray();
    try {
      BackupTask export = new BackupTask();
      try (BackupFiles.Session session = BackupFiles.export(context, password, export)) {
        assertTrue(session.readyBytes > 180_000_000L);
        assertTrue(session.readyBytes < BackupStreamCrypto.MAX_FILE_BYTES);
        BackupFiles.save(context, session, Uri.parse(document), export);
      }
      clearRecords();
      BackupTask restore = new BackupTask();
      try (BackupFiles.Session session = BackupFiles.read(context, Uri.parse(document), restore)) {
        assertTrue(session.encrypted);
        BackupFiles.unlock(session, password, restore);
        assertEquals(RECORDS, session.stage.records);
        assertEquals(VERSIONS, session.stage.versions);
        assertEquals(1, session.stage.images);
        assertEquals(RECORDS, store.restoreStage(session.stage, true, restore)[0]);
      }
      assertEquals(VERSIONS, store.storageUsage().historyCount);
      assertEquals(RECORDS, store.counts()[0]);
      assertEquals("30", store.setting("retention", "0"));
      Record restored = store.list("", null, true, false, 1).get(0);
      assertEquals(record, restored.id);
      assertEquals("保留備註", restored.note);
      assertEquals("原始內容", restored.text);
      assertEquals(picture.hash, store.recordImages(record).getString(0));
      assertTrue(java.util.Arrays.equals(picture.data, store.image(picture.hash)));
      try (Cursor latest = store.getReadableDatabase().rawQuery(
          "SELECT id FROM versions WHERE record_id=? ORDER BY time DESC,rowid DESC LIMIT 1", new String[]{record})) {
        assertTrue(latest.moveToFirst());
        assertEquals(new UUID(0, VERSIONS - VERSIONS % RECORDS).toString(), latest.getString(0));
      }
      try (Cursor attachment = store.getReadableDatabase().rawQuery(
          "SELECT image_hash FROM version_images WHERE version_id=?", new String[]{new UUID(0, RECORDS).toString()})) {
        assertTrue(attachment.moveToFirst());
        assertEquals(picture.hash, attachment.getString(0));
      }
    } finally {
      java.util.Arrays.fill(password, '\0');
      destination.delete(document);
    }
  }

  public void testTwoMillionHistoryEntriesRestoreAndRejectOneMore() throws Exception {
    int limit = 2_000_000;
    int recordCount = 2000;
    File dir = new File(getContext().getCacheDir(), "history-limit-" + UUID.randomUUID());
    assertTrue(dir.mkdir());
    try {
      seedCompactVersions(recordCount, limit);
      File json = new File(dir, "limit.json");
      try (FileOutputStream output = new FileOutputStream(json)) {
        BackupArchive.write(store.getReadableDatabase(), output, new BackupTask());
      }
      assertTrue(json.length() < BackupStreamCrypto.MAX_BYTES);

      clearRecords();
      File staging = new File(dir, "stage.db");
      try (BackupArchive.Stage stage = BackupArchive.read(json, staging, new BackupTask())) {
        assertEquals(limit, stage.versions);
        assertEquals(recordCount, stage.records);
        assertEquals(recordCount, store.restoreStage(stage, false, new BackupTask())[0]);
      }
      assertTrue(SQLiteDatabase.deleteDatabase(staging));
      assertEquals(limit, store.storageUsage().historyCount);

      appendOneVersion(json, limit);
      assertTrue(json.length() < BackupStreamCrypto.MAX_BYTES);
      try (BackupArchive.Stage ignored = BackupArchive.read(json, staging, new BackupTask())) {
        fail("Accepted a backup with 2,000,001 history entries");
      } catch (BackupProblem expected) {
        assertEquals(BackupArchive.TOO_MANY_VERSIONS, expected.getMessage());
      }
      assertEquals(limit, store.storageUsage().historyCount);

      store.capture("after-limit", PACKAGE, "Fixture", "t", "1", TIME);
      File rejected = new File(dir, "rejected.json");
      try (FileOutputStream output = new FileOutputStream(rejected)) {
        BackupArchive.write(store.getReadableDatabase(), output, new BackupTask());
        fail("Exported a backup with 2,000,001 history entries");
      } catch (BackupProblem expected) {
        assertEquals(BackupArchive.TOO_MANY_VERSIONS, expected.getMessage());
      }
      assertEquals(0, rejected.length());
      assertEquals(limit + 1L, store.storageUsage().historyCount);
    } finally {
      BackupFiles.erase(dir);
    }
  }

  private void seedCompactVersions(int recordCount, int versions) {
    SQLiteDatabase db = store.getWritableDatabase();
    String sequence = "WITH RECURSIVE numbers(n) AS (VALUES(0) UNION ALL SELECT n+1 FROM numbers WHERE n+1<?) ";
    String recordId = "printf('00000000-0000-0001-0000-%012x', ";
    db.beginTransaction();
    try {
      db.execSQL(sequence + "INSERT INTO records(id,nkey,pkg,app,title,body,posted,first_seen,updated,hash)"
              + " SELECT " + recordId + "n), 'compact-'||n, ?, 'Fixture', 't', '1', ?, ?, ?, ? FROM numbers",
          new Object[]{recordCount, PACKAGE, TIME, TIME, TIME + versions, EventPolicy.fingerprint("t", "1")});
      db.execSQL(sequence + "INSERT INTO versions(id,record_id,time,title,body,hash)"
              + " SELECT printf('00000000-0000-0000-0000-%012x',n), " + recordId + "n%?), ?+n, 't',"
              + " CASE n/?%2 WHEN 0 THEN '0' ELSE '1' END, CASE n/?%2 WHEN 0 THEN ? ELSE ? END FROM numbers",
          new Object[]{versions, recordCount, TIME, recordCount, recordCount,
              EventPolicy.fingerprint("t", "0"), EventPolicy.fingerprint("t", "1")});
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  private static void appendOneVersion(File json, int version) throws Exception {
    byte[] suffix = "]}],\"images\":[],\"settings\":{}}".getBytes(StandardCharsets.UTF_8);
    String extra = "," + new JSONObject().put("id", new UUID(0, version).toString()).put("time", TIME)
        .put("title", "t").put("body", "1").put("image_status", "none").put("images", new JSONArray());
    try (RandomAccessFile file = new RandomAccessFile(json, "rw")) {
      long offset = file.length() - suffix.length;
      file.seek(offset);
      byte[] actual = new byte[suffix.length];
      file.readFully(actual);
      assertTrue(java.util.Arrays.equals(suffix, actual));
      file.seek(offset);
      file.write(extra.getBytes(StandardCharsets.UTF_8));
      file.write(suffix);
    }
  }

  private void seed(String record, NotificationImages.Attachment picture) {
    SQLiteDatabase db = store.getWritableDatabase();
    db.beginTransaction();
    try (SQLiteStatement records = db.compileStatement(
        "INSERT INTO records(id,nkey,pkg,app,title,body,posted,first_seen,updated,hash)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)");
        SQLiteStatement versions = db.compileStatement(
            "INSERT INTO versions(id,record_id,time,title,body,hash) VALUES(?,?,?,?,?,?)")) {
      String[] ids = new String[RECORDS];
      String recordHash = EventPolicy.fingerprint("通知", "原始內容");
      for (int i = 0; i < ids.length; i++) {
        ids[i] = i == 0 ? record : new UUID(1, i).toString();
        records.bindString(1, ids[i]);
        records.bindString(2, "通知-" + i);
        records.bindString(3, PACKAGE);
        records.bindString(4, "Fixture");
        records.bindString(5, "通知");
        records.bindString(6, "原始內容");
        records.bindLong(7, TIME);
        records.bindLong(8, TIME);
        records.bindLong(9, TIME);
        records.bindString(10, recordHash);
        records.executeInsert();
      }
      String body = String.join("", Collections.nCopies(207, "資"));
      versions.bindLong(3, TIME);
      versions.bindString(4, "歷程");
      versions.bindString(5, body);
      versions.bindString(6, EventPolicy.fingerprint("歷程", body));
      for (int i = 1; i <= VERSIONS; i++) {
        versions.bindString(1, new UUID(0, i).toString());
        versions.bindString(2, ids[i % ids.length]);
        versions.executeInsert();
      }
      String versionId = new UUID(0, RECORDS).toString();
      db.execSQL("INSERT INTO images(hash,data,width,height) VALUES(?,?,?,?)",
          new Object[]{picture.hash,picture.data,picture.width,picture.height});
      db.execSQL("INSERT INTO record_images VALUES(?,0,?)", new Object[]{record,picture.hash});
      db.execSQL("INSERT INTO version_images VALUES(?,0,?)", new Object[]{versionId,picture.hash});
      db.execSQL("UPDATE records SET image_status='saved' WHERE id=?", new Object[]{record});
      db.execSQL("UPDATE versions SET image_status='saved' WHERE id=?", new Object[]{versionId});
      store.favorite(record, true);
      store.note(record, "保留備註");
      store.putSetting("retention", "30");
      db.setTransactionSuccessful();
    } finally { db.endTransaction(); }
  }
}
