package dev.huang.notify;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Portable password-encrypted container. Header is authenticated as GCM AAD. */
public final class BackupCrypto {
  private static final byte[] MAGIC = {'H', 'N', 'B', 'K', 1};
  private static final int ITERATIONS = 210000;
  public static final int MAX_BYTES = 20 * 1024 * 1024;

  private BackupCrypto() {}

  public static boolean encrypted(byte[] bytes) {
    return bytes.length >= 5 && Arrays.equals(Arrays.copyOf(bytes, 5), MAGIC);
  }

  public static byte[] encrypt(byte[] bytes, char[] password) throws Exception {
    if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("備份超過 20 MB，請先整理紀錄");
    if (password.length < 8) throw new IllegalArgumentException("備份密碼至少需要 8 個字元");
    SecureRandom random = new SecureRandom();
    byte[] salt = new byte[16], nonce = new byte[12];
    random.nextBytes(salt);
    random.nextBytes(nonce);
    byte[] header =
        ByteBuffer.allocate(37).put(MAGIC).putInt(ITERATIONS).put(salt).put(nonce).array();
    Cipher cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, nonce);
    cipher.updateAAD(header);
    return ByteBuffer.allocate(header.length + bytes.length + 16)
        .put(header)
        .put(cipher.doFinal(bytes))
        .array();
  }

  public static byte[] decrypt(byte[] bytes, char[] password) throws Exception {
    if (!encrypted(bytes) || bytes.length < 53 || bytes.length > MAX_BYTES + 53)
      throw new IllegalArgumentException("備份檔格式或大小不正確");
    ByteBuffer input = ByteBuffer.wrap(bytes);
    input.position(5);
    if (input.getInt() != ITERATIONS) throw new IllegalArgumentException("不支援的加密版本");
    byte[] salt = new byte[16], nonce = new byte[12];
    input.get(salt);
    input.get(nonce);
    Cipher cipher = cipher(Cipher.DECRYPT_MODE, password, salt, nonce);
    cipher.updateAAD(Arrays.copyOf(bytes, 37));
    return cipher.doFinal(bytes, 37, bytes.length - 37);
  }

  private static Cipher cipher(int mode, char[] password, byte[] salt, byte[] nonce)
      throws Exception {
    PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
    byte[] key = null;
    try {
      key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      return cipher;
    } finally {
      spec.clearPassword();
      if (key != null) Arrays.fill(key, (byte) 0);
    }
  }
}
