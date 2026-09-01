package com.gimle.core.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.logging.LogFileReader.LogPage;
import com.gimle.core.protocol.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Content filtering (level threshold + substring) as {@link LogFileReader} actually applies it,
 * including alongside the timestamp cursor it has always had -- both together are what an operator
 * hunting one line in a high-volume log ends up issuing.
 */
class LogFileReaderFilteringTest {

  @TempDir Path dir;

  private Path file;

  /** Writes one structured JSON line in exactly the shape {@code JsonLogEncoder} emits. */
  private void write(String timestamp, String level, String message) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("timestamp", timestamp);
    line.put("level", level);
    line.put("logger", "com.example.Handler");
    line.put("message", message);
    append(Json.write(line));
  }

  private void writeRaw(String text) {
    append(text);
  }

  private void append(String text) {
    if (file == null) {
      file = dir.resolve("platform.log");
    }
    try {
      Files.writeString(
          file,
          text + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void writeSampleStream() {
    write("2026-08-10T10:00:00Z", "DEBUG", "cache hit");
    write("2026-08-10T10:00:01Z", "INFO", "order accepted");
    write("2026-08-10T10:00:02Z", "WARN", "queue depth approaching threshold");
    write("2026-08-10T10:00:03Z", "INFO", "handled request in 42ms");
    write("2026-08-10T10:00:04Z", "ERROR", "downstream call timed out");
  }

  private static List<String> messagesOf(List<Map<String, Object>> lines) {
    List<String> messages = new ArrayList<>();
    for (Map<String, Object> line : lines) {
      messages.add(String.valueOf(line.get("message")));
    }
    return messages;
  }

  @Test
  void read_older_with_a_level_threshold_keeps_that_level_and_everything_above() {
    writeSampleStream();

    LogPage page = LogFileReader.readOlder(file, 1, null, 100, LogFilter.of("WARN", null));

    assertEquals(
        List.of("queue depth approaching threshold", "downstream call timed out"),
        messagesOf(page.lines()));
  }

  @Test
  void read_older_with_a_text_filter_keeps_only_lines_carrying_that_text() {
    writeSampleStream();

    LogPage page = LogFileReader.readOlder(file, 1, null, 100, LogFilter.of(null, "TIMED OUT"));

    assertEquals(List.of("downstream call timed out"), messagesOf(page.lines()));
  }

  @Test
  void a_filter_matching_nothing_yields_an_empty_page_rather_than_an_error() {
    writeSampleStream();

    LogPage page =
        LogFileReader.readOlder(file, 1, null, 100, LogFilter.of("ERROR", "no such text anywhere"));

    assertTrue(page.lines().isEmpty());
    // Nothing older to page back to, and no newer line to resume from -- both cursors stay null
    // (the request carried none), so a caller can tell "nothing matched" from "there's more".
    assertNull(page.olderCursor());
    assertNull(page.newerCursor());
  }

  @Test
  void the_limit_counts_matching_lines_only_not_raw_lines_scanned() {
    // 40 INFO lines drown 2 ERRORs: a limit applied before filtering would return the last 3 raw
    // lines (all INFO, none matching), which is exactly the failure mode this ordering avoids.
    write("2026-08-10T10:00:00Z", "ERROR", "first failure");
    for (int i = 0; i < 40; i++) {
      write("2026-08-10T10:01:" + String.format("%02d", i) + "Z", "INFO", "chatter " + i);
    }
    write("2026-08-10T10:02:00Z", "ERROR", "second failure");

    LogPage page = LogFileReader.readOlder(file, 1, null, 3, LogFilter.of("ERROR", null));

    assertEquals(List.of("first failure", "second failure"), messagesOf(page.lines()));
  }

  @Test
  void a_filter_composes_with_the_backward_paging_cursor() {
    writeSampleStream();

    LogPage page =
        LogFileReader.readOlder(file, 1, "2026-08-10T10:00:03Z", 100, LogFilter.of("INFO", null));

    // Strictly before 10:00:03 and at least INFO: the 10:00:01 INFO and the 10:00:02 WARN, never
    // the 10:00:04 ERROR (newer than the cursor) or the 10:00:00 DEBUG (below the threshold).
    assertEquals(
        List.of("order accepted", "queue depth approaching threshold"), messagesOf(page.lines()));
  }

  @Test
  void a_filter_composes_with_the_forward_since_cursor() {
    writeSampleStream();

    List<Map<String, Object>> lines =
        LogFileReader.readAfter(file, 1, "2026-08-10T10:00:01Z", LogFilter.of("WARN", null));

    assertEquals(
        List.of("queue depth approaching threshold", "downstream call timed out"),
        messagesOf(lines));
  }

  @Test
  void an_unstructured_raw_capture_line_is_dropped_by_a_level_threshold_but_reachable_by_text() {
    writeRaw("kernel: [ 1234.567] cgroup limit reached for slice gimle.slice");
    write("2026-08-10T10:00:04Z", "ERROR", "downstream call timed out");

    assertEquals(
        1, LogFileReader.readOlder(file, 1, null, 100, LogFilter.of("TRACE", null)).lines().size());
    List<Map<String, Object>> byText =
        LogFileReader.readOlder(file, 1, null, 100, LogFilter.of(null, "cgroup")).lines();
    assertEquals(1, byText.size());
    assertTrue(String.valueOf(byText.get(0).get("raw")).contains("cgroup"));
  }

  @Test
  void follow_streams_only_matching_lines_and_still_advances_past_the_rest() throws Exception {
    writeSampleStream();
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    // cursor before every line on disk, so the tail replays the whole file first.
    Thread follower =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    LogFileReader.streamFollow(
                        file,
                        1,
                        "2026-08-10T09:59:59Z",
                        Duration.ofMillis(20),
                        out,
                        LogFilter.of("WARN", null));
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });
    try {
      awaitLineCount(out, 2);
      // A brand-new INFO line must not appear, and must not stall the tail: the next ERROR after
      // it still arrives, which only happens if the cursor advanced past the suppressed line.
      write("2026-08-10T10:00:05Z", "INFO", "more chatter");
      write("2026-08-10T10:00:06Z", "ERROR", "second failure");
      awaitLineCount(out, 3);

      List<String> streamed = streamedMessages(out);
      assertEquals(
          List.of(
              "queue depth approaching threshold", "downstream call timed out", "second failure"),
          streamed);
      assertFalse(streamed.contains("more chatter"));
    } finally {
      follower.interrupt();
      follower.join(Duration.ofSeconds(5));
    }
  }

  private static void awaitLineCount(ByteArrayOutputStream out, int expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (streamedMessages(out).size() >= expected) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError(
        "follow stream never reached " + expected + " lines; got " + streamedMessages(out));
  }

  private static List<String> streamedMessages(ByteArrayOutputStream out) {
    List<String> messages = new ArrayList<>();
    for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
      if (!line.isBlank()) {
        messages.add(String.valueOf(Json.asObject(Json.parse(line)).get("message")));
      }
    }
    return messages;
  }
}
