package com.gimle.core.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.gimle.core.logging.LogFileReader.LogPage;
import com.gimle.core.protocol.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A worker's captured stdout is written as a record of its own -- a timestamp, the SYSTEM category
 * and the line itself -- rather than as a structured log event, so it carries no level. Reading one
 * back must return that record as it was written; wrapping it a second time buries the real line
 * inside a JSON string and stamps the read time in front of the capture time.
 */
class LogFileReaderSystemCaptureTest {

  @TempDir Path dir;

  @Test
  void a_captured_stdout_record_is_read_back_as_the_record_it_was_written_as() {
    Path file = dir.resolve("worker-system.log");
    Instant capturedAt = Instant.parse("2026-09-01T10:00:00Z");
    writeCapture(file, capturedAt, "Exception in thread \"main\" java.lang.IllegalStateException");

    LogPage page = LogFileReader.readOlder(file, 1, null, 10, LogFilter.NONE);

    assertEquals(1, page.lines().size());
    Map<String, Object> line = page.lines().get(0);
    assertEquals(capturedAt.toString(), line.get("timestamp"));
    assertEquals("SYSTEM", line.get("category"));
    assertEquals("Exception in thread \"main\" java.lang.IllegalStateException", line.get("raw"));
    assertNull(line.get("level"));
  }

  /** The exact shape a worker supervisor writes for one captured stdout line. */
  private void writeCapture(Path file, Instant timestamp, String line) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("timestamp", timestamp.toString());
    entry.put("category", "SYSTEM");
    entry.put("raw", line);
    try {
      Files.writeString(file, Json.write(entry) + System.lineSeparator(), StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
