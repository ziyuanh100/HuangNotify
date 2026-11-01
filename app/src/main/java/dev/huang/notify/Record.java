package dev.huang.notify;

public final class Record {
  public String id, key, pkg, app, title, text, note;
  public long posted, firstSeen, updated, removed;
  public boolean favorite, active;
  public int reason, versions, imageCount;
  public String imageStatus="none";
}
