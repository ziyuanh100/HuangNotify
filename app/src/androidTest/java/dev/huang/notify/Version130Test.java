package dev.huang.notify;

import android.test.AndroidTestCase;
import android.content.ContentValues;
import android.net.Uri;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

@SuppressWarnings("deprecation")
public class Version130Test extends AndroidTestCase {
  Store s;File dir;
  @Override protected void setUp()throws Exception{super.setUp();s=Store.get(getContext());Store.IO.submit(()->{s.getWritableDatabase().delete("records",null,null);s.getWritableDatabase().delete("settings",null,null);s.pruneImages();s.clearDiagnostics();}).get();dir=new File(getContext().getCacheDir(),"v130-"+UUID.randomUUID());assertTrue(dir.mkdir());}
  @Override protected void tearDown()throws Exception{Store.IO.submit(()->{s.getWritableDatabase().delete("records",null,null);s.getWritableDatabase().delete("settings",null,null);s.pruneImages();s.clearDiagnostics();}).get();BackupFiles.erase(dir);super.tearDown();}
  Record capture(String key,String text,NotificationImages.Attachment image){s.capture(key,"v130.fixture","Fixture","標題",text,1,image==null?NotificationImages.Result.empty("none"):new NotificationImages.Result(Collections.singletonList(image),"saved"));for(Record row:s.list("",null,false,false,10))if(row.key.equals(key))return row;throw new AssertionError("Record missing");}
  public void testStreamingExportReadableBy120AndCachedChecksumRejectsMutation()throws Exception{
    NotificationImages.Attachment image=ImageStoreTest.picture(0xff146c59);Record r=capture("one","一",image);s.capture("one","v130.fixture","Fixture","標題","二",2,NotificationImages.Result.empty("none"));s.favorite(r.id,true);s.note(r.id,"保留備註");
    try(BackupFiles.Session file=AutoBackup.IO.submit(()->BackupFiles.export(getContext(),"password123".toCharArray(),new BackupTask())).get(30,TimeUnit.SECONDS)){
      assertFalse(file.plain.exists());assertNotNull(file.sha256);assertEquals(file.ready.length(),file.readyBytes);
      File json=new File(dir,"old-readable.json");try(InputStream in=new FileInputStream(file.ready);OutputStream out=new FileOutputStream(json)){Legacy12BackupStreamCrypto.decrypt(in,out,"password123".toCharArray(),new java.util.concurrent.atomic.AtomicBoolean(),n->{});}
      try(Legacy12BackupArchive.Stage stage=Legacy12BackupArchive.read(json,new File(dir,"old.db"),new BackupTask())){assertEquals(1,stage.records);assertEquals(2,stage.versions);assertEquals(1,stage.images);}
      AutoBackup.Documents d=new AutoBackup.Documents(getContext(),Uri.parse("content://dev.huang.notify.testdocs/tree/root"));String id=d.create("tampered-ready.hnbk");
      try{try(RandomAccessFile edit=new RandomAccessFile(file.ready,"rw")){edit.seek(45);edit.write(edit.read()^1);}try{BackupFiles.save(getContext(),file,Uri.parse(id),new BackupTask());fail("Mutated cached source was accepted");}catch(IOException expected){}}finally{d.delete(id);}
    }
  }
  public void testDateAndImagesCombineIncludingHistoricalImageAndStableOrdering()throws Exception{
    Record a=capture("a","needle",ImageStoreTest.picture(0xff146c59));s.capture("a","v130.fixture","Fixture","標題","needle",2,NotificationImages.Result.empty("none"));Record b=capture("b","needle",null);s.favorite(a.id,true);
    long[] range=RecordFilters.bounds("2026-11-01","2026-11-01",java.time.ZoneId.of("UTC"));ContentValues v=new ContentValues();v.put("first_seen",range[0]);s.getWritableDatabase().update("records",v,null,null);
    assertEquals(1,s.listFiltered("needle","v130.fixture",true,range[0],range[1],true,10).size());assertEquals(a.id,s.listFiltered("needle",null,false,range[0],range[1],true,10).get(0).id);
    v.put("first_seen",range[1]);s.getWritableDatabase().update("records",v,"id=?",new String[]{a.id});assertEquals(0,s.listFiltered("needle",null,false,range[0],range[1],true,10).size());assertEquals(1,s.listFiltered("needle",null,false,range[0],range[1],false,10).size());
    s.clearImages(true);assertTrue(s.listFiltered("",null,false,-1,-1,true,10).isEmpty());
  }
  public void testCleanupPreviewCountsSharedBytesAndProtectsFavoriteHistory()throws Exception{
    NotificationImages.Attachment shared=ImageStoreTest.picture(0xff146c59),exclusive=ImageStoreTest.picture(0xffefc875);Record favorite=capture("favorite","fav",shared);s.favorite(favorite.id,true);Record other=capture("other","old",shared);s.capture("other","v130.fixture","Fixture","標題","new",2,new NotificationImages.Result(Collections.singletonList(exclusive),"saved"));s.note(other.id,"備註");
    long[] preview=s.imageCleanupPreview(false);assertEquals(1,preview[0]);assertEquals(2,preview[1]);assertEquals(exclusive.data.length,preview[2]);assertEquals(preview[2],s.clearImages(false));assertNotNull(s.image(shared.hash));assertNull(s.image(exclusive.hash));assertEquals("備註",s.list("new",null,false,false,10).get(0).note);
    long[] all=s.imageCleanupPreview(true);assertEquals(1,all[0]);assertEquals(1,all[1]);assertEquals(shared.data.length,all[2]);
  }
  public void testDiagnosticFilteringSequenceAndRingTruncation()throws Exception{
    long sequence=s.diagnosticSequence();for(int i=0;i<205;i++)Diagnostics.record(s,i%2==0?"received":"excluded","fixture.pkg");JSONArray rows=s.diagnosticEvents();assertEquals(200,rows.length());assertTrue(Diagnostics.truncated(rows,sequence));JSONObject row=rows.getJSONObject(0);assertTrue(Diagnostics.matches(row,"fixture.pkg",0,1));assertFalse(Diagnostics.matches(row,"wrong.pkg",0,0));assertFalse(Diagnostics.matches(row,"fixture.pkg",row.getLong("time")+1,0));assertTrue(Diagnostics.matches(rows.getJSONObject(1),"fixture.pkg",0,3));s.clearDiagnostics();assertTrue(s.diagnosticSequence()>sequence);
  }
  public void testInterruptedCleanupPreservesLiveSessionAndPrunesOnlyOwnedDirectories()throws Exception{
    File root=new File(getContext().getCacheDir(),"backup-work");root.mkdirs();File abandoned=new File(root,"op_"+UUID.randomUUID()),other=new File(root,"user-file");assertTrue(abandoned.mkdir());assertTrue(other.mkdir());
    try(BackupFiles.Session live=new BackupFiles.Session(getContext())){BackupFiles.cleanInterrupted(getContext());assertFalse(abandoned.exists());assertTrue(live.dir.isDirectory());assertTrue(other.isDirectory());}finally{other.delete();}
  }
}
