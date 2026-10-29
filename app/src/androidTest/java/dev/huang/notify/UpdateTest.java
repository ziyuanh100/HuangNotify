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
