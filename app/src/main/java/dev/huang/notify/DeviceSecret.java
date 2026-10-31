package dev.huang.notify;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Wrap only the recurring backup password with a non-exportable device key. */
final class DeviceSecret {
  private static final String ALIAS = "huangnotify.backup.password.v1";
  private static synchronized SecretKey key(boolean create) throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
    if (!store.containsAlias(ALIAS)) {
      if (!create) throw new IllegalStateException("請重新設定自動備份密碼");
      KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
      generator.generateKey();
    }
    return (SecretKey) store.getKey(ALIAS, null);
  }
  static String wrap(char[] password) throws Exception {
    java.nio.ByteBuffer encoded = java.nio.charset.StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(password));
    byte[] raw = new byte[encoded.remaining()]; encoded.get(raw);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(true));
      byte[] encrypted = cipher.doFinal(raw);
      return Base64.encodeToString(ByteBuffer.allocate(12 + encrypted.length).put(cipher.getIV()).put(encrypted).array(), Base64.NO_WRAP);
    } finally { java.util.Arrays.fill(raw, (byte)0); if (encoded.hasArray()) java.util.Arrays.fill(encoded.array(), (byte)0); }
  }
  static char[] unwrap(String value) throws Exception {
    byte[] wrapped = Base64.decode(value, Base64.NO_WRAP);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, wrapped, 0, 12));
    byte[] raw = cipher.doFinal(wrapped, 12, wrapped.length - 12);
    try { java.nio.CharBuffer decoded = java.nio.charset.StandardCharsets.UTF_8.decode(ByteBuffer.wrap(raw));
      char[] password = new char[decoded.remaining()]; decoded.get(password);
      if (decoded.hasArray()) java.util.Arrays.fill(decoded.array(), '\0'); return password;
    } finally { java.util.Arrays.fill(raw, (byte)0); }
  }
}
