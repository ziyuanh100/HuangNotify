package dev.huang.notify;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class EventPolicy {
  private EventPolicy() {}

  public static String fingerprint(String title, String text) {
    try {
      byte[] bytes =
          MessageDigest.getInstance("SHA-256")
              .digest((title.length() + ":" + title + text).getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
      return out.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String removalLabel(int reason) {
    return switch (reason) {
      case 1 -> "點擊通知後移除";
      case 2 -> "手動移除";
      case 3 -> "清除全部通知";
      case 8, 9 -> "來源 App 移除";
      case 10, 11, 12 -> "群組通知調整";
      case 18 -> "通知逾時";
      case 0 -> "目前狀態未知";
      default -> "系統移除（" + reason + "）";
    };
  }

  public static String likePattern(String text) {
    return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
  }
}
