package dev.huang.notify;

import android.content.pm.*;
import android.test.AndroidTestCase;
import java.util.*;

@SuppressWarnings("deprecation")
public class AppSourcesTest extends AndroidTestCase {
  private Store store;
  @Override protected void setUp() throws Exception {
    super.setUp(); store=Store.get(getContext()); store.getWritableDatabase().delete("records",null,null); store.getWritableDatabase().delete("settings",null,null);
  }
  public void testInstalledInventoryIncludesAppsWithoutLauncherOrHistory() throws Exception {
    List<AppSources.Entry> entries=AppSources.load(getContext(),store); Set<String> actual=new HashSet<>();
    for(AppSources.Entry e:entries) { assertTrue(actual.add(e.pkg)); assertEquals(0L,e.count); assertTrue(e.allowed); }
    for(ApplicationInfo info:getContext().getPackageManager().getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS))
      if(!info.packageName.equals(getContext().getPackageName()) && (info.flags & ApplicationInfo.FLAG_INSTALLED)!=0) assertTrue("Missing installed package: "+info.packageName,actual.contains(info.packageName));
    assertNull(getContext().getPackageManager().getLaunchIntentForPackage("dev.huang.notify.probe"));
    assertTrue("Notification-only fixture missing before first notification",actual.contains("dev.huang.notify.probe"));
    assertFalse(actual.contains(getContext().getPackageName()));
  }
  public void testMergePreservesSystemDisabledHistoryAndPriorExclusions() {
    List<AppSources.Entry> installed=Arrays.asList(
      new AppSources.Entry("system.app","系統來源",true,true,false,0,true),
      new AppSources.Entry("fresh.app","同名",true,false,true,0,true),
      new AppSources.Entry("same.name","同名",true,false,true,0,true),
      new AppSources.Entry("self.app","本身",true,false,true,0,true));
    List<String[]> history=Arrays.asList(new String[]{"system.app","舊名稱","3"},new String[]{"removed.app","歷史 App","2"});
    Set<String> excluded=new HashSet<>(Arrays.asList("system.app","removed.empty"));
    Map<String,AppSources.Entry> merged=new HashMap<>(); for(AppSources.Entry e:AppSources.merge(installed,history,excluded,"self.app")) merged.put(e.pkg,e);
    assertEquals(5,merged.size()); AppSources.Entry system=merged.get("system.app");
    assertTrue(system.system); assertFalse(system.enabled); assertFalse(system.allowed); assertEquals("系統來源",system.label); assertEquals(3L,system.count);
    assertTrue(merged.get("fresh.app").allowed); assertFalse(merged.get("removed.app").installed); assertFalse(merged.get("removed.empty").allowed);
  }
  public void testSavingExclusionBeforeFirstNotificationControlsCapture() throws Exception {
    Map<String,Boolean> changes=new LinkedHashMap<>(); changes.put("dev.huang.notify.probe",false); store.setSourceChoices(changes);
    AppSources.Entry found=null; for(AppSources.Entry e:AppSources.load(getContext(),store)) if(e.pkg.equals("dev.huang.notify.probe")) found=e;
    assertNotNull(found); assertFalse(found.allowed); assertEquals(0L,found.count);
    store.capture("test","dev.huang.notify.probe","測試","標題","不保存",1); assertEquals(0L,store.counts()[0]);
    changes.put("dev.huang.notify.probe",true); store.setSourceChoices(changes);
    store.capture("test","dev.huang.notify.probe","測試","標題","可以保存",2); assertEquals(1L,store.counts()[0]);
  }
  public void testSourceChoiceWriteIsAtomic() {
    android.database.sqlite.SQLiteDatabase db=store.getWritableDatabase();
    db.execSQL("CREATE TEMP TRIGGER fail_source BEFORE INSERT ON settings WHEN NEW.name='exclude:second.app' BEGIN SELECT RAISE(ABORT,'fixture'); END");
    try {
      Map<String,Boolean> changes=new LinkedHashMap<>(); changes.put("first.app",false); changes.put("second.app",false);
      try { store.setSourceChoices(changes); fail("Must roll back the entire selection"); } catch(android.database.sqlite.SQLiteException expected) { }
      assertFalse(store.excluded("first.app")); assertFalse(store.excluded("second.app"));
    } finally { db.execSQL("DROP TRIGGER fail_source"); }
  }
}
