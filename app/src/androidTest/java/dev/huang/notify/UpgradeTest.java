package dev.huang.notify;

import android.content.pm.*;
import android.test.AndroidTestCase;
import java.io.*;
import org.json.*;

@SuppressWarnings("deprecation")
public class UpgradeTest extends AndroidTestCase {
  private PackageInfo info(String pkg,int code,String version,String signature) {
    PackageInfo p=new PackageInfo(); p.packageName=pkg; p.versionCode=code; p.versionName=version; p.signatures=new Signature[]{new Signature(signature)}; return p;
  }
  public void testSameSignatureNewVersionAcceptedButDowngradeRejected() {
    PackageInfo old=info("dev.huang.notify",4,"1.0.0-alpha.1","1234"), next=info("dev.huang.notify",5,"1.0.0-alpha.2","1234");
    UpgradeManager.validateInfo(next,old,"v1.0.0-alpha.2");
    try { UpgradeManager.validateInfo(old,next,"v1.0.0-alpha.1"); fail(); } catch(SecurityException expected) { }
  }
  public void testForeignSignaturePackageAndMetadataRejected() {
    PackageInfo old=info("dev.huang.notify",4,"1.0.0-alpha.1","1234");
    for(PackageInfo next:new PackageInfo[]{info("dev.huang.notify",5,"1.0.0-alpha.2","5678"),info("other.package",5,"1.0.0-alpha.2","1234"),info("dev.huang.notify",5,"1.0.0-alpha.3","1234")}) {
      try { UpgradeManager.validateInfo(next,old,"v1.0.0-alpha.2"); fail(); } catch(SecurityException expected) { }
    }
  }
  public void testTamperedApkRejectedBeforeParsing() throws Exception {
    File file=new File(getContext().getCacheDir(),"bad-update.apk");
    try { try(FileOutputStream out=new FileOutputStream(file)) { out.write(new byte[]{1,2,3}); }
      try { UpgradeManager.verify(getContext(),file,new String(new char[64]).replace('\0','0'),"v2.0.0"); fail(); } catch(SecurityException expected) { }
    } finally { file.delete(); }
  }
  public void testInterruptedInstallAllowsExplicitRetryAndMissingApkFails() throws Exception {
    android.content.Context c=getContext();
    try {
      try(FileOutputStream out=new FileOutputStream(UpgradeManager.apk(c))) { out.write(1); }
      UpgradeManager.prefs(c).edit().clear().putBoolean("ready",true).putString("stage","installing").commit();
      UpgradeManager.recoverInterrupted(c);
      assertEquals("retry",UpgradeManager.prefs(c).getString("stage","")); assertTrue(UpgradeManager.prefs(c).getBoolean("ready",false));
      UpgradeManager.apk(c).delete(); UpgradeManager.stage(c,"installing","interrupted"); UpgradeManager.recoverInterrupted(c);
      assertEquals("failed",UpgradeManager.prefs(c).getString("stage","")); assertFalse(UpgradeManager.prefs(c).getBoolean("ready",true));
    } finally { UpgradeManager.apk(c).delete(); UpgradeManager.prefs(c).edit().clear().commit(); }
  }
  public void testOnlyOfficialHttpsApkUrlsAllowed() {
    assertTrue(UpgradeManager.allowedDownload("https://github.com/ziyuanh100/HuangNotify/releases/download/v1.0.0/HuangNotify-v1.0.0.apk"));
    for(String url:new String[]{"http://github.com/ziyuanh100/HuangNotify/releases/download/v1/a.apk","https://github.com@evil.example/ziyuanh100/HuangNotify/releases/download/v1/a.apk","https://github.com/other/repo/releases/download/v1/a.apk","https://github.com/ziyuanh100/HuangNotify/releases/download/v1/../a.apk"}) assertFalse(UpgradeManager.allowedDownload(url));
  }
  public void testFeedRequiresShaForInAppDownload() throws Exception {
    JSONObject asset=new JSONObject().put("name","a.apk").put("size",100).put("state","uploaded").put("browser_download_url","https://github.com/ziyuanh100/HuangNotify/releases/download/v2.0.0/a.apk");
    JSONObject release=new JSONObject().put("tag_name","v2.0.0").put("draft",false).put("prerelease",false).put("html_url","https://github.com/ziyuanh100/HuangNotify/releases/tag/v2.0.0").put("assets",new JSONArray().put(asset));
    UpdateChecker.Result result=UpdateChecker.select(new JSONArray().put(release),"1.0.0",true); assertTrue(result.available()); assertFalse(result.installable());
    asset.put("digest","sha256:"+new String(new char[64]).replace('\0','a')); assertTrue(UpdateChecker.select(new JSONArray().put(release),"1.0.0",true).installable());
  }
}
