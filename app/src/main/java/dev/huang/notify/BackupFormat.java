package dev.huang.notify;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class BackupFormat {
  private BackupFormat() {}

  private static long integer(JSONObject object, String name, long min, long max) throws Exception {
    Object value=object.get(name);
    if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException("備份整數欄位格式錯誤："+name);
    long number=((Number)value).longValue();
    if (number<min || number>max) throw new IllegalArgumentException("備份數值超出範圍："+name);
    return number;
  }

  public static void validate(JSONObject root) throws Exception {
    if (!"HuangNotify".equals(root.getString("format")) || integer(root, "version", 1, 1) != 1)
      throw new IllegalArgumentException("不支援的備份格式或版本");
    integer(root, "created", 0, 32503680000000L);
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
        integer(o, n, 0, 32503680000000L);
      }
      integer(o, "favorite", 0, 1); integer(o, "reason", 0, 1000);
      JSONArray versions = o.getJSONArray("versions");
      if (versions.length() == 0 || (versionCount += versions.length()) > 200000)
        throw new IllegalArgumentException("更新歷程數量錯誤");
      for (int j = 0; j < versions.length(); j++) {
        JSONObject v = versions.getJSONObject(j);
        String vid = v.getString("id");
        UUID.fromString(vid);
        if (!versionIds.add(vid)) throw new IllegalArgumentException("重複的更新歷程識別碼");
        integer(v, "time", 0, 32503680000000L);
        for (String n : new String[] {"title", "body"})
          if (!(v.get(n) instanceof String) || v.getString(n).length() > 100000)
            throw new IllegalArgumentException("更新內容錯誤");
      }
    }
    JSONObject settings = root.getJSONObject("settings");
    java.util.Iterator<String> names = settings.keys();
    while (names.hasNext()) {
      String n = names.next();
      if (!(settings.get(n) instanceof String)) throw new IllegalArgumentException("備份設定格式錯誤");
      String value = settings.getString(n);
      if (n.equals("retention")) {
        if (!java.util.Arrays.asList("0", "7", "30", "90", "365").contains(value))
          throw new IllegalArgumentException("保存期限錯誤");
      } else if (n.startsWith("exclude:") && n.length() > 8 && n.length() <= 263) {
        if (!value.equals("0") && !value.equals("1")) throw new IllegalArgumentException("排除設定錯誤");
      } else throw new IllegalArgumentException("未知的備份設定");
    }
  }
}
