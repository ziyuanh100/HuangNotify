package dev.huang.notify;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.util.concurrent.*;

/** CI-only cross-process interruption, followed by a real fresh app launch. */
final class BackupProcessHarness {
  static void run(Instrumentation instrumentation,String phase)throws Exception{
    Context c=instrumentation.getTargetContext();
    if(phase.equals("interrupt-before")){
      UpdateChecker.enable(c,false);c.getSharedPreferences("permission_guide",0).edit().putInt("step",3).commit();
      Store.IO.submit(()->{Store s=Store.get(c);s.putSetting("paused","0");s.capture("interruption-fixture","interruption.fixture","Fixture","保留","程序中斷後仍保留",1);s.note(s.list("程序中斷後仍保留","interruption.fixture",false,false,10).get(0).id,"interruption-note");return null;}).get(20,TimeUnit.SECONDS);
      // Hold the source executor so the operation is definitely still in progress when killed.
      Store.IO.execute(()->{try{new CountDownLatch(1).await(60,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
      instrumentation.runOnMainSync(()->{BackupOperation op=BackupOperation.begin(c,BackupOperation.State.EXPORTING);if(op==null)throw new AssertionError("Unexpected active operation");op.export("never-persist-this-password".toCharArray());});
      long deadline=SystemClock.elapsedRealtime()+10000;boolean found=false;File root=new File(c.getCacheDir(),"backup-work");
      while(SystemClock.elapsedRealtime()<deadline){File[] files=root.listFiles();if(files!=null&&files.length>0){found=true;break;}SystemClock.sleep(50);}
      if(!found)throw new AssertionError("No private operation directory was created");
      if(!c.getSharedPreferences("backup_operation",0).edit().putString("pending",BackupOperation.current().id).commit())throw new AssertionError("Interruption marker not persisted");
      Bundle result=new Bundle();result.putString("backup_interruption","READY: active export and durable marker; next step force-stops target");instrumentation.finish(Activity.RESULT_OK,result);return;
    }
    if(!phase.equals("interrupt-after"))throw new AssertionError("Unknown interruption phase");
    if(BackupOperation.current()!=null)throw new AssertionError("Old process operation survived a force-stop");
    if(!c.getSharedPreferences("backup_operation",0).contains("pending"))throw new AssertionError("Interruption marker was lost");
    MainActivity main=(MainActivity)instrumentation.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));instrumentation.waitForIdleSync();
    AutoBackup.IO.submit(()->{}).get(20,TimeUnit.SECONDS);
    if(c.getSharedPreferences("backup_operation",0).contains("pending"))throw new AssertionError("Interruption marker was not acknowledged on resume");
    if(BackupOperation.current()!=null)throw new AssertionError("Interrupted task unexpectedly resumed without a password");
    File[] files=new File(c.getCacheDir(),"backup-work").listFiles();if(files!=null)for(File file:files)if(file.getName().startsWith("op_"))throw new AssertionError("Abandoned private data was not cleaned");
    Store.IO.submit(()->{java.util.List<Record> rows=Store.get(c).list("程序中斷後仍保留","interruption.fixture",false,false,10);if(rows.size()!=1||!rows.get(0).note.equals("interruption-note"))throw new AssertionError("Live records were changed by interrupted export");}).get(20,TimeUnit.SECONDS);
    instrumentation.runOnMainSync(main::finish);Bundle result=new Bundle();result.putString("backup_interruption","PASS: process replacement, interruption acknowledgement, private cleanup and retained records");instrumentation.finish(Activity.RESULT_OK,result);
  }
}
