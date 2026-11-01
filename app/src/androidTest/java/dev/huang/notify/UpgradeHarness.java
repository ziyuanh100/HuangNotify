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
  private boolean confirmInstall(android.view.accessibility.AccessibilityNodeInfo node) {
    CharSequence label=node.getText(), pkg=node.getPackageName();
    if(pkg!=null && pkg.toString().contains("packageinstaller") && label!=null
        && (label.toString().equalsIgnoreCase("Update") || label.toString().equalsIgnoreCase("Install"))
        && node.isEnabled() && node.isClickable()) return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
    for(int i=0;i<node.getChildCount();i++) {
      android.view.accessibility.AccessibilityNodeInfo child=node.getChild(i);
      if(child!=null && confirmInstall(child)) return true;
    }
    return false;
  }
  /** CI emulator recovery only: never dismiss an ANR belonging to the app under test. */
  private boolean closeQuickstepAnr(android.view.accessibility.AccessibilityNodeInfo root) {
    boolean quickstep=false;
    for(android.view.accessibility.AccessibilityNodeInfo title:root.findAccessibilityNodeInfosByViewId("android:id/alertTitle"))
      if("Quickstep isn't responding".contentEquals(title.getText()==null?"":title.getText())) quickstep=true;
    if(!quickstep) return false;
    for(android.view.accessibility.AccessibilityNodeInfo button:root.findAccessibilityNodeInfosByViewId("android:id/aerr_close"))
      if("android".contentEquals(button.getPackageName()==null?"":button.getPackageName()) && button.isEnabled() && button.isClickable()) {
        Bundle diagnostic=new Bundle(); diagnostic.putString("emulator_recovery","Quickstep launcher ANR obscured installer; closing that launcher only"); sendStatus(0,diagnostic);
        return button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
      }
    return false;
  }
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
        UpgradeManager.prefs(c).edit().putBoolean("ready",true).putString("tag","v"+BuildConfig.VERSION_NAME).putString("sha",sha.toString()).commit();
        MainActivity old=(MainActivity)startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        UpgradeManager.install(c,false);
        long deadline=SystemClock.elapsedRealtime()+30000;
        while (UpgradeManager.confirmation==null && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100);
        require(UpgradeManager.confirmation!=null,"System confirmation Intent missing: "+UpgradeManager.status(c));
        runOnMainSync(()->old.startActivity(UpgradeManager.confirmation));
        deadline=SystemClock.elapsedRealtime()+30000;
        boolean clicked=false, launcherRecovered=false;
        while (!clicked && SystemClock.elapsedRealtime()<deadline) {
          android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
          if(root!=null) {
            if(!launcherRecovered && closeQuickstepAnr(root)) { launcherRecovered=true; deadline=SystemClock.elapsedRealtime()+30000; }
            else clicked=confirmInstall(root);
          }
          if(!clicked) SystemClock.sleep(100);
        }
        require(clicked,"System install confirmation button missing");
        // Successful self update intentionally terminates this old instrumentation process.
        SystemClock.sleep(20000);
        throw new AssertionError("Old process was not replaced: "+UpgradeManager.status(c));
      }
      require(phase.equals("after"),"Unknown harness phase");
      require(c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName.equals(BuildConfig.VERSION_NAME),"New APK was not installed");
      Store.IO.submit(()->{
        Store store=Store.get(c); java.util.List<Record> records=store.list("",null,false,false,10);
        require(records.size()==1,"Record missing after upgrade"); Record r=records.get(0);
        require(r.text.equals("updated") && r.note.equals("upgrade-sentinel") && r.favorite,"Content, note or favorite was lost");
        try { require(store.versions(r.id).length()==2,"Version history was lost"); } catch(Exception e) { throw new RuntimeException(e); }
        require(store.setting("paused","0").equals("1") && store.setting("retention","0").equals("30"),"Recording settings were lost");
      }).get();
      require(Store.get(c).getReadableDatabase().getVersion()==2,"Image schema migration did not run");
      Store.IO.submit(()->{
        Store store=Store.get(c); Record old=store.list("",null,false,false,10).get(0);
        require(old.imageCount==0 && "none".equals(old.imageStatus),"Legacy record image state changed");
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xff146c59);
        NotificationImages.Attachment attachment=NotificationImages.normalize(bitmap); bitmap.recycle();
        store.putSetting("paused","0");
        store.capture("image-upgrade","fixture.image","Fixture","圖片升級","保留",3,new NotificationImages.Result(java.util.Collections.singletonList(attachment),"saved"));
        store.putSetting("paused","1");
        require(java.util.Arrays.equals(attachment.data,store.image(attachment.hash)),"New image write failed after migration");
        BackupFormat.validate(store.exportBackup()); return null;
      }).get();
      // User reopening is the reliable fallback when replacement broadcasts are deferred.
      MainActivity main=(MainActivity)startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      long end=SystemClock.elapsedRealtime()+10000;
      while((main.updateFlow.dialog==null || !main.updateFlow.dialog.isShowing()) && SystemClock.elapsedRealtime()<end) { waitForIdleSync(); SystemClock.sleep(100); }
      require(UpgradeManager.prefs(c).getBoolean("completion_pending",false),"Completion was not recovered after launch");
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
