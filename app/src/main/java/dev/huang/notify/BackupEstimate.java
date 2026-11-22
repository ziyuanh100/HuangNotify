package dev.huang.notify;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;
import java.util.Locale;

/** Estimates serialized size from a single read of the live database. */
final class BackupEstimate {
  private static final String[] HISTORY_TIME = {"time"};
  private static final String METADATA_HEADER =
      "{\"format\":\"HuangNotify\",\"version\":" + BackupArchive.VERSION
          + ",\"created\":0000000000000,\"records\":[],\"settings\":{}}";
  private static final String SQL =
      "WITH notifications AS (SELECT COUNT(*) AS rows, "
          + rowBytes(BackupArchive.TEXT, BackupArchive.TIMES, true) + " AS bytes FROM records),"
          + " history AS (SELECT COUNT(*) AS rows, "
          + rowBytes(BackupArchive.VERSION_TEXT, HISTORY_TIME, false) + " AS bytes FROM versions),"
          + " pictures AS (SELECT COUNT(*) AS rows, COALESCE(SUM(length(data)+"
          + BackupArchive.IMAGE_HEADER_BYTES + "),0) AS bytes FROM images i WHERE "
          + BackupArchive.REFERENCED_IMAGE + ")"
          + " SELECT notifications.rows, history.rows, pictures.rows,"
          + " notifications.bytes + " + referenceBytes("record_images") + ","
          + " history.bytes + " + referenceBytes("version_images") + ", pictures.bytes,"
          + " (SELECT COALESCE(SUM(length(CAST(name AS BLOB)) + length(CAST(value AS BLOB)) + 6),0)"
          + " FROM settings WHERE " + BackupArchive.BACKUP_SETTING + ")"
          + " FROM notifications, history, pictures";

  final long records, versions, images;
  final long recordBytes, historyBytes, imageBytes, totalBytes;

  private BackupEstimate(Cursor summary) {
    records = summary.getLong(0);
    versions = summary.getLong(1);
    images = summary.getLong(2);
    recordBytes = summary.getLong(3) + Math.max(0, records - 1);
    historyBytes = summary.getLong(4) + Math.max(0, versions - records);
    imageBytes = summary.getLong(5);
    totalBytes = recordBytes + historyBytes + imageBytes + summary.getLong(6)
        + BackupArchive.ENVELOPE_BYTES + METADATA_HEADER.length();
  }

  static BackupEstimate read(SQLiteDatabase db, CancellationSignal cancellation) {
    try (Cursor row = db.rawQuery(SQL, null, cancellation)) {
      row.moveToFirst();
      return new BackupEstimate(row);
    }
  }

  boolean exceedsCountLimit() {
    return versions > BackupArchive.MAX_VERSIONS || records > BackupArchive.MAX_RECORDS
        || images > BackupArchive.MAX_IMAGES;
  }

  static String size(long bytes) {
    if (bytes == 0) return "0 KiB";
    if (bytes < 1024) return "小於 1 KiB";
    if (bytes < 1024 * 1024) return String.format(Locale.TAIWAN, "%.1f KiB", bytes / 1024.0);
    return String.format(Locale.TAIWAN, "%.1f MiB", bytes / (1024.0 * 1024));
  }

  private static String rowBytes(String[] text, String[] numbers, boolean record) {
    // Count UTF-8 before JSON escaping, plus keys, quotes, commas and brackets.
    int syntax = 1 + "image_status".length() + 6 + "images".length() + 6;
    if (record) syntax += "versions".length() + 6;
    StringBuilder bytes = new StringBuilder("COALESCE(SUM(length(CAST(image_status AS BLOB))");
    for (String name : text) {
      syntax += name.length() + 6;
      bytes.append(" + length(CAST(").append(name).append(" AS BLOB))");
    }
    for (String name : numbers) {
      syntax += name.length() + 4;
      bytes.append(" + length(").append(name).append(")");
    }
    return bytes.append(" + ").append(syntax).append("),0)").toString();
  }

  private static String referenceBytes(String table) {
    // Each quoted SHA-256 is 66 bytes, with a comma after the first reference.
    return "(SELECT COALESCE(SUM(CASE WHEN position=0 THEN 66 ELSE 67 END),0) FROM "
        + table + ")";
  }
}
