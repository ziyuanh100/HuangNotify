package dev.huang.notify;
import org.junit.Test;
import static org.junit.Assert.*;
public class ReleaseVersionTest {
  @Test public void comparesNumerically() {
    assertTrue(ReleaseVersion.parse("v0.10.0").compareTo(ReleaseVersion.parse("0.9.9")) > 0);
    assertTrue(ReleaseVersion.parse("1.0.0-alpha.10").compareTo(ReleaseVersion.parse("1.0.0-alpha.2")) > 0);
  }
  @Test public void ordersPrereleasesAndIgnoresMetadata() {
    String[] versions = {"1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-rc.1", "1.0.0"};
    for (int i = 1; i < versions.length; i++) assertTrue(ReleaseVersion.parse(versions[i]).compareTo(ReleaseVersion.parse(versions[i-1])) > 0);
    assertEquals(0, ReleaseVersion.parse("1.0.0+build.7").compareTo(ReleaseVersion.parse("v1.0.0+build.8")));
  }
  @Test public void rejectsMalformedVersions() {
    for (String v : new String[] {"", "1.0", "1.00.0", "1.0.0-alpha.01", "1.0.0/evil", "-1.0.0", "1.0.0-"}) assertNull(v, ReleaseVersion.parse(v));
    assertNull(ReleaseVersion.parse(null));
  }
}
