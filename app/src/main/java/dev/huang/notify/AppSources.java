package dev.huang.notify;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.text.Collator;
import java.util.*;

/** Local-only inventory for choosing notification sources before their first notification. */
final class AppSources {
  static final class Entry {
    final String pkg, label;
    final boolean installed, system, enabled, allowed;
    final long count;
    Entry(String pkg, String label, boolean installed, boolean system, boolean enabled, long count, boolean allowed) {
      this.pkg=pkg; this.label=label; this.installed=installed; this.system=system;
      this.enabled=enabled; this.count=count; this.allowed=allowed;
    }
    String description() {
      String kind=!installed?"歷史來源":system?"系統 App":"一般 App";
      return pkg+"\n"+kind+(installed && !enabled?" · 已停用":"")+" · "+(count==0?"尚無紀錄":count+" 則紀錄");
    }
  }
  static List<Entry> load(Context c, Store store) {
    PackageManager pm=c.getPackageManager(); List<Entry> installed=new ArrayList<>();
    for(ApplicationInfo info:pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)) {
      if ((info.flags & ApplicationInfo.FLAG_INSTALLED)==0) continue;
      String label=info.packageName;
      try { CharSequence value=pm.getApplicationLabel(info); if(value!=null && value.length()>0) label=value.toString(); } catch(RuntimeException ignored) { }
      boolean system=(info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0;
      installed.add(new Entry(info.packageName,label,true,system,info.enabled,0,true));
    }
    return merge(installed,store.apps(),store.excludedPackages(),c.getPackageName());
  }
  static List<Entry> merge(List<Entry> installed,List<String[]> history,Set<String> excluded,String self) {
    Map<String,Entry> entries=new HashMap<>();
    for(Entry entry:installed) if(!entry.pkg.equals(self)) entries.put(entry.pkg,entry);
    for(String[] row:history) {
      if(row[0].equals(self)) continue;
      Entry existing=entries.get(row[0]);
      entries.put(row[0],new Entry(row[0],existing==null?row[1]:existing.label,existing!=null && existing.installed,
          existing!=null && existing.system,existing!=null && existing.enabled,Long.parseLong(row[2]),true));
    }
    // Exclusions without remaining records must remain editable, even after uninstall.
    for(String pkg:excluded) if(!pkg.equals(self) && !entries.containsKey(pkg))
      entries.put(pkg,new Entry(pkg,pkg,false,false,false,0,false));
    List<Entry> result=new ArrayList<>();
    for(Entry e:entries.values()) result.add(new Entry(e.pkg,e.label,e.installed,e.system,e.enabled,e.count,!excluded.contains(e.pkg)));
    Collator order=Collator.getInstance(Locale.TAIWAN);
    result.sort((a,b)->{ int n=order.compare(a.label,b.label); return n==0?a.pkg.compareTo(b.pkg):n; });
    return result;
  }
}
