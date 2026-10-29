package dev.huang.notify;

import java.math.BigInteger;
import java.util.regex.Pattern;

/** Strict SemVer ordering, independent of release dates or API result ordering. */
public final class ReleaseVersion implements Comparable<ReleaseVersion> {
  private static final Pattern VALID = Pattern.compile(
      "v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?");
  private final BigInteger[] numbers;
  private final String[] pre;
  private ReleaseVersion(BigInteger[] numbers, String[] pre) { this.numbers = numbers; this.pre = pre; }
  public static ReleaseVersion parse(String value) {
    if (value == null || value.length() > 160) return null;
    java.util.regex.Matcher m = VALID.matcher(value);
    if (!m.matches()) return null;
    String[] pre = m.group(4) == null ? new String[0] : m.group(4).split("\\.");
    for (String part : pre)
      if (part.matches("[0-9]+") && part.length() > 1 && part.startsWith("0")) return null;
    return new ReleaseVersion(new BigInteger[] {new BigInteger(m.group(1)), new BigInteger(m.group(2)), new BigInteger(m.group(3))}, pre);
  }
  public boolean isPreview() { return pre.length > 0; }
  @Override public int compareTo(ReleaseVersion other) {
    for (int i = 0; i < 3; i++) { int c = numbers[i].compareTo(other.numbers[i]); if (c != 0) return c; }
    if (pre.length == 0 || other.pre.length == 0)
      return pre.length == other.pre.length ? 0 : pre.length == 0 ? 1 : -1;
    for (int i = 0; i < Math.min(pre.length, other.pre.length); i++) {
      String a = pre[i], b = other.pre[i];
      boolean an = a.matches("[0-9]+"), bn = b.matches("[0-9]+");
      int c = an && bn ? new BigInteger(a).compareTo(new BigInteger(b)) : an != bn ? (an ? -1 : 1) : a.compareTo(b);
      if (c != 0) return c;
    }
    return Integer.compare(pre.length, other.pre.length);
  }
}
