package dev.huang.notify;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.CancellationSignal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** All disk work runs on IO, including listener events, export and restore. */
public final class Store extends SQLiteOpenHelper {
  public static final ExecutorService IO = Executors.newSingleThreadExecutor();
  private static volatile Store instance;
  public static volatile String error = "";

  public static Store get(Context context) {
    if (instance == null)
      synchronized (Store.class) {
        if (instance == null) instance = new Store(context.getApplicationContext());
      }
    return instance;
  }

  private final java.io.File databaseFile;
  private Store(Context context) {
    super(context, "notifications.db", null, 3);
    databaseFile=context.getDatabasePath("notifications.db");
    setWriteAheadLoggingEnabled(true);
  }

  @Override
  public void onConfigure(SQLiteDatabase db) {
    db.setForeignKeyConstraintsEnabled(true);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE records(id TEXT PRIMARY KEY, nkey TEXT NOT NULL, pkg TEXT NOT NULL, app TEXT"
            + " NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, posted INTEGER NOT NULL,"
            + " first_seen INTEGER NOT NULL, updated INTEGER NOT NULL, removed INTEGER NOT NULL"
            + " DEFAULT 0, reason INTEGER NOT NULL DEFAULT 0, favorite INTEGER NOT NULL DEFAULT 0,"
            + " active INTEGER NOT NULL DEFAULT 0, note TEXT NOT NULL DEFAULT '', hash TEXT NOT"
            + " NULL)");
    db.execSQL(
        "CREATE TABLE versions(id TEXT PRIMARY KEY, record_id TEXT NOT NULL REFERENCES records(id)"
            + " ON DELETE CASCADE, time INTEGER NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL,"
            + " hash TEXT NOT NULL)");
    db.execSQL("CREATE TABLE settings(name TEXT PRIMARY KEY, value TEXT NOT NULL)");
    db.execSQL("CREATE INDEX records_time ON records(first_seen DESC)");
    db.execSQL("CREATE INDEX records_app ON records(pkg,first_seen DESC)");
    db.execSQL("CREATE INDEX records_key ON records(nkey,active)");
    db.execSQL("CREATE INDEX versions_record ON versions(record_id,time)");
    imageSchema(db);
    diagnosticSchema(db);
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if(oldVersion<2)imageSchema(db);
    if(oldVersion<3)diagnosticSchema(db);
  }

  private static void diagnosticSchema(SQLiteDatabase db) {db.execSQL("CREATE TABLE diagnostics(id INTEGER PRIMARY KEY AUTOINCREMENT,time INTEGER NOT NULL,code TEXT NOT NULL,pkg TEXT NOT NULL)");}

  private static void imageSchema(SQLiteDatabase db) {
    db.execSQL("ALTER TABLE records ADD COLUMN image_status TEXT NOT NULL DEFAULT 'none'");
    db.execSQL("ALTER TABLE versions ADD COLUMN image_status TEXT NOT NULL DEFAULT 'none'");
    db.execSQL("CREATE TABLE images(hash TEXT PRIMARY KEY, data BLOB NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL)");
    db.execSQL("CREATE TABLE record_images(record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE, position INTEGER NOT NULL, image_hash TEXT NOT NULL REFERENCES images(hash), PRIMARY KEY(record_id,position))");
    db.execSQL("CREATE TABLE version_images(version_id TEXT NOT NULL REFERENCES versions(id) ON DELETE CASCADE, position INTEGER NOT NULL, image_hash TEXT NOT NULL REFERENCES images(hash), PRIMARY KEY(version_id,position))");
    db.execSQL("CREATE INDEX record_image_hash ON record_images(image_hash)");
    db.execSQL("CREATE INDEX version_image_hash ON version_images(image_hash)");
  }

  private void putImages(NotificationImages.Result images) {
    for(NotificationImages.Attachment image:images.images) {
      ContentValues v=new ContentValues(); v.put("hash",image.hash); v.put("data",image.data); v.put("width",image.width); v.put("height",image.height);
      getWritableDatabase().insertWithOnConflict("images",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }
  }
  private void linkImages(String table,String column,String id,List<String> hashes) {
    SQLiteDatabase db=getWritableDatabase(); db.delete(table,column+"=?",new String[]{id});
    for(int i=0;i<hashes.size();i++) {
      ContentValues v=new ContentValues(); v.put(column,id); v.put("position",i); v.put("image_hash",hashes.get(i)); db.insertOrThrow(table,null,v);
    }
  }
  private static List<String> hashes(NotificationImages.Result result) {
    List<String> hashes=new ArrayList<>(); for(NotificationImages.Attachment image:result.images)hashes.add(image.hash); return hashes;
  }
  private List<String> imageHashes(String table,String column,String id) {
    List<String> hashes=new ArrayList<>();
    try(Cursor c=getReadableDatabase().rawQuery("SELECT image_hash FROM "+table+" WHERE "+column+"=? ORDER BY position",new String[]{id})) { while(c.moveToNext())hashes.add(c.getString(0)); }
    return hashes;
  }
  public JSONArray recordImages(String id) { return new JSONArray(imageHashes("record_images","record_id",id)); }
  public byte[] image(String hash) {
    if(hash==null || !hash.matches("[0-9a-f]{64}"))return null;
    try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM images WHERE hash=?",new String[]{hash})) { return c.moveToFirst()?c.getBlob(0):null; }
  }
  void pruneImages() {
    getWritableDatabase().execSQL("DELETE FROM images WHERE NOT EXISTS(SELECT 1 FROM record_images r WHERE r.image_hash=images.hash) AND NOT EXISTS(SELECT 1 FROM version_images v WHERE v.image_hash=images.hash)");
  }

  public String setting(String name, String fallback) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT value FROM settings WHERE name=?", new String[] {name})) {
      return c.moveToFirst() ? c.getString(0) : fallback;
    }
  }

  public void putSetting(String name, String value) {
    ContentValues v = new ContentValues();
    v.put("name", name);
    v.put("value", value);
    getWritableDatabase()
        .insertWithOnConflict("settings", null, v, SQLiteDatabase.CONFLICT_REPLACE);
  }

  public java.util.Set<String> excludedPackages() {
    java.util.Set<String> excluded=new java.util.HashSet<>();
    try(Cursor c=getReadableDatabase().rawQuery("SELECT name FROM settings WHERE name LIKE 'exclude:%' AND value='1'",null)) {
      while(c.moveToNext()) excluded.add(c.getString(0).substring(8));
    }
    return excluded;
  }

  public void setSourceChoices(java.util.Map<String,Boolean> allowed) {
    SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
    try {
      for(java.util.Map.Entry<String,Boolean> choice:allowed.entrySet()) {
        ContentValues value=new ContentValues(); value.put("name","exclude:"+choice.getKey()); value.put("value",choice.getValue()?"0":"1");
        if(db.insertWithOnConflict("settings",null,value,SQLiteDatabase.CONFLICT_REPLACE)==-1)
          throw new android.database.sqlite.SQLiteException("無法儲存記錄來源");
      }
      db.setTransactionSuccessful();
    } finally { db.endTransaction(); }
  }

  public boolean excluded(String pkg) {
    return "1".equals(setting("exclude:" + pkg, "0"));
  }

  public void capture(String key, String pkg, String app, String title, String body, long posted) {
    capture(key,pkg,app,title,body,posted,NotificationImages.Result.empty("none"));
  }

  public void capture(String key,String pkg,String app,String title,String body,long posted,NotificationImages.Result images) {captureEvent(key,pkg,app,title,body,posted,images);}
  public String captureEvent(String key,String pkg,String app,String title,String body,long posted,NotificationImages.Result images) {
    if(excluded(pkg))return "excluded";
    if("1".equals(setting("paused","0")))return "paused";
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      String id = null, oldHash = "";
      try (Cursor c =
          db.rawQuery(
              "SELECT id,hash FROM records WHERE nkey=? AND active=1 LIMIT 1",
              new String[] {key})) {
        if (c.moveToFirst()) {
          id = c.getString(0);
          oldHash = c.getString(1);
        }
      }
      long now = System.currentTimeMillis();
      List<String> imageHashes=hashes(images);
      String hash = NotificationImages.fingerprint(title,body,imageHashes,images.status);
      if (id != null && hash.equals(oldHash)) {
        db.setTransactionSuccessful();
        return "unchanged";
      }
      putImages(images);
      ContentValues v = new ContentValues();
      v.put("image_status",images.status);
      v.put("title", title);
      v.put("body", body);
      v.put("updated", now);
      v.put("hash", hash);
      v.put("app", app);
      if (id == null) {
        id = UUID.randomUUID().toString();
        v.put("id", id);
        v.put("nkey", key);
        v.put("pkg", pkg);
        v.put("posted", posted);
        v.put("first_seen", now);
        v.put("active", 1);
        db.insertOrThrow("records", null, v);
      } else db.update("records", v, "id=?", new String[] {id});
      ContentValues version = new ContentValues();
      String versionId=UUID.randomUUID().toString();
      version.put("id", versionId);
      version.put("image_status",images.status);
      version.put("record_id", id);
      version.put("time", now);
      version.put("title", title);
      version.put("body", body);
      version.put("hash", hash);
      db.insertOrThrow("versions", null, version);
      linkImages("record_images","record_id",id,imageHashes);
      linkImages("version_images","version_id",versionId,imageHashes);
      putSetting("last_saved", Long.toString(now));
      db.setTransactionSuccessful();
      error = "";
      return "saved";
    } finally {
      db.endTransaction();
    }
  }

  public void removed(String key, int reason) {
    ContentValues v = new ContentValues();
    v.put("active", 0);
    v.put("removed", System.currentTimeMillis());
    v.put("reason", reason);
    getWritableDatabase().update("records", v, "nkey=? AND active=1", new String[] {key});
  }

  /** Unknown removals while disconnected must not be reported as user dismissals. */
  public void reconcile(java.util.Set<String> activeKeys) {
    SQLiteDatabase db = getWritableDatabase();
    try (Cursor c = db.rawQuery("SELECT id,nkey FROM records WHERE active=1", null)) {
      while (c.moveToNext())
        if (!activeKeys.contains(c.getString(1))) {
          ContentValues v = new ContentValues();
          v.put("active", 0);
          v.put("reason", 0);
          db.update("records", v, "id=?", new String[] {c.getString(0)});
        }
    }
  }

  public List<Record> list(String query, String pkg, boolean favorite, boolean today, int limit) {
    return listFiltered(query,pkg,favorite,today?todayStart():-1,-1,false,limit);
  }

  private static String recordScope(String pkg, long start, long end, List<String> args) {
    String where = "1=1";
    if (pkg != null) {
      where += " AND pkg=?";
      args.add(pkg);
    }
    if (start != -1) {
      where += " AND first_seen>=?";
      args.add(Long.toString(start));
    }
    if (end != -1) {
      where += " AND first_seen<?";
      args.add(Long.toString(end));
    }
    return where;
  }

  List<Record> listFiltered(String query,String pkg,boolean favorite,long start,long end,boolean images,int limit) {
    List<String> args = new ArrayList<>();
    String where = recordScope(pkg, start, end, args);
    if (!query.trim().isEmpty()) {
      where +=
          " AND (title LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\' OR app LIKE ? ESCAPE '\\' OR"
              + " note LIKE ? ESCAPE '\\')";
      String term = EventPolicy.likePattern(query);
      for (int i = 0; i < 4; i++) args.add(term);
    }
    if (favorite) where += " AND favorite=1";
    if(images)where+=" AND (EXISTS(SELECT 1 FROM record_images i WHERE i.record_id=r.id) OR EXISTS(SELECT 1 FROM versions v JOIN version_images i ON i.version_id=v.id WHERE v.record_id=r.id))";
    List<Record> result = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT r.*, (SELECT COUNT(*) FROM versions v WHERE v.record_id=r.id) AS"
                    + " version_count, (SELECT COUNT(*) FROM record_images ri WHERE ri.record_id=r.id) AS image_count FROM records r WHERE "
                    + where
                    + " ORDER BY first_seen DESC,id DESC LIMIT "
                    + limit,
                args.toArray(new String[0]))) {
      while (c.moveToNext()) result.add(record(c));
    }
    return result;
  }

  long countInScope(String pkg, long start, long end) {
    List<String> args = new ArrayList<>();
    String where = recordScope(pkg, start, end, args);
    try (Cursor c = getReadableDatabase().rawQuery(
        "SELECT COUNT(*) FROM records WHERE " + where, args.toArray(new String[0]))) {
      c.moveToFirst();
      return c.getLong(0);
    }
  }

  NotificationAnalysis analyze(LocalDate first, LocalDate last, ZoneId zone, String pkg,
      CancellationSignal signal) {
    signal.throwIfCanceled();
    long[] bounds = RecordFilters.bounds(first.toString(), last.toString(), zone);
    List<String> args = new ArrayList<>();
    String where = recordScope(pkg, bounds[0], bounds[1], args);
    NotificationAnalysis analysis = new NotificationAnalysis(first, last, zone);
    try (Cursor c = getReadableDatabase().rawQuery(
        "SELECT first_seen,pkg,app FROM records WHERE " + where + " ORDER BY first_seen",
        args.toArray(new String[0]), signal)) {
      while (c.moveToNext()) {
        signal.throwIfCanceled();
        analysis.add(c.getLong(0), c.getString(1), c.getString(2));
      }
    }
    return analysis;
  }

  private Record record(Cursor c) {
    Record r = new Record();
    r.id = s(c, "id");
    r.key = s(c, "nkey");
    r.pkg = s(c, "pkg");
    r.app = s(c, "app");
    r.title = s(c, "title");
    r.text = s(c, "body");
    r.note = s(c, "note");
    r.posted = l(c, "posted");
    r.firstSeen = l(c, "first_seen");
    r.updated = l(c, "updated");
    r.removed = l(c, "removed");
    r.reason = (int) l(c, "reason");
    r.favorite = l(c, "favorite") == 1;
    r.active = l(c, "active") == 1;
    r.versions = (int) l(c, "version_count");
    r.imageCount=(int)l(c,"image_count"); r.imageStatus=s(c,"image_status");
    return r;
  }

  private static String s(Cursor c, String n) {
    return c.getString(c.getColumnIndexOrThrow(n));
  }

  private static long l(Cursor c, String n) {
    return c.getLong(c.getColumnIndexOrThrow(n));
  }

  public long[] counts() {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT COUNT(*), COALESCE(SUM(CASE WHEN first_seen>=? THEN 1 ELSE 0 END),0),"
                    + " COALESCE(SUM(favorite),0) FROM records",
                new String[] {Long.toString(todayStart())})) {
      c.moveToFirst();
      return new long[] {c.getLong(0), c.getLong(1), c.getLong(2)};
    }
  }

  public List<String[]> apps() {
    List<String[]> apps = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT pkg,MAX(app),COUNT(*) FROM records GROUP BY pkg ORDER BY COUNT(*) DESC",
                null)) {
      while (c.moveToNext())
        apps.add(new String[] {c.getString(0), c.getString(1), c.getString(2)});
    }
    // Preserve exclusions even after all records for that app are cleared.
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT name FROM settings WHERE name LIKE 'exclude:%' AND value='1'", null)) {
      while (c.moveToNext()) {
        String pkg = c.getString(0).substring(8);
        if (apps.stream().noneMatch(a -> a[0].equals(pkg))) apps.add(new String[] {pkg, pkg, "0"});
      }
    }
    return apps;
  }

  public JSONArray versions(String id) throws Exception {
    JSONArray result = new JSONArray();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT * FROM versions WHERE record_id=? ORDER BY time DESC,rowid DESC", new String[] {id})) {
      while (c.moveToNext())
        result.put(
            new JSONObject()
                .put("id", s(c, "id"))
                .put("time", l(c, "time"))
                .put("title", s(c, "title"))
                .put("body", s(c, "body"))
                .put("image_status",s(c,"image_status"))
                .put("images",new JSONArray(imageHashes("version_images","version_id",s(c,"id")))));
    }
    return result;
  }

  public void favorite(String id, boolean value) {
    ContentValues v = new ContentValues();
    v.put("favorite", value ? 1 : 0);
    getWritableDatabase().update("records", v, "id=?", new String[] {id});
  }

  public void note(String id, String note) {
    ContentValues v = new ContentValues();
    v.put("note", note);
    getWritableDatabase().update("records", v, "id=?", new String[] {id});
  }

  private int removeRecords(String where,String[] args) {
    SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
    try { int count=db.delete("records",where,args); pruneImages(); db.setTransactionSuccessful(); return count; }
    finally { db.endTransaction(); }
  }
  public void delete(String id) { removeRecords("id=?",new String[]{id}); }
  public int cleanup(int days) {
    return days<=0?0:removeRecords("favorite=0 AND first_seen<?",new String[]{Long.toString(System.currentTimeMillis()-days*86400000L)});
  }
  public void clearUnstarred() { removeRecords("favorite=0",null); }

  public static long todayStart() {
    Calendar c = Calendar.getInstance();
    c.set(Calendar.HOUR_OF_DAY, 0);
    c.set(Calendar.MINUTE, 0);
    c.set(Calendar.SECOND, 0);
    c.set(Calendar.MILLISECOND, 0);
    return c.getTimeInMillis();
  }

  public JSONObject exportBackup() throws Exception {
    JSONObject root =
        new JSONObject()
            .put("format", "HuangNotify")
            .put("version", 2)
            .put("created", System.currentTimeMillis());
    JSONArray rows = new JSONArray();
    int bytesEstimate = 0, totalVersions = 0;
    SQLiteDatabase db = getReadableDatabase();
    db.beginTransaction();
    try {
      try (Cursor c = db.rawQuery("SELECT * FROM records ORDER BY first_seen", null)) {
        while (c.moveToNext()) {
          JSONObject o = new JSONObject();
          for (String n : new String[] {"id", "nkey", "pkg", "app", "title", "body", "note"})
            o.put(n, s(c, n));
          for (String n :
              new String[] {"posted", "first_seen", "updated", "removed", "reason", "favorite"})
            o.put(n, l(c, n));
          o.put("image_status",s(c,"image_status")).put("images",recordImages(s(c,"id")));
          JSONArray history = versions(s(c, "id"));
          o.put("versions", history);
          totalVersions += history.length();
          bytesEstimate += o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
          if (bytesEstimate > BackupCrypto.MAX_BYTES
              || rows.length() >= 100000
              || totalVersions > BackupArchive.MAX_VERSIONS) throw new IllegalArgumentException("備份資料超過 20 MB 或筆數上限，請先整理紀錄");
          rows.put(o);
        }
      }
      JSONObject settings = new JSONObject();
      try (Cursor c =
          db.rawQuery(
              "SELECT name,value FROM settings WHERE name='retention' OR name='save_images' OR name LIKE 'exclude:%'",
              null)) {
        while (c.moveToNext()) settings.put(c.getString(0), c.getString(1));
      }
      JSONArray pictures=new JSONArray();
      try(Cursor c=db.rawQuery("SELECT * FROM images i WHERE EXISTS(SELECT 1 FROM record_images r WHERE r.image_hash=i.hash) OR EXISTS(SELECT 1 FROM version_images v WHERE v.image_hash=i.hash)",null)) {
        while(c.moveToNext()) {
          byte[] data=c.getBlob(c.getColumnIndexOrThrow("data"));
          bytesEstimate+=((data.length+2)/3)*4+200;
          if(bytesEstimate>BackupCrypto.MAX_BYTES)throw new IllegalArgumentException("含圖片的備份超過 20 MB，請先整理紀錄");
          pictures.put(new JSONObject().put("sha256",s(c,"hash")).put("mime","image/png").put("width",l(c,"width")).put("height",l(c,"height")).put("data",java.util.Base64.getEncoder().encodeToString(data)));
        }
      }
      root.put("settings", settings).put("records", rows).put("images",pictures);
      if(root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>BackupCrypto.MAX_BYTES)throw new IllegalArgumentException("含圖片的備份超過 20 MB，請先整理紀錄");
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    return root;
  }

  public int[] restore(JSONObject root, boolean restoreSettings) throws Exception {
    BackupFormat.validate(root); // Validate everything before the first write.
    JSONArray rows = root.getJSONArray("records");
    boolean v2=root.getInt("version")==2;
    SQLiteDatabase db = getWritableDatabase();
    int added = 0, skipped = 0, mergedVersions = 0;
    db.beginTransaction();
    try {
      if(v2) {
        JSONArray pictures=root.getJSONArray("images");
        for(int i=0;i<pictures.length();i++) {
          JSONObject p=pictures.getJSONObject(i);
          byte[] bytes=java.util.Base64.getDecoder().decode(p.getString("data"));
          NotificationImages.Attachment image=new NotificationImages.Attachment(bytes,p.getInt("width"),p.getInt("height"));
          // Existing content-addressed blobs must be identical; never replace shared data.
          byte[] old=image(image.hash); if(old!=null && !java.util.Arrays.equals(old,bytes))throw new IllegalArgumentException("圖片識別碼衝突");
          putImages(new NotificationImages.Result(java.util.Collections.singletonList(image),"saved"));
        }
      }
      for (int i = 0; i < rows.length(); i++) {
        JSONObject o = rows.getJSONObject(i);
        String id = o.getString("id");
        boolean exists;
        try (Cursor c = db.rawQuery("SELECT pkg,nkey FROM records WHERE id=?", new String[] {id})) {
          exists = c.moveToFirst();
          if (exists && (!c.getString(0).equals(o.getString("pkg")) || !c.getString(1).equals(o.getString("nkey"))))
            throw new IllegalArgumentException("通知識別碼衝突，未匯入任何資料");
        }
        if (!exists) {
          ContentValues v = new ContentValues();
          for (String n : new String[] {"id", "nkey", "pkg", "app", "title", "body", "note"})
            v.put(n, o.getString(n));
          for (String n :
              new String[] {"posted", "first_seen", "updated", "removed", "reason", "favorite"})
            v.put(n, o.getLong(n));
          // Imported notifications are historical; never attach them to a live listener key.
          v.put("active", 0);
          String status=v2?o.getString("image_status"):"none"; List<String> hashes=v2?jsonHashes(o.getJSONArray("images")):java.util.Collections.emptyList();
          v.put("image_status",status);
          v.put("hash", NotificationImages.fingerprint(o.getString("title"),o.getString("body"),hashes,status));
          db.insertOrThrow("records", null, v);
          if(v2)linkImages("record_images","record_id",id,jsonHashes(o.getJSONArray("images")));
          added++;
        } else skipped++;
        JSONArray versions = o.getJSONArray("versions");
        for (int j = 0; j < versions.length(); j++) {
          JSONObject x = versions.getJSONObject(j);
          try (Cursor c=db.rawQuery("SELECT record_id,time,title,body,image_status FROM versions WHERE id=?",new String[]{x.getString("id")})) {
            if (c.moveToFirst()) {
              if (!id.equals(c.getString(0)) || x.getLong("time")!=c.getLong(1)
                  || !x.getString("title").equals(c.getString(2)) || !x.getString("body").equals(c.getString(3)))
                throw new IllegalArgumentException("更新歷程識別碼衝突，未匯入任何資料");
              if(v2 && (!x.getString("image_status").equals(c.getString(4)) || !jsonHashes(x.getJSONArray("images")).equals(imageHashes("version_images","version_id",x.getString("id")))))
                throw new IllegalArgumentException("更新歷程圖片衝突，未匯入任何資料");
              continue;
            }
          }
          ContentValues v = new ContentValues();
          v.put("id", x.getString("id"));
          v.put("record_id", id);
          v.put("time", x.getLong("time"));
          v.put("title", x.getString("title"));
          v.put("body", x.getString("body"));
          String status=v2?x.getString("image_status"):"none"; List<String> hashes=v2?jsonHashes(x.getJSONArray("images")):java.util.Collections.emptyList();
          v.put("image_status",status);
          v.put("hash", NotificationImages.fingerprint(x.getString("title"),x.getString("body"),hashes,status));
          db.insertOrThrow("versions", null, v);
          linkImages("version_images","version_id",x.getString("id"),hashes);
          if (exists) mergedVersions++;
        }
      }
      pruneImages();
      if (restoreSettings) {
        JSONObject settings = root.getJSONObject("settings");
        java.util.Iterator<String> names = settings.keys();
        while (names.hasNext()) {
          String n = names.next();
          putSetting(n, settings.getString(n));
        }
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    return new int[] {added, skipped, mergedVersions};
  }
  private static List<String> jsonHashes(JSONArray array) throws Exception {
    List<String> hashes=new ArrayList<>(); for(int i=0;i<array.length();i++)hashes.add(array.getString(i)); return hashes;
  }

  /** Called on Store.IO: checkpoint and hold the writer lock for the short file-copy phase. */
  void snapshot(java.io.File target,BackupTask task)throws Exception {
    SQLiteDatabase db=getWritableDatabase();task.phase("整理資料庫",0);task.check();
    try(Cursor c=db.rawQuery("PRAGMA wal_checkpoint(FULL)",null)) {
      if(!c.moveToFirst() || c.getInt(0)!=0 || c.getInt(1)!=c.getInt(2))throw new BackupProblem("資料庫正忙碌，請稍後再備份");
    }
    task.phase("複製一致性快照",databaseFile.length());db.beginTransaction();
    try(java.io.InputStream in=new java.io.BufferedInputStream(new java.io.FileInputStream(databaseFile));java.io.OutputStream out=new java.io.BufferedOutputStream(new java.io.FileOutputStream(target))) {
      BackupFiles.copy(in,out,Long.MAX_VALUE,task);db.setTransactionSuccessful();
    } finally {db.endTransaction();}
  }
  private static ContentValues importValues(Cursor c,boolean record) {
    ContentValues v=new ContentValues();for(String name:record?BackupArchive.TEXT:new String[]{"id","title","body"})v.put(name,s(c,name));
    for(String name:record?BackupArchive.TIMES:new String[]{"time"})v.put(name,l(c,name));v.put("image_status",s(c,"image_status"));return v;
  }
  private static String importedHash(Cursor c,List<String> refs){return NotificationImages.fingerprint(s(c,"title"),s(c,"body"),refs,s(c,"image_status"));}
  int[] restoreStage(BackupArchive.Stage stage,boolean restoreSettings,BackupTask task)throws Exception {
    SQLiteDatabase db=getWritableDatabase();int added=0,skipped=0,merged=0;task.phase("合併紀錄",0);task.check();db.beginTransaction();
    try {
      try(Cursor images=stage.db.rawQuery("SELECT * FROM images",null)) {
        while(images.moveToNext()) {task.check();String hash=s(images,"hash");byte[] bytes=images.getBlob(images.getColumnIndexOrThrow("data")),existing=image(hash);
          if(existing!=null&&!java.util.Arrays.equals(existing,bytes))throw new BackupProblem("圖片識別碼衝突，未匯入任何資料");
          if(existing==null){ContentValues v=new ContentValues();v.put("hash",hash);v.put("data",bytes);v.put("width",l(images,"width"));v.put("height",l(images,"height"));db.insertOrThrow("images",null,v);}
        }
      }
      try(Cursor records=stage.db.rawQuery("SELECT * FROM records ORDER BY ordinal",null)) {
        while(records.moveToNext()) {
          task.check();String id=s(records,"id"),ordinal=Long.toString(l(records,"ordinal"));boolean exists;
          List<String> refs=stage.refs("r",ordinal);
          try(Cursor old=db.rawQuery("SELECT pkg,nkey,image_status,title,body,updated FROM records WHERE id=?",new String[]{id})) {
            exists=old.moveToFirst();if(exists&&(!old.getString(0).equals(s(records,"pkg"))||!old.getString(1).equals(s(records,"nkey"))))throw new BackupProblem("通知識別碼衝突，未匯入任何資料");
            if(exists&&"cleared".equals(old.getString(2))&&!refs.isEmpty()&&old.getString(3).equals(s(records,"title"))&&old.getString(4).equals(s(records,"body"))&&old.getLong(5)==l(records,"updated")) {
              ContentValues v=new ContentValues();v.put("image_status",s(records,"image_status"));v.put("hash",importedHash(records,refs));db.update("records",v,"id=?",new String[]{id});linkImages("record_images","record_id",id,refs);
            }
          }
          if(!exists) {ContentValues v=importValues(records,true);v.put("active",0);v.put("hash",importedHash(records,refs));db.insertOrThrow("records",null,v);linkImages("record_images","record_id",id,refs);added++;}else skipped++;
          try(Cursor versions=stage.db.rawQuery("SELECT * FROM versions WHERE owner=? ORDER BY time ASC,seq DESC",new String[]{ordinal})) {
            while(versions.moveToNext()) {
              task.check();
              String vid=s(versions,"id"), status=s(versions,"image_status");
              List<String> vrefs="saved".equals(status)||"partial".equals(status)
                  ?stage.refs("v",vid):java.util.Collections.emptyList();
              boolean found;
              try(Cursor old=db.rawQuery("SELECT record_id,time,title,body,image_status FROM versions WHERE id=?",new String[]{vid})) {
                found=old.moveToFirst();if(found) {
                  if(!id.equals(old.getString(0))||old.getLong(1)!=l(versions,"time")||!old.getString(2).equals(s(versions,"title"))||!old.getString(3).equals(s(versions,"body")))throw new BackupProblem("更新歷程識別碼衝突，未匯入任何資料");
                  if(stage.format>=2&&(!status.equals(old.getString(4))||!vrefs.equals(imageHashes("version_images","version_id",vid)))) {
                    if("cleared".equals(old.getString(4))&&!vrefs.isEmpty()) {ContentValues v=new ContentValues();v.put("image_status",s(versions,"image_status"));v.put("hash",importedHash(versions,vrefs));db.update("versions",v,"id=?",new String[]{vid});linkImages("version_images","version_id",vid,vrefs);merged++;}
                    else throw new BackupProblem("更新歷程圖片衝突，未匯入任何資料");
                  }
                }
              }
              if(!found) {ContentValues v=importValues(versions,false);v.put("record_id",id);v.put("hash",importedHash(versions,vrefs));db.insertOrThrow("versions",null,v);linkImages("version_images","version_id",vid,vrefs);if(exists)merged++;}
            }
          }
        }
      }
      if(restoreSettings)try(Cursor c=stage.db.rawQuery("SELECT name,value FROM settings",null)){while(c.moveToNext()){task.check();putSetting(c.getString(0),c.getString(1));}}
      task.check();pruneImages();db.setTransactionSuccessful();return new int[]{added,skipped,merged};
    } finally {db.endTransaction();}
  }
  long[] imageUsage() {try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*),COALESCE(SUM(length(data)),0) FROM images WHERE EXISTS(SELECT 1 FROM record_images r WHERE r.image_hash=images.hash) OR EXISTS(SELECT 1 FROM version_images v WHERE v.image_hash=images.hash)",null)){c.moveToFirst();return new long[]{c.getLong(0),c.getLong(1)};}}

  long databaseBytes() {
    String path = databaseFile.getPath();
    return databaseFile.length() + new java.io.File(path + "-wal").length()
        + new java.io.File(path + "-shm").length();
  }

  static final class StorageUsage {
    final long totalBytes, notificationBytes, historyBytes, historyCount, imageBytes, imageCount;

    StorageUsage(long totalBytes, long notificationBytes, long historyBytes, long historyCount,
        long imageBytes, long imageCount) {
      this.totalBytes = totalBytes;
      this.notificationBytes = notificationBytes;
      this.historyBytes = historyBytes;
      this.historyCount = historyCount;
      this.imageBytes = imageBytes;
      this.imageCount = imageCount;
    }

    long otherBytes() {
      return Math.max(0, totalBytes - notificationBytes - historyBytes - imageBytes);
    }
  }

  StorageUsage storageUsage() {
    SQLiteDatabase db = getReadableDatabase();
    long notificationBytes;
    // These columns contain text; casting to BLOB counts bytes, including multibyte characters.
    try (Cursor notifications = db.rawQuery("SELECT COALESCE(SUM(length(CAST(id AS BLOB))"
        + " + length(CAST(nkey AS BLOB)) + length(CAST(pkg AS BLOB)) + length(CAST(app AS BLOB))"
        + " + length(CAST(title AS BLOB)) + length(CAST(body AS BLOB)) + length(CAST(note AS BLOB))"
        + " + length(CAST(hash AS BLOB)) + length(CAST(image_status AS BLOB))),0) FROM records", null)) {
      notifications.moveToFirst();
      notificationBytes = notifications.getLong(0);
    }
    try (Cursor history = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(length(CAST(id AS BLOB))"
        + " + length(CAST(record_id AS BLOB)) + length(CAST(title AS BLOB)) + length(CAST(body AS BLOB))"
        + " + length(CAST(hash AS BLOB)) + length(CAST(image_status AS BLOB))),0) FROM versions", null)) {
      history.moveToFirst();
      long[] images = imageUsage();
      return new StorageUsage(databaseBytes(), notificationBytes, history.getLong(1), history.getLong(0),
          images[1], images[0]);
    }
  }

  long[] imageCleanupPreview(boolean includeFavorites){
    SQLiteDatabase db=getReadableDatabase();String target=includeFavorites?"1=1":"r.favorite=0";
    long count;try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM records r WHERE "+target+" AND (EXISTS(SELECT 1 FROM record_images i WHERE i.record_id=r.id) OR EXISTS(SELECT 1 FROM versions v JOIN version_images i ON i.version_id=v.id WHERE v.record_id=r.id))",null)){c.moveToFirst();count=c.getLong(0);}
    String candidates="EXISTS(SELECT 1 FROM record_images x JOIN records r ON r.id=x.record_id WHERE x.image_hash=i.hash AND "+target+") OR EXISTS(SELECT 1 FROM version_images x JOIN versions v ON v.id=x.version_id JOIN records r ON r.id=v.record_id WHERE x.image_hash=i.hash AND "+target+")";
    String retained=includeFavorites?"0":"EXISTS(SELECT 1 FROM record_images x JOIN records r ON r.id=x.record_id WHERE x.image_hash=i.hash AND r.favorite=1) OR EXISTS(SELECT 1 FROM version_images x JOIN versions v ON v.id=x.version_id JOIN records r ON r.id=v.record_id WHERE x.image_hash=i.hash AND r.favorite=1)";
    try(Cursor c=db.rawQuery("SELECT COUNT(*),COALESCE(SUM(CASE WHEN ("+retained+") THEN 0 ELSE length(data) END),0) FROM images i WHERE ("+candidates+")",null)){c.moveToFirst();return new long[]{count,c.getLong(0),c.getLong(1)};}
  }
  long clearImages(boolean includeFavorites) {
    SQLiteDatabase db=getWritableDatabase();long before=imageUsage()[1];db.beginTransaction();
    try {
      String condition=includeFavorites?"1=1":"favorite=0";
      for(String table:new String[]{"records","versions"}) {
        String links=table.equals("records")?"record_images":"version_images",key=table.equals("records")?"record_id":"version_id";
        String target=table.equals("records")?condition:"record_id IN (SELECT id FROM records WHERE "+condition+")";
        try(Cursor c=db.rawQuery("SELECT id,title,body FROM "+table+" t WHERE "+target+" AND EXISTS(SELECT 1 FROM "+links+" i WHERE i."+key+"=t.id)",null)) {
          while(c.moveToNext()) {ContentValues v=new ContentValues();v.put("image_status","cleared");v.put("hash",NotificationImages.fingerprint(c.getString(1),c.getString(2),java.util.Collections.emptyList(),"cleared"));db.update(table,v,"id=?",new String[]{c.getString(0)});}
        }
        db.delete(links,key+" IN (SELECT id FROM "+table+" WHERE "+target+")",null);
      }
      pruneImages();db.setTransactionSuccessful();
    } finally {db.endTransaction();}
    return before-imageUsage()[1];
  }
  void diagnostic(String code,String pkg) {
    if("0".equals(setting("diagnostics","1")))return;
    ContentValues v=new ContentValues();long now=System.currentTimeMillis();v.put("time",now);v.put("code",code);v.put("pkg",pkg==null?"":pkg.substring(0,Math.min(255,pkg.length())));
    SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
    try{db.insertOrThrow("diagnostics",null,v);
      db.execSQL("DELETE FROM diagnostics WHERE id NOT IN(SELECT id FROM diagnostics ORDER BY id DESC LIMIT 200)");
      if(code.equals("received")||code.equals("replayed"))putSetting("diagnostic_received",Long.toString(now));
      if(code.equals("saved"))putSetting("diagnostic_saved",Long.toString(now));
      db.setTransactionSuccessful();
    }finally{db.endTransaction();}
  }
  JSONArray diagnosticEvents()throws Exception {
    JSONArray rows=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT time,code,pkg,id FROM diagnostics ORDER BY id DESC",null)){while(c.moveToNext())rows.put(new JSONObject().put("id",c.getLong(3)).put("time",c.getLong(0)).put("code",c.getString(1)).put("package",c.getString(2)));}return rows;
  }
  long diagnosticSequence(){try(Cursor c=getReadableDatabase().rawQuery("SELECT seq FROM sqlite_sequence WHERE name='diagnostics'",null)){return c.moveToFirst()?c.getLong(0):0;}}
  void clearDiagnostics(){getWritableDatabase().delete("diagnostics",null,null);putSetting("diagnostic_received","0");putSetting("diagnostic_saved","0");}

}
