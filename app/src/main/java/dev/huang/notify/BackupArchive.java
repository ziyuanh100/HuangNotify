package dev.huang.notify;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.JsonReader;
import android.util.JsonWriter;
import android.util.JsonToken;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import org.json.*;

/** Streaming JSON v3. Import is staged on disk and fully validated before touching live records. */
final class BackupArchive {
  static final String[] TEXT={"id","nkey","pkg","app","title","body","note"};
  static final String[] TIMES={"posted","first_seen","updated","removed","reason","favorite"};
  static final int MAX_RECORDS=100000,MAX_VERSIONS=200000,MAX_IMAGES=100000;
  static List<String> refs(SQLiteDatabase db,String table,String column,String id) {
    List<String> result=new ArrayList<>();
    try(Cursor c=db.rawQuery("SELECT image_hash FROM "+table+" WHERE "+column+"=? ORDER BY position",new String[]{id})){while(c.moveToNext())result.add(c.getString(0));}return result;
  }
  private static String text(Cursor c,String name){return c.getString(c.getColumnIndexOrThrow(name));}
  private static long number(Cursor c,String name){return c.getLong(c.getColumnIndexOrThrow(name));}
  private static void imageFields(JsonWriter w,String status,List<String> refs) throws IOException {
    w.name("image_status").value(status).name("images").beginArray();for(String hash:refs)w.value(hash);w.endArray();
  }
  static void write(SQLiteDatabase db,OutputStream destination,BackupTask task) throws Exception {
    task.phase("建立備份",0);int records=0,versions=0,images=0;
    try(JsonWriter w=new JsonWriter(new OutputStreamWriter(new LimitedOutput(destination,task),StandardCharsets.UTF_8))) {
      w.beginObject().name("format").value("HuangNotify").name("version").value(3).name("created").value(System.currentTimeMillis());
      w.name("records").beginArray();
      try(Cursor c=db.rawQuery("SELECT * FROM records ORDER BY first_seen,id",null)) {
        while(c.moveToNext()) {
          task.check();if(++records>MAX_RECORDS)throw invalid("通知筆數超過上限");w.beginObject();String id=text(c,"id");
          for(String name:TEXT)w.name(name).value(text(c,name));for(String name:TIMES)w.name(name).value(number(c,name));
          imageFields(w,text(c,"image_status"),refs(db,"record_images","record_id",id));w.name("versions").beginArray();
          try(Cursor v=db.rawQuery("SELECT * FROM versions WHERE record_id=? ORDER BY time DESC,rowid DESC",new String[]{id})) {
            while(v.moveToNext()) {
              task.check();if(++versions>MAX_VERSIONS)throw invalid("更新歷程超過上限");w.beginObject();
              for(String name:new String[]{"id","title","body"})w.name(name).value(text(v,name));w.name("time").value(number(v,"time"));
              imageFields(w,text(v,"image_status"),refs(db,"version_images","version_id",text(v,"id")));w.endObject();
            }
          }
          w.endArray().endObject();
        }
      }
      w.endArray().name("images").beginArray();
      try(Cursor c=db.rawQuery("SELECT * FROM images i WHERE EXISTS(SELECT 1 FROM record_images r WHERE r.image_hash=i.hash) OR EXISTS(SELECT 1 FROM version_images v WHERE v.image_hash=i.hash)",null)) {
        while(c.moveToNext()) {
          task.check();if(++images>MAX_IMAGES)throw invalid("圖片筆數超過上限");byte[] data=c.getBlob(c.getColumnIndexOrThrow("data"));
          w.beginObject().name("sha256").value(text(c,"hash")).name("mime").value("image/png").name("width").value(number(c,"width")).name("height").value(number(c,"height")).name("data").value(Base64.getEncoder().encodeToString(data)).endObject();
        }
      }
      w.endArray().name("settings").beginObject();
      try(Cursor c=db.rawQuery("SELECT name,value FROM settings WHERE name='retention' OR name='save_images' OR name LIKE 'exclude:%'",null)) {
        while(c.moveToNext()){task.check();w.name(c.getString(0)).value(c.getString(1));}
      }
      task.check();w.endObject().endObject();w.flush();
    }
  }
  private static IllegalArgumentException invalid(String message){return new BackupProblem(message);}
  static final class LimitedOutput extends FilterOutputStream {
    long count;final BackupTask task;
    LimitedOutput(OutputStream out,BackupTask task){super(out);this.task=task;}
    public void write(int b)throws IOException{byte[] one={(byte)b};write(one,0,1);}
    public void write(byte[] bytes,int offset,int length)throws IOException{task.check();if(count+length>BackupStreamCrypto.MAX_BYTES)throw invalid("備份超過 100 MiB，請先整理紀錄");out.write(bytes,offset,length);count+=length;task.progress(count);}
  }
  /** Limits the lexical size and depth before JsonReader can allocate a giant token. */
  static final class TokenReader extends FilterReader {
    boolean quoted,escaped;int token,depth;
    TokenReader(Reader in){super(in);}
    public int read(char[] b,int off,int len)throws IOException{int n=super.read(b,off,len);for(int i=off;i<off+Math.max(0,n);i++){char c=b[i];if(quoted){if(++token>2200000)throw invalid("備份欄位過大");if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"'){quoted=false;token=0;}}else if(c=='"'){quoted=true;token=0;}else if(c=='{'||c=='['){if(++depth>32)throw invalid("備份巢狀層級過深");token=0;}else if(c=='}'||c==']'){depth--;token=0;}else if(Character.isWhitespace(c)||c==','||c==':')token=0;else if(++token>128)throw invalid("備份數值欄位過長");}return n;}
    public int read()throws IOException{char[] one=new char[1];return read(one,0,1)==-1?-1:one[0];}
  }
  static final class Stage implements AutoCloseable {
    final SQLiteDatabase db;final File file;long created;int format,records,versions,images;
    Stage(File file){this.file=file;db=SQLiteDatabase.openOrCreateDatabase(file,null);db.execSQL("PRAGMA synchronous=NORMAL");
      db.execSQL("CREATE TABLE records(ordinal INTEGER UNIQUE,id TEXT PRIMARY KEY,nkey TEXT,pkg TEXT,app TEXT,title TEXT,body TEXT,note TEXT,posted INTEGER,first_seen INTEGER,updated INTEGER,removed INTEGER,reason INTEGER,favorite INTEGER,image_status TEXT,has_images INTEGER,has_status INTEGER)");
      db.execSQL("CREATE TABLE versions(id TEXT PRIMARY KEY,owner INTEGER,seq INTEGER,time INTEGER,title TEXT,body TEXT,image_status TEXT,has_images INTEGER,has_status INTEGER)");
      db.execSQL("CREATE INDEX versions_owner ON versions(owner)");
      db.execSQL("CREATE TABLE images(hash TEXT PRIMARY KEY,data BLOB,width INTEGER,height INTEGER)");
      db.execSQL("CREATE TABLE refs(kind TEXT,owner TEXT,position INTEGER,hash TEXT,PRIMARY KEY(kind,owner,position))");db.execSQL("CREATE INDEX refs_hash ON refs(hash)");
      db.execSQL("CREATE TABLE settings(name TEXT PRIMARY KEY,value TEXT)");
    }
    List<String> refs(String kind,String owner){List<String> result=new ArrayList<>();try(Cursor c=db.rawQuery("SELECT hash FROM refs WHERE kind=? AND owner=? ORDER BY position",new String[]{kind,owner})){while(c.moveToNext())result.add(c.getString(0));}return result;}
    public void close(){if(db.isOpen())db.close();}
  }
  static Stage read(File json,File database,BackupTask task)throws Exception {
    if(json.length()>BackupStreamCrypto.MAX_BYTES)throw invalid("備份超過 100 MiB");
    Stage stage=new Stage(database);boolean success=false;task.phase("檢查備份",json.length());
    try(InputStream source=new BufferedInputStream(new FileInputStream(json));JsonReader r=new JsonReader(new TokenReader(new InputStreamReader(new FilterInputStream(source){long count;public int read(byte[] b,int o,int n)throws IOException{task.check();int got=super.read(b,o,n);if(got>0){count+=got;task.progress(count);}return got;}},StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))))) {
      stage.db.beginTransaction();
      try {
        Set<String> seen=new HashSet<>();r.beginObject();
        while(r.hasNext()) {
          task.check();String name=r.nextName();if(!seen.add(name))throw invalid("備份含重複欄位");
          switch(name) {
            case "format":if(!"HuangNotify".equals(string(r)))throw invalid("不支援的備份格式");break;
            case "version":long version=integer(r);if(version<1||version>3)throw invalid("不支援的備份版本");stage.format=(int)version;break;
            case "created":stage.created=integer(r);if(stage.created<0||stage.created>32503680000000L)throw invalid("備份日期錯誤");break;
            case "records":readRecords(r,stage,task);break;
            case "images":readImages(r,stage,task);break;
            case "settings":readSettings(r,stage,task);break;
            default:throw invalid("未知的備份欄位");
          }
        }
        r.endObject();if(r.peek()!=JsonToken.END_DOCUMENT)throw invalid("備份含多餘內容");
        if(!seen.containsAll(Arrays.asList("format","version","created","records","settings")) || (stage.format>=2)!=seen.contains("images"))throw invalid("備份缺少必要欄位或版本不符");
        validateRelations(stage,task);stage.db.setTransactionSuccessful();success=true;
      } finally {stage.db.endTransaction();}
      return stage;
    } finally {if(!success)stage.close();}
  }
  private static String string(JsonReader r)throws IOException{if(r.peek()!=JsonToken.STRING)throw invalid("備份文字欄位格式錯誤");return r.nextString();}
  private static long integer(JsonReader r)throws IOException {
    if(r.peek()!=JsonToken.NUMBER)throw invalid("備份整數欄位格式錯誤");String number=r.nextString();if(!number.matches("-?(0|[1-9][0-9]{0,18})"))throw invalid("備份數值不是整數");try{return Long.parseLong(number);}catch(NumberFormatException e){throw invalid("備份數值超出範圍");}
  }
  private static JSONArray readRefs(JsonReader r)throws Exception {
    JSONArray refs=new JSONArray();Set<String> seen=new HashSet<>();r.beginArray();while(r.hasNext()){if(refs.length()>=NotificationImages.MAX_IMAGES)throw invalid("每則通知圖片超過上限");String hash=string(r);if(!hash.matches("[0-9a-f]{64}")||!seen.add(hash))throw invalid("圖片引用錯誤或重複");refs.put(hash);}r.endArray();return refs;
  }
  private static JSONObject readObject(JsonReader r,Stage stage,BackupTask task,int owner,boolean record)throws Exception {
    JSONObject data=new JSONObject();Set<String> names=new HashSet<>();boolean history=false;r.beginObject();
    while(r.hasNext()) {
      task.check();String name=r.nextName();if(!names.add(name))throw invalid("通知含重複欄位");
      if(record && name.equals("versions")){readVersions(r,stage,task,owner);history=true;continue;}
      if(name.equals("images")){data.put(name,readRefs(r));continue;}
      if(name.equals("image_status") || Arrays.asList(record?TEXT:new String[]{"id","title","body"}).contains(name)){String value=string(r);if(value.length()>100000)throw invalid("通知文字超過上限");data.put(name,value);continue;}
      if(Arrays.asList(record?TIMES:new String[]{"time"}).contains(name)){data.put(name,integer(r));continue;}
      throw invalid("未知的通知欄位");
    }
    r.endObject();if(record&&!history)throw invalid("通知缺少更新歷程");return data;
  }
  private static void putRefs(Stage stage,JSONObject data,String kind,String owner)throws Exception {
    JSONArray refs=data.optJSONArray("images");if(refs==null)return;
    for(int i=0;i<refs.length();i++){ContentValues v=new ContentValues();v.put("kind",kind);v.put("owner",owner);v.put("position",i);v.put("hash",refs.getString(i));stage.db.insertOrThrow("refs",null,v);}
  }
  private static void readRecords(JsonReader r,Stage stage,BackupTask task)throws Exception {
    r.beginArray();while(r.hasNext()) {
      if(++stage.records>MAX_RECORDS)throw invalid("備份通知筆數超過上限");int ordinal=stage.records;
      JSONObject data=readObject(r,stage,task,ordinal,true);validateRow(data,true);
      ContentValues v=values(data,true);v.put("ordinal",ordinal);stage.db.insertOrThrow("records",null,v);putRefs(stage,data,"r",Integer.toString(ordinal));
    }r.endArray();
  }
  private static void readVersions(JsonReader r,Stage stage,BackupTask task,int owner)throws Exception {
    r.beginArray();while(r.hasNext()) {
      if(++stage.versions>MAX_VERSIONS)throw invalid("更新歷程數量超過上限");JSONObject data=readObject(r,stage,task,owner,false);validateRow(data,false);
      ContentValues v=values(data,false);v.put("owner",owner);v.put("seq",stage.versions);stage.db.insertOrThrow("versions",null,v);putRefs(stage,data,"v",data.getString("id"));
    }r.endArray();
  }
  static ContentValues values(JSONObject data,boolean record)throws Exception {
    ContentValues result=new ContentValues();for(String name:record?TEXT:new String[]{"id","title","body"})result.put(name,data.getString(name));
    for(String name:record?TIMES:new String[]{"time"})result.put(name,data.getLong(name));result.put("image_status",data.optString("image_status","none"));result.put("has_images",data.has("images")?1:0);result.put("has_status",data.has("image_status")?1:0);return result;
  }
  private static void validateRow(JSONObject data,boolean record)throws Exception {
    UUID.fromString(data.getString("id"));
    for(String name:record?TEXT:new String[]{"id","title","body"})if(!(data.get(name) instanceof String)||data.getString(name).length()>100000)throw invalid("通知文字格式錯誤");
    if(record&&(data.getString("pkg").trim().isEmpty()||data.getString("pkg").length()>255))throw invalid("來源 App 識別碼錯誤");
    for(String name:record?new String[]{"posted","first_seen","updated","removed"}:new String[]{"time"})BackupFormat.integer(data,name,0,32503680000000L);
    if(record){BackupFormat.integer(data,"reason",0,1000);BackupFormat.integer(data,"favorite",0,1);}
    if(data.has("image_status")&&(!(data.get("image_status") instanceof String)||!NotificationImages.STATUSES.contains(data.getString("image_status"))))throw invalid("圖片狀態錯誤");
  }
  private static void readImages(JsonReader r,Stage stage,BackupTask task)throws Exception {
    r.beginArray();while(r.hasNext()) {
      task.check();if(++stage.images>MAX_IMAGES)throw invalid("圖片數量超過上限");JSONObject data=new JSONObject();Set<String> seen=new HashSet<>();r.beginObject();
      while(r.hasNext()){String name=r.nextName();if(!seen.add(name))throw invalid("圖片含重複欄位");if(name.equals("width")||name.equals("height"))data.put(name,integer(r));else if(Arrays.asList("sha256","mime","data").contains(name))data.put(name,string(r));else throw invalid("未知的圖片欄位");}r.endObject();
      String hash=data.getString("sha256"),encoded=data.getString("data");
      if(!hash.matches("[0-9a-f]{64}")||!"image/png".equals(data.getString("mime"))||encoded.length()>((NotificationImages.MAX_IMAGE_BYTES+2)/3)*4)throw invalid("圖片格式或大小錯誤");
      int width=(int)BackupFormat.integer(data,"width",1,NotificationImages.MAX_EDGE),height=(int)BackupFormat.integer(data,"height",1,NotificationImages.MAX_EDGE);
      byte[] bytes=Base64.getDecoder().decode(encoded);NotificationImages.validate(bytes,hash,width,height);
      ContentValues v=new ContentValues();v.put("hash",hash);v.put("data",bytes);v.put("width",width);v.put("height",height);stage.db.insertOrThrow("images",null,v);
    }r.endArray();
  }
  private static void readSettings(JsonReader r,Stage stage,BackupTask task)throws Exception {
    r.beginObject();int count=0;while(r.hasNext()){task.check();if(++count>100000)throw invalid("備份設定過多");String name=r.nextName(),value=string(r);validateSetting(name,value);ContentValues v=new ContentValues();v.put("name",name);v.put("value",value);stage.db.insertOrThrow("settings",null,v);}r.endObject();
  }
  static void validateSetting(String name,String value){
    if(name.equals("retention")){if(!Arrays.asList("0","7","30","90","365").contains(value))throw invalid("保存期限錯誤");}
    else if(name.equals("save_images")||(name.startsWith("exclude:")&&name.length()>8&&name.length()<=263)){if(!value.equals("0")&&!value.equals("1"))throw invalid("備份設定值錯誤");}
    else throw invalid("未知的備份設定");
  }
  private static boolean exists(SQLiteDatabase db,String sql){try(Cursor c=db.rawQuery(sql,null)){return c.moveToFirst();}}
  private static void validateRelations(Stage s,BackupTask task)throws Exception {
    task.check();
    if(exists(s.db,"SELECT 1 FROM records r WHERE NOT EXISTS(SELECT 1 FROM versions v WHERE v.owner=r.ordinal) LIMIT 1"))throw invalid("通知缺少更新歷程");
    for(String table:new String[]{"records","versions"}) {
      String condition=s.format==1?"has_images!=0 OR has_status!=0":"has_images!=1 OR has_status!=1";
      if(exists(s.db,"SELECT 1 FROM "+table+" WHERE "+condition+" LIMIT 1"))throw invalid("圖片欄位與備份版本不符");
      if(s.format<3&&exists(s.db,"SELECT 1 FROM "+table+" WHERE image_status='cleared' LIMIT 1"))throw invalid("圖片清除狀態需要備份 v3");
      String kind=table.equals("records")?"r":"v",owner=table.equals("records")?"CAST(t.ordinal AS TEXT)":"t.id";
      if(exists(s.db,"SELECT 1 FROM "+table+" t WHERE (image_status IN ('saved','partial')) != EXISTS(SELECT 1 FROM refs f WHERE f.kind='"+kind+"' AND f.owner="+owner+") LIMIT 1"))throw invalid("圖片數量與狀態不符");
    }
    if(exists(s.db,"SELECT 1 FROM refs r WHERE NOT EXISTS(SELECT 1 FROM images i WHERE i.hash=r.hash) LIMIT 1"))throw invalid("備份缺少引用的圖片");
    if(exists(s.db,"SELECT 1 FROM images i WHERE NOT EXISTS(SELECT 1 FROM refs r WHERE r.hash=i.hash) LIMIT 1"))throw invalid("備份含未引用的圖片");
    task.check();
  }
}
