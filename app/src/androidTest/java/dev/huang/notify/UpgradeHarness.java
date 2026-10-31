package dev.huang.notify;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;

/** Separate CI instrumentation: real old-APK self update, process replacement, and reopen. */
public final class UpgradeHarness extends android.test.InstrumentationTestRunner {
  private String phase;
  @Override public void onCreate(Bundle arguments) { phase=arguments.getString("phase",""); if (phase.isEmpty()) super.onCreate(arguments); else start(); }
  private void require(boolean value,String error) { if(!value) throw new AssertionError(error); }
  @Override public void onStart() {
    if (phase.isEmpty()) { super.onStart(); return; }
    try {
      android.content.Context c=getTargetContext();
      if(phase.equals("before")) {
        c.getSharedPreferences("updates",0).edit().putBoolean("enabled",false).commit();
        c.getSharedPreferences("permission_guide",0).edit().putInt("step",3).commit();
        Store.IO.submit(()->{
          Store store=Store.get(c);
          store.capture("upgrade-fixture","fixture.package","Fixture","seed","first",1);
          store.capture("upgrade-fixture","fixture.package","Fixture","seed","updated",2);
          Record record=store.list("",null,false,false,10).get(0);
          store.favorite(record.id,true); store.note(record.id,"upgrade-sentinel");
          store.putSetting("paused","1"); store.putSetting("retention","30");
        }).get();
        File target=UpgradeManager.apk(c);
        try(ParcelFileDescriptor fd=getUiAutomation().executeShellCommand("cat /data/local/tmp/huangnotify-next.apk"); InputStream in=new FileInputStream(fd.getFileDescriptor()); OutputStream out=new FileOutputStream(target)) {
          byte[] buffer=new byte[16384]; int n; while((n=in.read(buffer))!=-1) out.write(buffer,0,n);
        }
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(target)) { byte[] buffer=new byte[16384]; int n; while((n=in.read(buffer))!=-1)digest.update(buffer,0,n); }
        StringBuilder sha=new StringBuilder(); for(byte b:digest.digest()) sha.append(String.format(Locale.ROOT,"%02x",b&255));
        UpgradeManager.configure(c,true,true);
        UpgradeManager.prefs(c).edit().putBoolean("ready",true).putString("tag","v1.0.0-alpha.2").putString("sha",sha.toString()).commit();
        UpgradeManager.install(c,true);
        // Successful self update intentionally terminates this old instrumentation process.
        SystemClock.sleep(20000);
        throw new AssertionError("Old process was not replaced: "+UpgradeManager.status(c));
      }
      require(phase.equals("after"),"Unknown harness phase");
      require(c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName.equals("1.0.0-alpha.2"),"New APK was not installed");
      Store.IO.submit(()->{
        Store store=Store.get(c); java.util.List<Record> records=store.list("",null,false,false,10);
        require(records.size()==1,"Record missing after upgrade"); Record r=records.get(0);
        require(r.text.equals("updated") && r.note.equals("upgrade-sentinel") && r.favorite,"Content, note or favorite was lost");
        try { require(store.versions(r.id).length()==2,"Version history was lost"); } catch(Exception e) { throw new RuntimeException(e); }
        require(store.setting("paused","0").equals("1") && store.setting("retention","0").equals("30"),"Recording settings were lost");
      }).get();
      long end=SystemClock.elapsedRealtime()+10000;
      while(!UpgradeManager.prefs(c).getBoolean("completion_pending",false) && SystemClock.elapsedRealtime()<end) SystemClock.sleep(100);
      require(UpgradeManager.prefs(c).getBoolean("completion_pending",false),"Package replacement completion was not persisted");
      MainActivity main=(MainActivity)startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      end=SystemClock.elapsedRealtime()+10000;
      while((main.updateFlow.dialog==null || !main.updateFlow.dialog.isShowing()) && SystemClock.elapsedRealtime()<end) { waitForIdleSync(); SystemClock.sleep(100); }
      require(main.updateFlow.dialog!=null && main.updateFlow.dialog.isShowing(),"Completion dialog missing after launch");
      runOnMainSync(()->main.updateFlow.dialog.getButton(AppDialog.BUTTON_POSITIVE).performClick()); waitForIdleSync();
      require(main.isFinishing(),"Reopen did not replace the old activity");
      require(!UpgradeManager.prefs(c).getBoolean("completion_pending",true),"Completion dialog would repeat after reopen");
      Bundle report=new Bundle(); report.putString("upgrade_cycle","PASS: APK self-update, retained record/history/favorite/note/settings, completion and reopen"); finish(Activity.RESULT_OK,report);
    } catch(Throwable error) {
      Bundle report=new Bundle(); report.putString("failure",error.toString()); finish(Activity.RESULT_CANCELED,report);
    }
  }
}
