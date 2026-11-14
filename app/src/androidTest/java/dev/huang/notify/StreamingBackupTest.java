package dev.huang.notify;

import android.test.AndroidTestCase;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

@SuppressWarnings("deprecation")
public class StreamingBackupTest extends AndroidTestCase {
  private Store store;private File dir;private int seq;
  @Override protected void setUp()throws Exception{super.setUp();store=Store.get(getContext());Store.IO.submit(()->{store.getWritableDatabase().delete("records",null,null);store.getWritableDatabase().delete("settings",null,null);store.clearDiagnostics();store.pruneImages();}).get();dir=new File(getContext().getCacheDir(),"stream-test-"+UUID.randomUUID());assertTrue(dir.mkdir());}
  @Override protected void tearDown()throws Exception{BackupFiles.erase(dir);Store.IO.submit(()->{store.getWritableDatabase().delete("records",null,null);store.getWritableDatabase().delete("settings",null,null);store.clearDiagnostics();store.pruneImages();}).get();super.tearDown();}
  private File file(String text)throws Exception{File f=new File(dir,"input"+(seq++)+".json");try(OutputStream out=new FileOutputStream(f)){out.write(text.getBytes(StandardCharsets.UTF_8));}return f;}
  private BackupArchive.Stage stage(JSONObject json)throws Exception{return BackupArchive.read(file(json.toString()),new File(dir,"stage"+(seq++)+".db"),new BackupTask());}
  private void capture(String key,NotificationImages.Attachment picture){store.capture(key,"stream.fixture","Fixture","私密標題","私密內容",1,picture==null?NotificationImages.Result.empty("none"):new NotificationImages.Result(Collections.singletonList(picture),"saved"));}
  public void testLegacyV1V2AndEncryptedHnbk1Import()throws Exception{
    capture("legacy",ImageStoreTest.picture(0xff146c59));JSONObject json=store.exportBackup();
    try(BackupArchive.Stage s=stage(json)){assertEquals(2,s.format);assertEquals(1,s.images);assertEquals(1,store.restoreStage(s,false,new BackupTask())[1]);}
    AutoBackup.Documents docs=new AutoBackup.Documents(getContext(),Uri.parse("content://dev.huang.notify.testdocs/tree/root"));String id=docs.create("legacy-test.hnbk");
    try{try(OutputStream out=docs.write(id)){out.write(BackupCrypto.encrypt(json.toString().getBytes(StandardCharsets.UTF_8),"password123".toCharArray()));}
      try(BackupFiles.Session s=BackupFiles.read(getContext(),Uri.parse(id),new BackupTask())){BackupFiles.unlock(s,"password123".toCharArray(),new BackupTask());assertEquals(1,s.stage.images);}
    }finally{docs.delete(id);}
    json.put("version",1);json.remove("images");for(int i=0;i<json.getJSONArray("records").length();i++){JSONObject row=json.getJSONArray("records").getJSONObject(i);row.remove("images");row.remove("image_status");JSONArray versions=row.getJSONArray("versions");for(int j=0;j<versions.length();j++){versions.getJSONObject(j).remove("images");versions.getJSONObject(j).remove("image_status");}}
    try(BackupArchive.Stage s=stage(json)){assertEquals(1,s.format);assertEquals(1,store.restoreStage(s,false,new BackupTask())[1]);}
  }
  public void testLargeExportRestoreOver20MiBAndDoesNotBlockCaptureAfterSnapshot()throws Exception{
    char[] chars=new char[90000];Arrays.fill(chars,'資');String text=new String(chars);
    Store.IO.submit(()->{for(int i=0;i<193;i++)store.capture("large"+i,"stream.fixture","Fixture","標題",text,i);}).get(30,TimeUnit.SECONDS);
    try(BackupFiles.Session session=AutoBackup.IO.submit(()->BackupFiles.export(getContext(),"password123".toCharArray(),new BackupTask())).get(120,TimeUnit.SECONDS)){
      assertTrue(session.ready.length()>99L*1024*1024);
      Store.IO.submit(()->store.capture("during-backup","stream.fixture","Fixture","new","after snapshot",51)).get(5,TimeUnit.SECONDS);
      try(InputStream in=new FileInputStream(session.ready);OutputStream out=new FileOutputStream(session.plain)){BackupStreamCrypto.decrypt(in,out,"password123".toCharArray(),new java.util.concurrent.atomic.AtomicBoolean(),n->{});}
      try(BackupArchive.Stage s=BackupArchive.read(session.plain,session.database,new BackupTask())){
        assertEquals(193,s.records);Store.IO.submit(()->store.getWritableDatabase().delete("records",null,null)).get();
        assertEquals(193,Store.IO.submit(()->store.restoreStage(s,false,new BackupTask())[0]).get().intValue());assertEquals(text,store.list("",null,false,false,100).get(0).text);
      }
    }
    File over=new File(dir,"over.json");try(RandomAccessFile f=new RandomAccessFile(over,"rw")){f.setLength(BackupStreamCrypto.MAX_BYTES+1);}
    try{BackupArchive.read(over,new File(dir,"over.db"),new BackupTask());fail();}catch(BackupProblem expected){}
    assertEquals(193,store.counts()[0]);
  }
  public void testMalformedInputsFailBeforeChangingLiveData()throws Exception{
    capture("seed",ImageStoreTest.picture(0xff146c59));JSONObject base=store.exportBackup();
    for(int mutation=0;mutation<9;mutation++){
      JSONObject json=new JSONObject(base.toString());JSONObject row=json.getJSONArray("records").getJSONObject(0);
      if(mutation==0)row.put("favorite","1");if(mutation==1)row.put("updated",1.5);if(mutation==2)row.put("images",new JSONArray().put(String.join("",Collections.nCopies(64,"0"))));
      if(mutation==3)json.getJSONArray("images").getJSONObject(0).put("data","AAAA");
      if(mutation==4)json.getJSONArray("records").put(new JSONObject(row.toString()));
      if(mutation==5)row.getJSONArray("versions").put(row.getJSONArray("versions").getJSONObject(0));
      if(mutation==6)row.put("image_status","none");
      if(mutation==7)row.put("versions",new JSONArray());
      if(mutation==8)json.put("version",99);
      try(BackupArchive.Stage ignored=stage(json)){fail("Accepted mutation "+mutation);}catch(Exception expected){}
      assertEquals(1,store.counts()[0]);assertEquals(1,store.imageUsage()[0]);
    }
    for(String text:new String[]{base.toString()+"{}",base.toString().replace("\"version\":2","\"version\":2,\"version\":2"),"{\"unknown\":"+String.join("",Collections.nCopies(40,"["))+"0"+String.join("",Collections.nCopies(40,"]"))+"}"}){
      try(BackupArchive.Stage ignored=BackupArchive.read(file(text),new File(dir,"bad"+(seq++)+".db"),new BackupTask())){fail();}catch(Exception expected){}
    }
    File utf=file(base.toString());try(RandomAccessFile out=new RandomAccessFile(utf,"rw")){out.seek(20);out.write(0xff);}
    try(BackupArchive.Stage ignored=BackupArchive.read(utf,new File(dir,"utf.db"),new BackupTask())){fail();}catch(Exception expected){}
    char[] huge=new char[2200001];Arrays.fill(huge,'x');try(Reader reader=new BackupArchive.TokenReader(new StringReader("\""+new String(huge)+"\""))){char[] b=new char[4096];while(reader.read(b)!=-1){}fail();}catch(BackupProblem expected){}
  }
  public void testConflictRollsBackInsertedRecordsAndImagesAndCancelRollsBack()throws Exception{
    capture("seed",ImageStoreTest.picture(0xff146c59));JSONObject json=store.exportBackup();JSONObject row=json.getJSONArray("records").getJSONObject(0),extra=new JSONObject(row.toString());
    extra.put("id",UUID.randomUUID().toString());extra.put("nkey","new");extra.getJSONArray("versions").getJSONObject(0).put("id",UUID.randomUUID().toString());json.put("records",new JSONArray().put(extra).put(row));
    row.getJSONArray("versions").getJSONObject(0).put("body","conflict");
    try(BackupArchive.Stage s=stage(json)){try{store.restoreStage(s,true,new BackupTask());fail();}catch(BackupProblem expected){}}
    assertEquals(1,store.counts()[0]);assertEquals("私密內容",store.list("",null,false,false,10).get(0).text);
    try(BackupArchive.Stage s=stage(store.exportBackup())){BackupTask cancelled=new BackupTask(new java.util.concurrent.atomic.AtomicBoolean(true));try{store.restoreStage(s,true,cancelled);fail();}catch(InterruptedIOException expected){}}
    assertEquals(1,store.counts()[0]);
  }
  public void testImageClearProtectsFavoritesAndBackupCanRehydrate()throws Exception{
    NotificationImages.Attachment picture=ImageStoreTest.picture(0xff146c59);capture("a",picture);capture("b",picture);List<Record> rows=store.list("",null,false,false,10);store.favorite(rows.get(0).id,true);store.note(rows.get(1).id,"保留備註");
    try(BackupArchive.Stage s=stage(store.exportBackup())){
      assertEquals(0,store.clearImages(false));assertNotNull(store.image(picture.hash));assertEquals(1,store.list("",null,true,false,10).get(0).imageCount);
      assertEquals(picture.data.length,store.clearImages(true));assertNull(store.image(picture.hash));assertEquals(2,store.counts()[0]);assertEquals(1,store.counts()[2]);
      store.restoreStage(s,false,new BackupTask());assertNotNull(store.image(picture.hash));for(Record r:store.list("",null,false,false,10))assertEquals(1,r.imageCount);
      store.clearImages(true);File output=new File(dir,"clear.json");try(OutputStream out=new FileOutputStream(output)){BackupArchive.write(store.getReadableDatabase(),out,new BackupTask());}
      try(BackupArchive.Stage cleared=BackupArchive.read(output,new File(dir,"clear.db"),new BackupTask())){assertEquals(3,cleared.format);assertEquals(0,cleared.images);}
    }
  }

  public void testStorageUsageCountsUtf8NotesAndSharedImages() throws Exception {
    NotificationImages.Attachment picture = ImageStoreTest.picture(0xff146c59);
    capture("first", picture);
    String record = store.list("", null, false, false, 1).get(0).id;
    Store.StorageUsage original = store.storageUsage();
    String note = "備註🙂";
    store.note(record, note);
    Store.StorageUsage annotated = store.storageUsage();
    assertEquals(note.getBytes(StandardCharsets.UTF_8).length,
        annotated.notificationBytes - original.notificationBytes);
    assertEquals(original.historyBytes, annotated.historyBytes);

    capture("second", picture);
    Store.StorageUsage shared = store.storageUsage();
    assertEquals(2, shared.historyCount);
    assertEquals(original.historyBytes * 2, shared.historyBytes);
    assertEquals(1, shared.imageCount);
    assertEquals(picture.data.length, shared.imageBytes);
    assertEquals(shared.totalBytes,
        shared.notificationBytes + shared.historyBytes + shared.imageBytes + shared.otherBytes());

    store.clearImages(true);
    Store.StorageUsage cleared = store.storageUsage();
    assertEquals(shared.historyCount, cleared.historyCount);
    assertEquals(0, cleared.imageBytes);
    assertEquals(0, cleared.imageCount);
  }

  public void testDiagnosticsBoundedPrivateAndCanBeDisabled()throws Exception{
    capture("secret-key",null);for(int i=0;i<210;i++)Diagnostics.record(store,"received","stream.fixture");Diagnostics.record(store,"saved","stream.fixture");
    JSONObject report=Diagnostics.report(store,getContext());assertEquals(200,report.getJSONArray("events").length());String serialized=report.toString();assertFalse(serialized.contains("私密"));assertFalse(serialized.contains("secret-key"));assertTrue(report.getLong("last_received")>0);assertTrue(report.getLong("last_saved")>0);
    store.putSetting("diagnostics","0");store.clearDiagnostics();Diagnostics.record(store,"received","stream.fixture");assertEquals(0,store.diagnosticEvents().length());assertEquals("0",store.setting("diagnostic_received","missing"));
  }
  public void testCancelledPrivateCopyCleansOperationFiles()throws Exception{
    BackupTask task=new BackupTask(new java.util.concurrent.atomic.AtomicBoolean(true));try{AutoBackup.IO.submit(()->BackupFiles.export(getContext(),null,task)).get(10,TimeUnit.SECONDS);fail();}catch(ExecutionException expected){assertTrue(expected.getCause() instanceof InterruptedIOException);}
    File root=new File(getContext().getCacheDir(),"backup-work");File[] children=root.listFiles();assertNotNull(children);assertEquals(0,children.length);
  }
}
