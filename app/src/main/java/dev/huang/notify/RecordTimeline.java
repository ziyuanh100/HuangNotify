package dev.huang.notify;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Recycled notification cards with a heading on the first record of each local day. */
final class RecordTimeline extends BaseAdapter {
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.TAIWAN);
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日", Locale.TAIWAN);
  private static final DateTimeFormatter OLDER = DateTimeFormatter.ofPattern("uuuu年M月d日", Locale.TAIWAN);
  private final Ui u;
  private final List<Record> records;
  private final int inset;
  private ZoneId zone;
  private LocalDate today;

  RecordTimeline(Ui u, List<Record> records, int inset) {
    this.u = u;
    this.records = records;
    this.inset = inset;
    updateDate();
  }

  @Override public int getCount() { return records.size(); }
  @Override public Record getItem(int position) { return records.get(position); }
  @Override public long getItemId(int position) { return position; }

  @Override public void notifyDataSetChanged() {
    updateDate();
    super.notifyDataSetChanged();
  }

  private void updateDate() {
    zone = ZoneId.systemDefault();
    today = LocalDate.now(zone);
  }

  @Override
  public View getView(int position, View convert, ViewGroup parent) {
    Card card = convert == null ? new Card() : (Card) convert;
    Record record = getItem(position);
    var time = Instant.ofEpochMilli(record.firstSeen).atZone(zone);
    LocalDate day = time.toLocalDate();
    boolean startOfDay = position == 0
        || !day.equals(Instant.ofEpochMilli(getItem(position - 1).firstSeen).atZone(zone).toLocalDate());
    card.heading.setVisibility(startOfDay ? View.VISIBLE : View.GONE);
    if (startOfDay) {
      if (day.equals(today)) card.heading.setText("今天 · " + DAY.format(day));
      else if (day.equals(today.minusDays(1))) card.heading.setText("昨天 · " + DAY.format(day));
      else card.heading.setText((day.getYear() == today.getYear() ? DAY : OLDER).format(day));
    }

    card.icon.setImageDrawable(u.appIcon(record.pkg));
    card.source.setText(record.app);
    card.time.setText(TIME.format(time));
    card.bookmark.setVisibility(record.favorite ? View.VISIBLE : View.GONE);
    card.title.setText(record.title.isEmpty() ? "無標題的通知" : record.title);
    card.body.setText(record.text);
    card.body.setVisibility(record.text.isEmpty() ? View.GONE : View.VISIBLE);
    StringBuilder meta = new StringBuilder();
    if (record.favorite) meta.append("已收藏 · ");
    if (record.active && CaptureService.connected) meta.append("仍在通知列");
    else if (record.removed > 0) meta.append("已移除 · 紀錄已保存");
    else meta.append("已保存");
    if (record.imageCount > 0) meta.append(" · ").append(record.imageCount).append(" 張圖片");
    else if (!"none".equals(record.imageStatus)) meta.append(" · 圖片未保存");
    if (record.versions > 1) meta.append(" · ").append(record.versions).append(" 個版本");
    if (!record.note.isEmpty()) meta.append(" · 有備註");
    card.meta.setText(meta);
    return card;
  }

  private final class Card extends LinearLayout {
    final TextView heading, source, time, title, body, meta;
    final ImageView icon;
    final View bookmark;

    Card() {
      super(u.context);
      setOrientation(VERTICAL);
      setPadding(inset, u.d(4), inset, u.d(4));

      heading = u.text("", 13, u.accent, true);
      heading.setPadding(u.d(2), u.d(7), 0, u.d(8));
      u.heading(heading);
      addView(heading);

      LinearLayout row = u.row();
      row.setGravity(Gravity.TOP);
      row.setPadding(u.d(12), u.d(12), u.d(12), u.d(12));
      row.setBackground(u.outline(u.surface, 20));
      row.setForeground(u.rippleDrawable(null, 20, false));
      row.setDuplicateParentStateEnabled(true);
      addView(row, new LayoutParams(-1, -2));

      FrameLayout tile = new FrameLayout(u.context);
      tile.setBackground(u.shape(u.pale, 13));
      icon = new ImageView(u.context);
      icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      tile.addView(icon, new FrameLayout.LayoutParams(u.d(28), u.d(28), Gravity.CENTER));
      row.addView(tile, new LayoutParams(u.d(42), u.d(42)));

      LinearLayout copy = u.column();
      LayoutParams copyParams = new LayoutParams(0, -2, 1);
      copyParams.setMarginStart(u.d(11));
      row.addView(copy, copyParams);
      LinearLayout top = u.row();
      source = u.text("", 13, u.muted, true);
      source.setSingleLine();
      source.setEllipsize(TextUtils.TruncateAt.END);
      LayoutParams sourceParams = new LayoutParams(0, -2, 1);
      sourceParams.setMarginEnd(u.d(6));
      top.addView(source, sourceParams);
      time = u.text("", 12, u.muted, false);
      top.addView(time);
      bookmark = u.icon("starFilled", u.accent);
      LayoutParams bookmarkParams = new LayoutParams(u.d(16), u.d(16));
      bookmarkParams.setMarginStart(u.d(5));
      top.addView(bookmark, bookmarkParams);
      copy.addView(top);

      title = u.text("", 16, u.ink, true);
      title.setPadding(0, u.d(3), 0, 0);
      title.setMaxLines(2);
      title.setEllipsize(TextUtils.TruncateAt.END);
      copy.addView(title);

      body = u.text("", 14, u.muted, false);
      body.setPadding(0, u.d(3), 0, 0);
      body.setMaxLines(2);
      body.setEllipsize(TextUtils.TruncateAt.END);
      copy.addView(body);

      meta = u.text("", 12, u.accent, false);
      meta.setPadding(0, u.d(7), 0, 0);
      copy.addView(meta);
    }
  }
}
