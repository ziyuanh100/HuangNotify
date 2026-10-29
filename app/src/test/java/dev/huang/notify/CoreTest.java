package dev.huang.notify;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

public class CoreTest {
  @Test
  public void encryptedBackupRoundTrip() throws Exception {
    byte[] original = "通知紀錄：繁體中文 🔔\n第二行".getBytes(StandardCharsets.UTF_8);
    byte[] encrypted = BackupCrypto.encrypt(original, "correct horse".toCharArray());
    assertTrue(BackupCrypto.encrypted(encrypted));
    assertArrayEquals(original, BackupCrypto.decrypt(encrypted, "correct horse".toCharArray()));
    assertFalse(
        Arrays.equals(encrypted, BackupCrypto.encrypt(original, "correct horse".toCharArray())));
  }

  @Test
  public void wrongPasswordIsRejected() throws Exception {
    byte[] encrypted = BackupCrypto.encrypt(new byte[] {1, 2, 3}, "password123".toCharArray());
    assertThrows(Exception.class, () -> BackupCrypto.decrypt(encrypted, "different".toCharArray()));
  }

  @Test
  public void tamperedAndTruncatedBackupsAreRejected() throws Exception {
    byte[] encrypted = BackupCrypto.encrypt(new byte[] {1, 2, 3}, "password123".toCharArray());
    byte[] tampered = encrypted.clone();
    tampered[tampered.length - 1] ^= 1;
    assertThrows(
        Exception.class, () -> BackupCrypto.decrypt(tampered, "password123".toCharArray()));
    assertThrows(
        Exception.class,
        () -> BackupCrypto.decrypt(Arrays.copyOf(encrypted, 30), "password123".toCharArray()));
    byte[] header = encrypted.clone();
    header[10] ^= 1;
    assertThrows(Exception.class, () -> BackupCrypto.decrypt(header, "password123".toCharArray()));
  }

  @Test
  public void expensiveMaliciousKdfParametersAreRejected() throws Exception {
    byte[] encrypted = BackupCrypto.encrypt(new byte[] {1}, "password123".toCharArray());
    encrypted[5] = 0x7f;
    assertThrows(
        IllegalArgumentException.class,
        () -> BackupCrypto.decrypt(encrypted, "password123".toCharArray()));
  }

  @Test
  public void shortPasswordIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> BackupCrypto.encrypt(new byte[] {1}, "short".toCharArray()));
  }

  @Test
  public void contentHashSeparatesTitleAndBody() {
    assertNotEquals(EventPolicy.fingerprint("ab", "c"), EventPolicy.fingerprint("a", "bc"));
    assertEquals(EventPolicy.fingerprint("標題", "內容"), EventPolicy.fingerprint("標題", "內容"));
    assertNotEquals(EventPolicy.fingerprint("標題", "內容"), EventPolicy.fingerprint("標題", "更新"));
  }

  @Test
  public void searchEscapesWildcards() {
    assertEquals("%100\\%\\_\\\\%", EventPolicy.likePattern("100%_\\"));
  }
}
