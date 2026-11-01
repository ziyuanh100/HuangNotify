package dev.huang.notify;

import android.app.*;
import android.os.*;
import android.service.notification.StatusBarNotification;
import android.test.AndroidTestCase;

@SuppressWarnings("deprecation")
public class NotificationTextTest extends AndroidTestCase {
  private Notification notification() { return new Notification.Builder(getContext(),"fixture").setSmallIcon(android.R.drawable.ic_dialog_info).build(); }
  private StatusBarNotification item(String pkg,int id,String group,boolean summary) {
    Notification.Builder b=new Notification.Builder(getContext(),"fixture").setSmallIcon(android.R.drawable.ic_dialog_info).setGroup(group).setGroupSummary(summary);
    return new StatusBarNotification(pkg,pkg,id,null,10000,0,b.build(),android.os.Process.myUserHandle(),System.currentTimeMillis());
  }
  public void testStandaloneScreenshotSummaryIsEligibleAndImageIsDescribed() {
    Notification n=notification(); n.flags|=Notification.FLAG_GROUP_SUMMARY;
    n.extras.putCharSequence(Notification.EXTRA_TITLE,"截圖已儲存");
    n.extras.putParcelable(Notification.EXTRA_PICTURE,android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888));
    NotificationText value=NotificationText.read(n); assertEquals("截圖已儲存",value.title); assertEquals(NotificationText.IMAGE,value.body);
    StatusBarNotification summary=item("capture.app",1,"shot",true);
    assertFalse(NotificationText.hasGroupChildren(summary,new StatusBarNotification[]{summary}));
  }
  public void testOnlyChildrenOfSamePackageUserAndGroupSuppressSummary() {
    StatusBarNotification summary=item("capture.app",1,"shot",true);
    assertFalse(NotificationText.hasGroupChildren(summary,new StatusBarNotification[]{item("other.app",2,"shot",false),item("capture.app",3,"different",false)}));
    assertTrue(NotificationText.hasGroupChildren(summary,new StatusBarNotification[]{item("capture.app",2,"shot",false)}));
  }
  public void testTickerAndExpandedTitleWorkWithoutStandardText() {
    Notification n=notification(); n.extras.putCharSequence(Notification.EXTRA_TITLE_BIG,"截圖工具"); n.tickerText="截圖已儲存";
    NotificationText value=NotificationText.read(n); assertEquals("截圖工具",value.title); assertEquals("截圖已儲存",value.body);
    n.extras=null; assertEquals("截圖已儲存",NotificationText.read(n).body);
  }
  public void testMalformedAndEmptyExtrasDoNotEraseReadableContent() {
    Notification n=notification(); n.extras.putInt(Notification.EXTRA_TITLE,42); n.extras.putCharSequence(Notification.EXTRA_TITLE_BIG,"備援標題");
    n.extras.putCharSequence(Notification.EXTRA_BIG_TEXT,"可讀取內容"); n.extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES,new CharSequence[]{null,"","  "});
    n.extras.putParcelableArray(Notification.EXTRA_MESSAGES,new Parcelable[]{new Bundle()});
    NotificationText value=NotificationText.read(n); assertEquals("備援標題",value.title); assertEquals("可讀取內容",value.body);
    n.extras=new Bundle(); assertTrue(NotificationText.read(n).body.contains("未提供"));
  }
  public void testMessagingSenderAndImageMessageFallback() {
    Notification.MessagingStyle style;
    if(Build.VERSION.SDK_INT>=28) {
      style=new Notification.MessagingStyle(new Person.Builder().setName("自己").build());
      style.addMessage(new Notification.MessagingStyle.Message("你好",1,new Person.Builder().setName("寄件者").build()));
      style.addMessage(new Notification.MessagingStyle.Message("",2,new Person.Builder().setName("寄件者").build()).setData("image/png",android.net.Uri.parse("content://fixture/picture")));
    } else {
      style=new Notification.MessagingStyle("自己"); style.addMessage(new Notification.MessagingStyle.Message("你好",1,"寄件者"));
      style.addMessage(new Notification.MessagingStyle.Message("",2,"寄件者").setData("image/png",android.net.Uri.parse("content://fixture/picture")));
    }
    Notification n=new Notification.Builder(getContext(),"fixture").setSmallIcon(android.R.drawable.ic_dialog_info).setStyle(style).build();
    String body=NotificationText.read(n).body; assertTrue(body.contains("寄件者：你好")); assertTrue(body.contains(NotificationText.IMAGE));
  }
  public void testTextAssemblyIsBounded() {
    Notification n=notification(); String text=new String(new char[70000]).replace('\0','x');
    n.extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES,new CharSequence[]{text,text,text});
    assertEquals(NotificationText.LIMIT,NotificationText.read(n).body.length());
  }
}
