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
    if (!"HuangNotify".equals(root.getString("format")))
      throw new IllegalArgumentException("不支援的備份格式或版本");
    long format=integer(root,"version",1,2);
    if(root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>BackupCrypto.MAX_BYTES)
      throw new IllegalArgumentException("備份超過 20 MB");
    Set<String> imageIds=new HashSet<>(), usedImages=new HashSet<>();
    if(format==2) {
      JSONArray pictures=root.getJSONArray("images");
      if(pictures.length()>100000)throw new IllegalArgumentException("圖片數量超過上限");
      for(int i=0;i<pictures.length();i++) {
        JSONObject p=pictures.getJSONObject(i);
        Object hash=p.get("sha256"),data=p.get("data");
        if(!(hash instanceof String) || !((String)hash).matches("[0-9a-f]{64}") || !imageIds.add((String)hash)
            || !"image/png".equals(p.get("mime")) || !(data instanceof String)
            || ((String)data).length()>((NotificationImages.MAX_IMAGE_BYTES+2)/3)*4)
          throw new IllegalArgumentException("備份圖片欄位錯誤");
        int width=(int)integer(p,"width",1,NotificationImages.MAX_EDGE),height=(int)integer(p,"height",1,NotificationImages.MAX_EDGE);
        try { NotificationImages.validate(java.util.Base64.getDecoder().decode((String)data),(String)hash,width,height); }
        catch(Exception | OutOfMemoryError e) { throw new IllegalArgumentException("備份圖片損壞或校驗不符",e); }
      }
    } else if(root.has("images"))throw new IllegalArgumentException("含圖片的備份必須使用 v2");
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
      validateImages(o,format,imageIds,usedImages);
      JSONArray versions = o.getJSONArray("versions");
      if (versions.length() == 0 || (versionCount += versions.length()) > 200000)
        throw new IllegalArgumentException("更新歷程數量錯誤");
      for (int j = 0; j < versions.length(); j++) {
        JSONObject v = versions.getJSONObject(j);
        String vid = v.getString("id");
        UUID.fromString(vid);
        if (!versionIds.add(vid)) throw new IllegalArgumentException("重複的更新歷程識別碼");
        integer(v, "time", 0, 32503680000000L);
        validateImages(v,format,imageIds,usedImages);
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
      } else if (n.equals("save_images") || (n.startsWith("exclude:") && n.length() > 8 && n.length() <= 263)) {
        if (!value.equals("0") && !value.equals("1")) throw new IllegalArgumentException("排除設定錯誤");
      } else throw new IllegalArgumentException("未知的備份設定");
    }
    if(!usedImages.equals(imageIds))throw new IllegalArgumentException("備份含未引用的圖片");
  }

  private static void validateImages(JSONObject object,long format,Set<String> images,Set<String> used) throws Exception {
    if(format==1) {
      if(object.has("images") || object.has("image_status"))throw new IllegalArgumentException("圖片欄位需要 v2");
      return;
    }
    Object status=object.get("image_status");
    if(!(status instanceof String) || !NotificationImages.STATUSES.contains(status))throw new IllegalArgumentException("圖片狀態錯誤");
    JSONArray refs=object.getJSONArray("images"); Set<String> seen=new HashSet<>();
    if(refs.length()>NotificationImages.MAX_IMAGES)throw new IllegalArgumentException("每則通知圖片超過上限");
    for(int i=0;i<refs.length();i++) {
      Object ref=refs.get(i);
      if(!(ref instanceof String) || !images.contains(ref) || !seen.add((String)ref))throw new IllegalArgumentException("圖片引用遺失或重複");
      used.add((String)ref);
    }
    if((refs.length()>0 && !(status.equals("saved") || status.equals("partial"))) || (refs.length()==0 && (status.equals("saved") || status.equals("partial"))))
      throw new IllegalArgumentException("圖片數量與狀態不符");
  }
}
