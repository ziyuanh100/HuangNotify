package dev.huang.notify;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
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

  private Store(Context context) {
    super(context, "notifications.db", null, 2);
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
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if(oldVersion<2)imageSchema(db);
  }

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

  public void capture(String key,String pkg,String app,String title,String body,long posted,NotificationImages.Result images) {
    if (excluded(pkg) || "1".equals(setting("paused", "0"))) return;
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
        return;
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
    List<String> args = new ArrayList<>();
    String where = "1=1";
    if (!query.trim().isEmpty()) {
      where +=
          " AND (title LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\' OR app LIKE ? ESCAPE '\\' OR"
              + " note LIKE ? ESCAPE '\\')";
      String term = EventPolicy.likePattern(query);
      for (int i = 0; i < 4; i++) args.add(term);
    }
    if (pkg != null) {
      where += " AND pkg=?";
      args.add(pkg);
    }
    if (favorite) where += " AND favorite=1";
    if (today) {
      where += " AND first_seen>=?";
      args.add(Long.toString(todayStart()));
    }
    List<Record> result = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT r.*, (SELECT COUNT(*) FROM versions v WHERE v.record_id=r.id) AS"
                    + " version_count, (SELECT COUNT(*) FROM record_images ri WHERE ri.record_id=r.id) AS image_count FROM records r WHERE "
                    + where
                    + " ORDER BY first_seen DESC LIMIT "
                    + limit,
                args.toArray(new String[0]))) {
      while (c.moveToNext()) result.add(record(c));
    }
    return result;
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
              || totalVersions > 200000) throw new IllegalArgumentException("備份資料超過 20 MB 或筆數上限，請先整理紀錄");
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

}
