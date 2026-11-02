package dev.huang.notify;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Debug;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Opt-in, synthetic-only paired benchmark; exact 1.2.0 archive/files/crypto implementations live in test sources. */
final class BackupBenchmark {
  private static final char[] PASSWORD="benchmark-password-123".toCharArray();
  private static final Uri TREE=Uri.parse("content://dev.huang.notify.testdocs/tree/root");
  static void run(Context c,java.util.function.Consumer<String> progress)throws Exception{
    JSONArray cases=new JSONArray();JSONObject root=new JSONObject().put("app_version",BuildConfig.VERSION_NAME).put("api",android.os.Build.VERSION.SDK_INT)
      .put("baseline_commit","527a0f18091d209fce11debee02d8d38be73ce18").put("warmups_per_mode",1).put("measured_runs_per_mode",5)
      .put("destination","private debug DocumentsProvider; local disk; no picker time").put("memory","sampled Java used heap and process PSS every 100 ms; not device-wide memory").put("cases",cases);
    File report=new File(c.getFilesDir(),"backup-performance.json");
    try{
      for(String kind:new String[]{"text","images","many-short","fragmented"})for(int size:kind.equals("text")||kind.equals("images")?new int[]{10,50,99}:new int[]{10}){
        progress.accept("Preparing "+kind+" "+size);seed(c,kind,size);long[] counts=Store.IO.submit(()->Store.get(c).counts()).get();long dbBytes=c.getDatabasePath("notifications.db").length();
        for(boolean encrypted:new boolean[]{false,true}){
          JSONObject entry=new JSONObject().put("kind",kind).put("nominal_mib",size).put("encrypted",encrypted).put("records",counts[0]).put("database_bytes",dbBytes);JSONArray oldRuns=new JSONArray(),newRuns=new JSONArray();entry.put("old",oldRuns).put("new",newRuns);cases.put(entry);
          JSONObject oldWarm=measure(c,true,encrypted),newWarm=measure(c,false,encrypted);entry.put("first_old",oldWarm).put("first_new",newWarm);
          for(int trial=0;trial<5;trial++){
            progress.accept(kind+" "+size+" MiB "+(encrypted?"encrypted":"plain")+" paired "+(trial+1)+"/5");
            if(trial%2==0){oldRuns.put(measure(c,true,encrypted));newRuns.put(measure(c,false,encrypted));}else{newRuns.put(measure(c,false,encrypted));oldRuns.put(measure(c,true,encrypted));}
            write(report,root);
          }
          double before=median(oldRuns),after=median(newRuns),improvement=100*(before-after)/before;entry.put("old_median_ms",before).put("new_median_ms",after).put("improvement_percent",improvement);
          entry.put("target_met",size>=50&&encrypted?improvement>=30:after<=before*1.1);write(report,root);
        }
      }
      root.put("completed",true);write(report,root);
    }finally{Store.IO.submit(()->{Store s=Store.get(c);s.getWritableDatabase().delete("records",null,null);s.pruneImages();}).get();}
  }
  private static void write(File report,JSONObject root)throws Exception{try(Writer w=new OutputStreamWriter(new FileOutputStream(report),java.nio.charset.StandardCharsets.UTF_8)){w.write(root.toString(2));}}
  private static double median(JSONArray rows)throws Exception{double[] values=new double[rows.length()];for(int i=0;i<values.length;i++)values[i]=rows.getJSONObject(i).getDouble("total_ms");Arrays.sort(values);return values[values.length/2];}
  private static final class Memory implements AutoCloseable{
    final AtomicBoolean running=new AtomicBoolean(true);volatile long heap,pss;final Thread thread;
    Memory(){thread=new Thread(()->{while(running.get()){Runtime r=Runtime.getRuntime();heap=Math.max(heap,r.totalMemory()-r.freeMemory());pss=Math.max(pss,Debug.getPss()*1024L);try{Thread.sleep(100);}catch(InterruptedException e){return;}}},"backup-memory-sample");thread.setDaemon(true);thread.start();}
    public void close()throws InterruptedException{running.set(false);thread.interrupt();thread.join(2000);}
  }
  private static JSONObject measure(Context c,boolean legacy,boolean encrypted)throws Exception{
    return AutoBackup.IO.submit(()->{
      BackupTask task=new BackupTask();long start=System.nanoTime(),prepared,size,temp,saveStart;Map<String,Long> phases;Memory memory=new Memory();
      AutoBackup.Documents docs=new AutoBackup.Documents(c,TREE);String id=docs.create("benchmark_"+UUID.randomUUID()+(encrypted?".hnbk":".hnjson"));
      try{
        if(legacy){try(Legacy12BackupFiles.Session s=Legacy12BackupFiles.export(c,encrypted?PASSWORD:null,task)){prepared=System.nanoTime();size=s.ready.length();temp=directoryBytes(s.dir);saveStart=System.nanoTime();Legacy12BackupFiles.save(c,s,Uri.parse(id),task);phases=task.timingsMillis();}}
        else {try(BackupFiles.Session s=BackupFiles.export(c,encrypted?PASSWORD:null,task)){prepared=System.nanoTime();size=s.ready.length();temp=directoryBytes(s.dir);saveStart=System.nanoTime();BackupFiles.save(c,s,Uri.parse(id),task);phases=task.timingsMillis();}}
        long end=System.nanoTime();memory.close();return new JSONObject().put("total_ms",(end-start)/1000000.0).put("prepare_ms",(prepared-start)/1000000.0).put("save_verify_ms",(end-saveStart)/1000000.0)
          .put("output_bytes",size).put("ready_temp_bytes",temp).put("sampled_java_heap_bytes",memory.heap).put("sampled_pss_bytes",memory.pss).put("phases_ms",new JSONObject(phases));
      }finally{memory.close();docs.delete(id);}
    }).get(10,TimeUnit.MINUTES);
  }
  private static long directoryBytes(File f){if(f.isFile())return f.length();long count=0;File[] children=f.listFiles();if(children!=null)for(File child:children)count+=directoryBytes(child);return count;}
  private static String repeat(char ch,int n){char[] c=new char[n];Arrays.fill(c,ch);return new String(c);}
  private static void checkpoint(Store s){try(android.database.Cursor c=s.getWritableDatabase().rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null)){c.moveToFirst();}}
  private static void seed(Context c,String kind,int size)throws Exception{
    Store.IO.submit(()->{
      Store s=Store.get(c);android.database.sqlite.SQLiteDatabase db=s.getWritableDatabase();db.delete("records",null,null);db.delete("settings",null,null);s.pruneImages();checkpoint(s);db.execSQL("VACUUM");checkpoint(s);
      String text=repeat('資',90000);int rows=kind.equals("fragmented")?193:size==10?19:size==50?97:193;
      NotificationImages.Attachment shared=kind.equals("many-short")?ImageStoreTest.picture(0xff146c59):null;
      db.beginTransaction();try{
        if(kind.equals("images")){
          long estimate=0,target=size*1024L*1024-400000;Random random=new Random(8130);int index=0;
          while(estimate<target){Bitmap bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888);int[] pixels=new int[320*240];for(int i=0;i<pixels.length;i++)pixels[i]=0xff000000|random.nextInt(0x1000000);bitmap.setPixels(pixels,0,320,0,0,320,240);NotificationImages.Attachment image=NotificationImages.normalize(bitmap);bitmap.recycle();
            long next=((image.data.length+2)/3)*4+1400;if(estimate+next>100L*1024*1024-300000)break;s.capture("image"+index,"benchmark.fixture","Fixture","圖片基準","synthetic",index,new NotificationImages.Result(Collections.singletonList(image),"saved"));estimate+=next;index++;
          }
        }else if(kind.equals("many-short")){
          String body=repeat('x',160);for(int i=0;i<5000;i++)for(int v=0;v<3;v++)s.capture("short"+i,"benchmark.fixture","Fixture","Synthetic",body+v,i,new NotificationImages.Result(Collections.singletonList(shared),"saved"));
        }else for(int i=0;i<rows;i++)s.capture("text"+i,"benchmark.fixture","Fixture","文字基準",text,i);
        db.setTransactionSuccessful();
      }finally{db.endTransaction();}checkpoint(s);
      if(kind.equals("fragmented")){db.delete("records","nkey NOT IN (SELECT nkey FROM records ORDER BY nkey LIMIT 19)",null);s.pruneImages();checkpoint(s);}
      return null;
    }).get(5,TimeUnit.MINUTES);
  }
}
