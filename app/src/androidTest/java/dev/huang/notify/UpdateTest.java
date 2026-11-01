package dev.huang.notify;
import android.test.AndroidTestCase;
import android.app.job.JobScheduler;
import org.json.JSONArray;
import org.json.JSONObject;

@SuppressWarnings("deprecation")
public class UpdateTest extends AndroidTestCase {
  private JSONObject release(String tag, boolean preview, boolean apk) throws Exception {
    JSONObject r = new JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", preview)
        .put("html_url", "https://github.com/ziyuanh100/HuangNotify/releases/tag/" + tag);
    JSONArray assets = new JSONArray();
    if (apk) assets.put(new JSONObject().put("name", "HuangNotify.apk").put("size", 100).put("state", "uploaded")
        .put("browser_download_url", "https://github.com/ziyuanh100/HuangNotify/releases/download/" + tag + "/HuangNotify.apk"));
    return r.put("assets", assets);
  }
  public void testStableChannelIsDefaultAndExplicitPreviewChoiceIsPreserved() throws Exception {
    android.content.Context c=getContext(); android.content.SharedPreferences p=UpdateChecker.prefs(c);
    boolean present=p.contains("previews"), previous=UpdateChecker.previews(c);
    try {
      p.edit().remove("previews").commit(); assertFalse(UpdateChecker.previews(c));
      JSONArray feed=new JSONArray().put(release("v1.1.0-rc.1",true,true)).put(release("v1.0.0",false,true));
      assertEquals("v1.0.0",UpdateChecker.select(feed,"1.0.0-rc.2",UpdateChecker.previews(c)).tag);
      UpdateChecker.setPreviews(c,true); assertTrue(UpdateChecker.previews(c));
      assertEquals("v1.1.0-rc.1",UpdateChecker.select(feed,"1.0.0",UpdateChecker.previews(c)).tag);
      UpdateChecker.setPreviews(c,false); assertFalse(UpdateChecker.previews(c));
      assertFalse(UpdateChecker.select(feed,"1.0.0",UpdateChecker.previews(c)).available());
    } finally { if(present) p.edit().putBoolean("previews",previous).commit(); else p.edit().remove("previews").commit(); }
  }
  public void testChecksDefaultOnAndExplicitOffIsPreserved() {
    android.content.SharedPreferences p=UpdateChecker.prefs(getContext()); boolean present=p.contains("enabled"), old=UpdateChecker.enabled(getContext());
    try {
      p.edit().remove("enabled").commit(); assertTrue(UpdateChecker.enabled(getContext()));
      UpdateChecker.enable(getContext(),false); assertFalse(UpdateChecker.enabled(getContext()));
      UpdateChecker.schedule(getContext()); assertFalse(UpdateChecker.enabled(getContext()));
    } finally { if(present) UpdateChecker.enable(getContext(),old); else p.edit().remove("enabled").commit(); }
  }
  public void testEntryBypassesDailyCacheButExplicitOffDoesNotFetch() throws Exception {
    android.content.Context c=getContext(); UpdateChecker.Source old=UpdateChecker.source;
    java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
    try {
      UpdateChecker.prefs(c).edit().clear().putBoolean("enabled",true).putLong("checked",System.currentTimeMillis()).commit();
      UpdateChecker.source=(context,previews)->{ calls.incrementAndGet(); return new UpdateChecker.NetworkResult(new UpdateChecker.Result("","","沒有新版"),"test-etag"); };
      for(int i=0;i<2;i++) {
        java.util.concurrent.CountDownLatch latch=new java.util.concurrent.CountDownLatch(1);
        UpdateChecker.checkOnEntry(c,result->latch.countDown()); assertTrue(latch.await(10,java.util.concurrent.TimeUnit.SECONDS));
      }
      assertEquals(2,calls.get()); UpdateChecker.enable(c,false);
      java.util.concurrent.CountDownLatch latch=new java.util.concurrent.CountDownLatch(1);
      UpdateChecker.checkOnEntry(c,result->latch.countDown()); assertTrue(latch.await(10,java.util.concurrent.TimeUnit.SECONDS)); assertEquals(2,calls.get());
    } finally { UpdateChecker.source=old; UpdateChecker.enable(c,false); UpdateChecker.prefs(c).edit().remove("etag").remove("checked").commit(); }
  }
  public void testDisablingWhileRequestRunsSuppressesResult() throws Exception {
    android.content.Context c=getContext(); UpdateChecker.Source old=UpdateChecker.source;
    java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1), release=new java.util.concurrent.CountDownLatch(1), done=new java.util.concurrent.CountDownLatch(1);
    boolean[] available={true};
    try {
      UpdateChecker.prefs(c).edit().clear().putBoolean("enabled",true).commit();
      UpdateChecker.source=(context,previews)->{ started.countDown(); release.await(10,java.util.concurrent.TimeUnit.SECONDS); return new UpdateChecker.NetworkResult(new UpdateChecker.Result("v9.0.0","https://github.com/ziyuanh100/HuangNotify/releases/tag/v9.0.0","有新版"),""); };
      UpdateChecker.checkOnEntry(c,result->{ available[0]=result.available(); done.countDown(); });
      assertTrue(started.await(10,java.util.concurrent.TimeUnit.SECONDS)); UpdateChecker.enable(c,false); release.countDown();
      assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS)); assertFalse(available[0]); assertEquals("",UpdateChecker.prefs(c).getString("tag",""));
    } finally { release.countDown(); UpdateChecker.source=old; UpdateChecker.enable(c,false); }
  }
  public void testSelectsHighestVersionNotFirstDate() throws Exception {
    JSONArray feed = new JSONArray().put(release("v0.3.0", false, true)).put(release("v0.10.0", false, true)).put(release("v0.2.1", false, true));
    assertEquals("v0.10.0", UpdateChecker.select(feed, "0.2.0-alpha.1", false).tag);
  }
  public void testIgnoresDraftMissingApkAndOtherHosts() throws Exception {
    JSONArray feed = new JSONArray().put(release("v3.0.0", false, true).put("draft", true))
        .put(release("v2.0.0", false, false))
        .put(release("v1.0.0", false, true).put("html_url", "https://github.com.evil.example/releases/tag/v1.0.0"));
    assertFalse(UpdateChecker.select(feed, "0.2.0-alpha.1", true).available());
    assertFalse(UpdateChecker.validReleaseUrl("https://github.com@evil.example/ziyuanh100/HuangNotify/releases/tag/v1.0.0"));
    assertFalse(UpdateChecker.validReleaseUrl("http://github.com/ziyuanh100/HuangNotify/releases/tag/v1.0.0"));
  }
  public void testPreviewChannelAndNoDowngrades() throws Exception {
    JSONArray feed = new JSONArray().put(release("v0.3.0-alpha.1", true, true)).put(release("v0.2.0", false, true));
    assertEquals("v0.2.0", UpdateChecker.select(feed, "0.2.0-alpha.1", false).tag);
    assertEquals("v0.3.0-alpha.1", UpdateChecker.select(feed, "0.2.0-alpha.1", true).tag);
    assertFalse(UpdateChecker.select(feed, "0.4.0", true).available());
  }
  public void testOptInSchedulesPersistentJobAndDisableCancels() {
    boolean previous = UpdateChecker.enabled(getContext());
    try {
      UpdateChecker.enable(getContext(), true);
      android.app.job.JobInfo job = getContext().getSystemService(JobScheduler.class).getPendingJob(UpdateChecker.JOB);
      assertNotNull(job); assertTrue(job.isPersisted()); assertEquals(UpdateChecker.DAY, job.getIntervalMillis());
      UpdateChecker.enable(getContext(), false);
      assertNull(getContext().getSystemService(JobScheduler.class).getPendingJob(UpdateChecker.JOB));
    } finally { UpdateChecker.enable(getContext(), previous); }
  }
}
