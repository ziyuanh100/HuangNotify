package dev.huang.notify;

import android.test.AndroidTestCase;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("deprecation")
public class BackupTest extends AndroidTestCase {
  private static final String PREFIX="HuangNotify_auto_test_";
  private String name(int time) { return PREFIX+String.format(java.util.Locale.ROOT,"%013d",time)+"_00000000-0000-0000-0000-000000000000.hnbk"; }
  static final class Memory implements AutoBackup.Destination {
    Map<String,byte[]> data=new HashMap<>(); boolean corrupt;
    public String create(String name) { data.put(name,new byte[0]); return name; }
    public OutputStream write(String id) { return new ByteArrayOutputStream() { public void close() { data.put(id,toByteArray()); } }; }
    public InputStream read(String id) { return new ByteArrayInputStream(corrupt?new byte[]{0}:data.get(id)); }
    public Map<String,String> files() { Map<String,String> names=new HashMap<>(); for(String id:data.keySet()) names.put(id,id); return names; }
    public void delete(String id) { data.remove(id); }
  }
  public void testCorruptWriteNeverPrunesPreviousBackups() throws Exception {
    Memory d=new Memory(); d.data.put(name(1),new byte[]{1}); d.corrupt=true;
    try { AutoBackup.saveVerified(d,name(2),PREFIX,new byte[]{2,3},1,new AtomicBoolean()); fail(); } catch(IOException expected) { }
    assertEquals(1,d.data.size()); assertTrue(d.data.containsKey(name(1)));
  }
  public void testRetentionPreservesManualAndOtherInstallationFiles() throws Exception {
    Memory d=new Memory(); d.data.put(name(1),new byte[]{1}); d.data.put("manual.hnbk",new byte[]{4}); d.data.put("HuangNotify_auto_other.hnbk",new byte[]{5});
    AutoBackup.saveVerified(d,name(2),PREFIX,new byte[]{2},1,new AtomicBoolean());
    assertFalse(d.data.containsKey(name(1))); assertTrue(d.data.containsKey(name(2))); assertEquals(3,d.data.size());
  }
  public void testCancelledWriteNeverPrunesPreviousBackups() throws Exception {
    Memory d=new Memory(); d.data.put(name(1),new byte[]{1});
    try { AutoBackup.saveVerified(d,name(2),PREFIX,new byte[]{2},1,new AtomicBoolean(true)); fail(); } catch(InterruptedIOException expected) { }
    assertEquals(1,d.data.size());
  }
  public void testKeystorePasswordCanUnlockPortableBackup() throws Exception {
    char[] password="繁體中文-test-password".toCharArray();
    String wrapped=DeviceSecret.wrap(password); assertFalse(wrapped.contains("test-password"));
    char[] restored=DeviceSecret.unwrap(wrapped); assertTrue(Arrays.equals(password,restored));
    byte[] clear="通知備份內容".getBytes("UTF-8");
    assertTrue(Arrays.equals(clear,BackupCrypto.decrypt(BackupCrypto.encrypt(clear,restored),password)));
    Arrays.fill(restored,'\0'); Arrays.fill(password,'\0');
  }
  public void testScheduledBackupPersistsAndDisableCancels() throws Exception {
    try {
      AutoBackup.configure(getContext(),Uri.parse("content://dev.huang.notify.testdocs/tree/root"),"password123".toCharArray(),7);
      android.app.job.JobInfo job=getContext().getSystemService(android.app.job.JobScheduler.class).getPendingJob(AutoBackup.JOB);
      assertNotNull(job); assertTrue(job.isPersisted()); assertTrue(job.isRequireBatteryNotLow());
      AutoBackup.disable(getContext()); assertNull(getContext().getSystemService(android.app.job.JobScheduler.class).getPendingJob(AutoBackup.JOB));
      assertEquals("",AutoBackup.prefs(getContext()).getString("secret",""));
    } finally { AutoBackup.disable(getContext()); }
  }
  public void testActualDocumentsBackupCanDecryptAndValidate() throws Exception {
    android.content.Context c=getContext(); Uri tree=Uri.parse("content://dev.huang.notify.testdocs/tree/root");
    try {
      AutoBackup.configure(c,tree,"password123".toCharArray(),3);
      CountDownLatch latch=new CountDownLatch(1);
      AutoBackup.run(c,true,new AtomicBoolean(),message->latch.countDown()); assertTrue(latch.await(30,TimeUnit.SECONDS));
      assertTrue(AutoBackup.prefs(c).getLong("success",0)>0); assertEquals("",AutoBackup.prefs(c).getString("error",""));
      AutoBackup.Documents docs=new AutoBackup.Documents(c,tree); boolean found=false;
      for(String id:docs.files().keySet()) { try(InputStream in=docs.read(id); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
        byte[] buffer=new byte[8192]; int n; while((n=in.read(buffer))!=-1) out.write(buffer,0,n);
        byte[] raw=BackupCrypto.decrypt(out.toByteArray(),"password123".toCharArray());
        BackupFormat.validate(new org.json.JSONObject(new String(raw,"UTF-8"))); found=true;
      } }
      assertTrue(found);
    } finally { AutoBackup.disable(c); AutoBackup.Documents docs=new AutoBackup.Documents(c,tree); for(String id:docs.files().keySet()) docs.delete(id); AutoBackup.prefs(c).edit().clear().commit(); }
  }
}
