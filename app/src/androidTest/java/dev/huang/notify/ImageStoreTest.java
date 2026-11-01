package dev.huang.notify;

import android.graphics.Bitmap;
import android.test.AndroidTestCase;
import java.util.*;
import org.json.*;

/** Real SQLite transactions, image lifetimes, portable backups, and legacy import. */
@SuppressWarnings("deprecation")
public class ImageStoreTest extends AndroidTestCase {
  private Store store;
  private static String repeat(char c,int count) { char[] value=new char[count]; Arrays.fill(value,c); return new String(value); }
  @Override protected void setUp() throws Exception {
    super.setUp(); store=Store.get(getContext()); store.getWritableDatabase().delete("records",null,null); store.getWritableDatabase().delete("settings",null,null); store.pruneImages();
  }
  static NotificationImages.Attachment picture(int color) throws Exception {
    Bitmap b=Bitmap.createBitmap(48,32,Bitmap.Config.ARGB_8888); b.eraseColor(color);
    try { return NotificationImages.normalize(b); } finally { b.recycle(); }
  }
  private void capture(String key,NotificationImages.Attachment image) {
    store.capture(key,"fixture.images","圖片範例","旅行照片","即使通知消失，圖片仍會留下。",1,new NotificationImages.Result(Collections.singletonList(image),"saved"));
  }
  public void testImageOnlyUpdateRetainsBothVersionsAndDeduplicates() throws Exception {
    NotificationImages.Attachment a=picture(0xff146c59),b=picture(0xffefc875);
    capture("one",a); capture("one",a); assertEquals(1,store.list("",null,false,false,10).get(0).versions);
    capture("one",b); Record r=store.list("",null,false,false,10).get(0); assertEquals(2,r.versions); assertEquals(1,store.counts()[0]);
    assertEquals(b.hash,store.recordImages(r.id).getString(0)); assertEquals(2,store.exportBackup().getJSONArray("images").length());
    JSONArray history=store.versions(r.id); assertEquals(b.hash,history.getJSONObject(0).getJSONArray("images").getString(0));
    store.removed("one",2); assertTrue(Arrays.equals(a.data,store.image(a.hash))); assertTrue(Arrays.equals(b.data,store.image(b.hash)));
    store.delete(r.id); assertNull(store.image(a.hash)); assertNull(store.image(b.hash));
  }
  public void testSharedImageSurvivesOtherRecordDeletionAndFavoriteCleanup() throws Exception {
    NotificationImages.Attachment a=picture(0xff146c59); capture("one",a); capture("two",a);
    List<Record> records=store.list("",null,false,false,10); store.favorite(records.get(0).id,true); store.delete(records.get(1).id);
    store.clearUnstarred(); assertNotNull(store.image(a.hash));
    store.favorite(records.get(0).id,false);
    android.content.ContentValues v=new android.content.ContentValues(); v.put("first_seen",1); store.getWritableDatabase().update("records",v,null,null);
    assertEquals(1,store.cleanup(7)); assertNull(store.image(a.hash));
  }
  public void testEncryptedV2RoundTripAndV1Compatibility() throws Exception {
    NotificationImages.Attachment a=picture(0xff146c59); capture("one",a); store.putSetting("save_images","0");
    JSONObject backup=store.exportBackup(); BackupFormat.validate(backup); assertEquals(2,backup.getInt("version"));
    byte[] encrypted=BackupCrypto.encrypt(backup.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),"image-test-password".toCharArray());
    JSONObject decoded=new JSONObject(new String(BackupCrypto.decrypt(encrypted,"image-test-password".toCharArray()),java.nio.charset.StandardCharsets.UTF_8));
    store.getWritableDatabase().delete("records",null,null); store.pruneImages(); assertNull(store.image(a.hash));
    assertEquals(1,store.restore(decoded,true)[0]); assertTrue(Arrays.equals(a.data,store.image(a.hash))); assertEquals("0",store.setting("save_images","1"));
    assertEquals(1,store.restore(decoded,true)[1]); assertEquals(1,store.exportBackup().getJSONArray("images").length());
    JSONObject legacy=new JSONObject(backup.toString()); legacy.put("version",1); legacy.remove("images"); legacy.getJSONObject("settings").remove("save_images");
    JSONArray rows=legacy.getJSONArray("records");
    for(int i=0;i<rows.length();i++) { JSONObject row=rows.getJSONObject(i); row.remove("images"); row.remove("image_status"); JSONArray versions=row.getJSONArray("versions"); for(int j=0;j<versions.length();j++) {versions.getJSONObject(j).remove("images"); versions.getJSONObject(j).remove("image_status");} }
    BackupFormat.validate(legacy); store.getWritableDatabase().delete("records",null,null); store.pruneImages(); store.restore(legacy,false);
    Record old=store.list("",null,false,false,10).get(0); assertEquals(0,old.imageCount); assertEquals("none",old.imageStatus); assertEquals("旅行照片",old.title);
  }
  public void testTamperingMissingReferencesAndImageLimitsFailBeforeWrites() throws Exception {
    NotificationImages.Attachment a=picture(0xff146c59); capture("one",a); JSONObject backup=store.exportBackup();
    for(int mutation=0;mutation<5;mutation++) {
      JSONObject changed=new JSONObject(backup.toString()); JSONObject image=changed.getJSONArray("images").getJSONObject(0);
      if(mutation==0)image.put("data",java.util.Base64.getEncoder().encodeToString(new byte[]{1,2,3}));
      if(mutation==1)changed.getJSONArray("records").getJSONObject(0).getJSONArray("images").put(0,repeat('0',64));
      if(mutation==2)image.put("width",1.5);
      if(mutation==3)image.put("data",repeat('A',350000));
      if(mutation==4)changed.put("version",1);
      try { store.restore(changed,true); fail("Invalid image backup was accepted: "+mutation); } catch(Exception expected) { }
      assertEquals(1,store.counts()[0]); assertTrue(Arrays.equals(a.data,store.image(a.hash)));
    }
  }
  public void testVersionImageConflictRollsBackRecordsAndNewBlobs() throws Exception {
    NotificationImages.Attachment a=picture(0xff146c59),b=picture(0xffefc875); capture("one",a); JSONObject backup=store.exportBackup();
    JSONObject row=backup.getJSONArray("records").getJSONObject(0); JSONObject extra=new JSONObject(row.toString());
    extra.put("id",UUID.randomUUID().toString()); extra.put("nkey","new-before-conflict"); extra.getJSONArray("versions").getJSONObject(0).put("id",UUID.randomUUID().toString());
    backup.put("records",new JSONArray().put(extra).put(row));
    row.getJSONArray("versions").getJSONObject(0).put("images",new JSONArray().put(b.hash));
    backup.getJSONArray("images").put(new JSONObject().put("sha256",b.hash).put("mime","image/png").put("width",b.width).put("height",b.height).put("data",java.util.Base64.getEncoder().encodeToString(b.data)));
    BackupFormat.validate(backup);
    try { store.restore(backup,true); fail("Conflicting version image was accepted"); } catch(IllegalArgumentException expected) { }
    assertEquals(1,store.counts()[0]); assertNotNull(store.image(a.hash)); assertNull(store.image(b.hash));
  }
  public void testPauseAndSourceExclusionDoNotStoreImageData() throws Exception {
    NotificationImages.Attachment a=picture(0xff146c59); store.putSetting("paused","1"); capture("one",a); assertNull(store.image(a.hash));
    store.putSetting("paused","0"); store.putSetting("exclude:fixture.images","1"); capture("one",a); assertNull(store.image(a.hash)); assertEquals(0,store.counts()[0]);
  }
}
