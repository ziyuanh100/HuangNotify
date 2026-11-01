package dev.huang.notify;

import android.app.Notification;
import android.graphics.*;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.*;
import android.test.AndroidTestCase;
import java.util.*;

@SuppressWarnings("deprecation")
public class NotificationImagesTest extends AndroidTestCase {
  public void testLargeImageIsBoundedAndOriginalIsNotRecycled() throws Exception {
    Bitmap original=Bitmap.createBitmap(1600,2400,Bitmap.Config.ARGB_8888);
    // Noise exercises the encoded-byte bound rather than only a flat, easily compressed image.
    int[] row=new int[1600]; Random random=new Random(42);
    for(int y=0;y<2400;y++) { for(int x=0;x<row.length;x++)row[x]=0xff000000|random.nextInt(0xffffff); original.setPixels(row,0,1600,0,y,1600,1); }
    try { NotificationImages.Attachment image=NotificationImages.normalize(original); assertTrue(image.data.length<=NotificationImages.MAX_IMAGE_BYTES); assertTrue(image.width<=1440 && image.height<=1440); assertFalse(original.isRecycled()); NotificationImages.validate(image.data,image.hash,image.width,image.height); }
    finally { original.recycle(); }
  }
  public void testBigPictureAndIconAreCapturedButAvatarsAreNot() throws Exception {
    Bitmap b=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888); b.eraseColor(Color.GREEN);
    try {
      Notification n=new Notification.Builder(getContext(),"fixture").setSmallIcon(android.R.drawable.ic_dialog_info).setLargeIcon(b).build();
      assertEquals("none",NotificationImages.read(getContext(),n,true).status);
      n.extras.putParcelable(Notification.EXTRA_PICTURE,b); NotificationImages.Result result=NotificationImages.read(getContext(),n,true);
      assertEquals("saved",result.status); assertEquals(1,result.images.size()); assertEquals("disabled",NotificationImages.read(getContext(),n,false).status);
      if(Build.VERSION.SDK_INT>=31) { n.extras.remove(Notification.EXTRA_PICTURE); n.extras.putParcelable(Notification.EXTRA_PICTURE_ICON,Icon.createWithBitmap(b)); assertEquals("saved",NotificationImages.read(getContext(),n,true).status); }
    } finally { b.recycle(); }
  }
  public void testNetworkFileAndMissingContentUrisDoNotDiscardText() throws Exception {
    for(String uri:new String[]{"https://example.com/private.png","file:///data/private.png","content://no.such.provider/missing"}) {
      Notification n=new Notification.Builder(getContext(),"fixture").setSmallIcon(android.R.drawable.ic_dialog_info).setContentText("可讀文字").build();
      Bundle message=new Bundle(); message.putString("type","image/png"); message.putParcelable("uri",Uri.parse(uri)); n.extras.putParcelableArray(Notification.EXTRA_MESSAGES,new Parcelable[]{message});
      NotificationImages.Result result=NotificationImages.read(getContext(),n,true); assertEquals("unavailable",result.status); assertTrue(result.images.isEmpty()); assertEquals("可讀文字",NotificationText.read(n).body);
    }
  }
  public void testCorruptPictureAndChecksumAreRejected() throws Exception {
    Notification n=new Notification.Builder(getContext(),"fixture").setSmallIcon(android.R.drawable.ic_dialog_info).build(); n.extras.putString(Notification.EXTRA_PICTURE,"wrong type");
    assertEquals("unavailable",NotificationImages.read(getContext(),n,true).status);
    NotificationImages.Attachment a=ImageStoreTest.picture(Color.GREEN); byte[] corrupt=a.data.clone(); corrupt[corrupt.length-1]^=1;
    try { NotificationImages.validate(corrupt,a.hash,a.width,a.height); fail("Checksum mismatch accepted"); } catch(java.io.IOException expected) { }
  }
}
