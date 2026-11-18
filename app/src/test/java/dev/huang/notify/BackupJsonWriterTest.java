package dev.huang.notify;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
}
