package dev.huang.notify;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class BackupFormat {
  private BackupFormat() {}

  public static void validate(JSONObject root) throws Exception {
    if (!"HuangNotify".equals(root.getString("format")) || root.getInt("version") != 1)
      throw new IllegalArgumentException("不支援的備份格式或版本");
    JSONArray rows = root.getJSONArray("records");
    if (rows.length() > 100000) throw new IllegalArgumentException("備份筆數超過上限");
    Set<String> ids = new HashSet<>(), versionIds = new HashSet<>();
    int versionCount = 0;
    for (int i = 0; i < rows.length(); i++) {
      JSONObject o = rows.getJSONObject(i);
      String id = o.getString("id");
      UUID.fromString(id);
      if (!ids.add(id)) throw new IllegalArgumentException("備份含重複識別碼");
      for (String n : new String[] {"nkey", "pkg", "app", "title", "body", "note"}) {
        if (!(o.get(n) instanceof String) || o.getString(n).length() > 100000)
          throw new IllegalArgumentException("通知欄位格式錯誤");
      }
      if (o.getString("pkg").trim().isEmpty() || o.getString("pkg").length() > 255)
        throw new IllegalArgumentException("App 識別碼錯誤");
      for (String n : new String[] {"posted", "first_seen", "updated", "removed"}) {
        Object value = o.get(n);
        if (!(value instanceof Number) || o.getLong(n) < 0 || o.getLong(n) > 32503680000000L)
          throw new IllegalArgumentException("通知日期錯誤");
      }
      if (o.getInt("favorite") < 0
          || o.getInt("favorite") > 1
          || o.getInt("reason") < 0
          || o.getInt("reason") > 1000) throw new IllegalArgumentException("通知狀態錯誤");
      JSONArray versions = o.getJSONArray("versions");
      if (versions.length() == 0 || (versionCount += versions.length()) > 200000)
        throw new IllegalArgumentException("更新歷程數量錯誤");
      for (int j = 0; j < versions.length(); j++) {
        JSONObject v = versions.getJSONObject(j);
        String vid = v.getString("id");
        UUID.fromString(vid);
        if (!versionIds.add(vid)) throw new IllegalArgumentException("重複的更新歷程識別碼");
        if (v.getLong("time") < 0 || v.getLong("time") > 32503680000000L)
          throw new IllegalArgumentException("更新日期錯誤");
        for (String n : new String[] {"title", "body"})
          if (!(v.get(n) instanceof String) || v.getString(n).length() > 100000)
            throw new IllegalArgumentException("更新內容錯誤");
      }
    }
    JSONObject settings = root.getJSONObject("settings");
    java.util.Iterator<String> names = settings.keys();
    while (names.hasNext()) {
      String n = names.next(), value = settings.getString(n);
      if (n.equals("retention")) {
        if (!java.util.Arrays.asList("0", "7", "30", "90", "365").contains(value))
          throw new IllegalArgumentException("保存期限錯誤");
      } else if (n.startsWith("exclude:") && n.length() > 8 && n.length() <= 263) {
        if (!value.equals("0") && !value.equals("1")) throw new IllegalArgumentException("排除設定錯誤");
      } else throw new IllegalArgumentException("未知的備份設定");
    }
  }
}
