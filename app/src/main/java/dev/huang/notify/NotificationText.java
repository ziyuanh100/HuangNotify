package dev.huang.notify;

import android.app.Notification;
import android.os.*;
import android.service.notification.StatusBarNotification;

/** Read only notification-provided metadata. Never fetch image URIs or inspect the screen. */
final class NotificationText {
  static final int LIMIT=100000;
  static final String IMAGE="[通知包含圖片；目前僅保存文字，未保存圖片]";
  final String title, body;
  private NotificationText(String title,String body) { this.title=trim(title); this.body=trim(body); }
  static Object value(Bundle b,String key) { try { return b==null?null:b.get(key); } catch(RuntimeException e) { return null; } }
  static String text(Object value) { return value instanceof CharSequence?trim(value.toString()):""; }
  private static String trim(String value) { return value.length()>LIMIT?value.substring(0,LIMIT):value; }
  private static boolean blank(String value) { return value.trim().isEmpty(); }
  private static String first(String a,String b) { return blank(a)?b:a; }
  private static void line(StringBuilder out,String value) {
    if(blank(value) || out.length()>=LIMIT) return;
    if(out.length()>0) out.append('\n');
    int remaining=LIMIT-out.length(); if(remaining>0) out.append(value,0,Math.min(remaining,value.length()));
  }
  @SuppressWarnings("deprecation")
  static NotificationText read(Notification n) {
    Bundle b=n.extras;
    String title=first(text(value(b,Notification.EXTRA_TITLE)),text(value(b,Notification.EXTRA_TITLE_BIG)));
    String body=first(text(value(b,Notification.EXTRA_BIG_TEXT)),text(value(b,Notification.EXTRA_TEXT)));
    Object lines=value(b,Notification.EXTRA_TEXT_LINES);
    if(lines instanceof CharSequence[]) {
      StringBuilder joined=new StringBuilder(); for(CharSequence item:(CharSequence[])lines) line(joined,text(item));
      if(joined.length()>0) body=joined.toString();
    }
    Object messages=value(b,Notification.EXTRA_MESSAGES); StringBuilder joined=new StringBuilder();
    if(messages instanceof Parcelable[]) for(Parcelable entry:(Parcelable[])messages) {
      if(!(entry instanceof Bundle)) continue;
      try {
        String sender="", content="", mime="";
        if(Build.VERSION.SDK_INT>=30) {
          java.util.List<Notification.MessagingStyle.Message> parsed=Notification.MessagingStyle.Message.getMessagesFromBundleArray(new Parcelable[]{entry});
          if(parsed.isEmpty()) continue;
          Notification.MessagingStyle.Message m=parsed.get(0);
          if(m.getSenderPerson()!=null) sender=text(m.getSenderPerson().getName());
          content=text(m.getText()); mime=m.getDataMimeType();
        } else {
          Bundle m=(Bundle)entry; sender=text(value(m,"sender")); content=text(value(m,"text"));
          if(Build.VERSION.SDK_INT>=28 && value(m,"sender_person") instanceof android.app.Person)
            sender=text(((android.app.Person)value(m,"sender_person")).getName());
          Object type=value(m,"type"); if(type instanceof String) mime=(String)type;
        }
        if(blank(content) && mime!=null && mime.startsWith("image/")) content=IMAGE;
        if(!blank(content)) line(joined,blank(sender)?content:sender+"："+content);
      } catch(RuntimeException ignored) { /* One malformed message must not discard the notification. */ }
    }
    if(joined.length()>0) body=joined.toString();
    body=first(body,text(value(b,Notification.EXTRA_SUMMARY_TEXT)));
    body=first(body,text(n.tickerText));
    boolean picture=value(b,Notification.EXTRA_PICTURE)!=null;
    if(Build.VERSION.SDK_INT>=31) picture|=value(b,Notification.EXTRA_PICTURE_ICON)!=null;
    if(blank(body) && picture) body=IMAGE;
    if(blank(title)) title="未提供通知標題";
    if(blank(body)) body="[來源未提供可讀取的通知文字]";
    return new NotificationText(title,body);
  }
  static boolean hasGroupChildren(StatusBarNotification summary,StatusBarNotification[] active) {
    if(active==null || (summary.getNotification().flags & Notification.FLAG_GROUP_SUMMARY)==0) return false;
    for(StatusBarNotification other:active) {
      if(other==null || other.getNotification()==null || other.getKey().equals(summary.getKey())) continue;
      if(summary.getPackageName().equals(other.getPackageName()) && summary.getUser().equals(other.getUser())
          && summary.getGroupKey().equals(other.getGroupKey())
          && (other.getNotification().flags & Notification.FLAG_GROUP_SUMMARY)==0) return true;
    }
    return false;
  }
}
