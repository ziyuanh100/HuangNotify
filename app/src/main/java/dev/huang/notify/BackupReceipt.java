package dev.huang.notify;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

/** Last verified file metadata. A receipt is independent of the currently configured folder. */
final class BackupReceipt {
  final long time, bytes;
  final String location;
  final boolean automatic;

  private BackupReceipt(SharedPreferences prefs, boolean automatic) {
    time = prefs.getLong("verified_time", automatic ? prefs.getLong("success", 0) : 0);
    bytes = prefs.getLong("verified_bytes", 0);
    location = prefs.getString("verified_location", "");
    this.automatic = automatic;
  }

  static BackupReceipt latest(Context context) {
    BackupReceipt manual = new BackupReceipt(context.getSharedPreferences("manual_backup", 0), false);
    BackupReceipt automatic = new BackupReceipt(AutoBackup.prefs(context), true);
    return automatic.time > manual.time ? automatic : manual;
  }

  static SharedPreferences.Editor record(SharedPreferences.Editor edit, long time, long bytes, String location) {
    return edit.putLong("verified_time", time).putLong("verified_bytes", bytes)
        .putString("verified_location", location);
  }

  static void manual(Context context, Uri document, long bytes) {
    record(context.getSharedPreferences("manual_backup", 0).edit(), System.currentTimeMillis(),
        bytes, location(context, document)).apply();
  }

  static String location(Context context, Uri uri) {
    boolean folder = DocumentsContract.isTreeUri(uri) && !DocumentsContract.isDocumentUri(context, uri);
    Uri document = folder ? DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) : uri;
    if ("com.android.externalstorage.documents".equals(uri.getAuthority())
        && DocumentsContract.isDocumentUri(context, document)) {
      String id = DocumentsContract.getDocumentId(document);
      if (id.startsWith("primary:")) return "內部儲存空間" + (id.length() == 8 ? "" : " / " + id.substring(8));
    }
    try (Cursor cursor = context.getContentResolver().query(document,
        new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        String name = cursor.getString(0);
        if (name != null && !name.isEmpty()) return name;
      }
    } catch (RuntimeException ignored) {
      // Some document providers expose only file streams.
    }
    return folder ? "已選擇的資料夾" : "已選擇的檔案";
  }
}
