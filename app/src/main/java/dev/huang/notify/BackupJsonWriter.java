package dev.huang.notify;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Emits the backup schema in UTF-8, writing each string and encoded image in bulk. */
final class BackupJsonWriter implements AutoCloseable {
  private static final String[] ESCAPES = new String[128];
  static {
    String hex = "0123456789abcdef";
    for (int i = 0; i < 32; i++) {
      ESCAPES[i] = "\\u00" + hex.charAt(i / 16) + hex.charAt(i % 16);
    }
    ESCAPES['"'] = "\\\"";
    ESCAPES['\\'] = "\\\\";
    ESCAPES['\b'] = "\\b";
    ESCAPES['\f'] = "\\f";
    ESCAPES['\n'] = "\\n";
    ESCAPES['\r'] = "\\r";
    ESCAPES['\t'] = "\\t";
  }
  private static final Base64.Encoder BASE64 = Base64.getEncoder();
  private final BufferedOutputStream out;
  private byte[] encodedImage;
  private boolean comma;

  BackupJsonWriter(OutputStream destination) {
    out = new BufferedOutputStream(destination, 64 * 1024);
  }

  BackupJsonWriter beginObject() throws IOException { return open('{'); }
  BackupJsonWriter beginArray() throws IOException { return open('['); }
  BackupJsonWriter endObject() throws IOException { return end('}'); }
  BackupJsonWriter endArray() throws IOException { return end(']'); }

  private BackupJsonWriter open(int token) throws IOException {
    separate();
    out.write(token);
    comma = false;
    return this;
  }

  private BackupJsonWriter end(int token) throws IOException {
    out.write(token);
    comma = true;
    return this;
  }

  private void separate() throws IOException {
    if (comma) out.write(',');
    comma = true;
  }

  BackupJsonWriter name(String name) throws IOException {
    separate();
    quoted(name);
    out.write(':');
    comma = false;
    return this;
  }

  BackupJsonWriter value(String value) throws IOException {
    separate();
    if (value == null) utf8("null");
    else quoted(value);
    return this;
  }

  BackupJsonWriter value(long value) throws IOException {
    separate();
    utf8(Long.toString(value));
    return this;
  }

  void image(byte[] bytes) throws IOException {
    if (encodedImage == null) {
      encodedImage = new byte[((NotificationImages.MAX_IMAGE_BYTES + 2) / 3) * 4];
    }
    separate();
    out.write('"');
    out.write(encodedImage, 0, BASE64.encode(bytes, encodedImage));
    out.write('"');
  }

  private void quoted(String value) throws IOException {
    StringBuilder escaped = null;
    int start = 0;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      String replacement = c < ESCAPES.length ? ESCAPES[c]
          : c == '\u2028' ? "\\u2028" : c == '\u2029' ? "\\u2029" : null;
      if (replacement == null) continue;
      if (escaped == null) escaped = new StringBuilder(value.length() + 16);
      escaped.append(value, start, i).append(replacement);
      start = i + 1;
    }
    out.write('"');
    utf8(escaped == null ? value : escaped.append(value, start, value.length()).toString());
    out.write('"');
  }

  private void utf8(String value) throws IOException {
    out.write(value.getBytes(StandardCharsets.UTF_8));
  }

  @Override public void close() throws IOException { out.close(); }
}
