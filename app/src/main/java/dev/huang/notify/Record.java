package dev.huang.notify;

public final class Record {
  public String id, key, pkg, app, title, text, note;
  public long posted, firstSeen, updated, removed;
  public boolean favorite, active;
  public int reason, versions, imageCount;
  public String imageStatus="none";
  HistoryMatch historyMatch;

  static final class HistoryMatch {
    final String id, title, body;
    final long time;

    HistoryMatch(String id, String title, String body, long time) {
      this.id = id;
      this.title = title;
      this.body = body;
      this.time = time;
    }
  }
}
