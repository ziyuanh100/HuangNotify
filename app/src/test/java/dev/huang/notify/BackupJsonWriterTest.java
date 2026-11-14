package dev.huang.notify;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class BackupJsonWriterTest {
  @Test public void writesNestedArchiveAndEscapesValuesLikeTheExistingFormat() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (BackupJsonWriter writer = new BackupJsonWriter(bytes)) {
      writer.beginObject().name("records").beginArray().beginObject()
          .name("text").value("繁體/🙂\"\\\n\r\t\b\f\u0000\u001f\u2028\u2029")
          .name("empty").beginArray().endArray().endObject()
          .beginObject().name("count").value(Long.MIN_VALUE).endObject().endArray()
          .name("settings").beginObject().endObject().endObject();
    }
    assertEquals("{\"records\":[{\"text\":\"繁體/🙂\\\"\\\\\\n\\r\\t\\b\\f\\u0000\\u001f\\u2028\\u2029\",\"empty\":[]},"
        + "{\"count\":-9223372036854775808}],\"settings\":{}}", bytes.toString(StandardCharsets.UTF_8.name()));
  }

  @Test public void reusesTheImageBufferWithoutWritingTrailingBytes() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] full = new byte[NotificationImages.MAX_IMAGE_BYTES], small = {(byte) 255};
    new Random(13).nextBytes(full);
    try (BackupJsonWriter writer = new BackupJsonWriter(bytes)) {
      writer.beginArray();
      writer.image(full);
      writer.image(small);
      writer.endArray();
    }
    Base64.Encoder base64 = Base64.getEncoder();
    assertEquals("[\"" + base64.encodeToString(full) + "\",\""
        + base64.encodeToString(small) + "\"]", bytes.toString(StandardCharsets.UTF_8.name()));
  }
}
