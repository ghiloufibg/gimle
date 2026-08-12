package com.gimle.muninn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.logging.LogFileReader.LogPage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MuninnDayFileStoreTest {

  @TempDir Path tempDir;

  private MuninnDayFileStore store;

  @BeforeEach
  void setUp() {
    store = new MuninnDayFileStore(tempDir);
  }

  private static Map<String, Object> line(String timestamp, String message) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("timestamp", timestamp);
    line.put("message", message);
    return line;
  }

  @Test
  void lines_spanning_two_days_land_in_two_separate_day_files() throws Exception {
    store.appendLines(
        "logs/nodes/n1/PLATFORM",
        List.of(line("2026-08-10T10:00:00Z", "day one"), line("2026-08-11T10:00:00Z", "day two")));

    Path dir = tempDir.resolve("logs/nodes/n1/PLATFORM");
    assertTrue(Files.isRegularFile(dir.resolve("2026-08-10.log")));
    assertTrue(Files.isRegularFile(dir.resolve("2026-08-11.log")));
  }

  @Test
  void a_late_arriving_line_appends_into_the_existing_day_file_rather_than_overwriting_it()
      throws Exception {
    store.appendLines("logs/nodes/n1/PLATFORM", List.of(line("2026-08-10T10:00:00Z", "first")));
    store.appendLines("logs/nodes/n1/PLATFORM", List.of(line("2026-08-10T09:00:00Z", "earlier")));

    List<Map<String, Object>> all = store.readAfter("logs/nodes/n1/PLATFORM", null);
    assertEquals(2, all.size());
    // Final read is oldest-first regardless of append order, thanks to the post-load sort.
    assertEquals("earlier", all.get(0).get("message"));
    assertEquals("first", all.get(1).get("message"));
  }

  @Test
  void a_malformed_line_rejects_the_whole_batch_and_writes_nothing() {
    Map<String, Object> missingTimestamp = new LinkedHashMap<>();
    missingTimestamp.put("message", "no timestamp here");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.appendLines(
                "logs/nodes/n1/PLATFORM",
                List.of(line("2026-08-10T10:00:00Z", "valid"), missingTimestamp)));

    assertFalse(Files.isDirectory(tempDir.resolve("logs/nodes/n1/PLATFORM")));
  }

  @Test
  void read_after_and_read_older_round_trip_through_a_fresh_store_instance() throws Exception {
    store.appendLines(
        "logs/nodes/n1/PLATFORM",
        List.of(
            line("2026-08-10T10:00:00Z", "one"),
            line("2026-08-10T10:01:00Z", "two"),
            line("2026-08-10T10:02:00Z", "three")));

    MuninnDayFileStore reopened = new MuninnDayFileStore(tempDir);

    List<Map<String, Object>> after = reopened.readAfter("logs/nodes/n1/PLATFORM", null);
    assertEquals(3, after.size());
    assertEquals("one", after.get(0).get("message"));
    assertEquals("three", after.get(2).get("message"));

    LogPage page = reopened.readOlder("logs/nodes/n1/PLATFORM", null, 2);
    assertEquals(2, page.lines().size());
    assertEquals("two", page.lines().get(0).get("message"));
    assertEquals("three", page.lines().get(1).get("message"));
  }

  @Test
  void reading_a_subtree_that_was_never_written_returns_empty_rather_than_erroring()
      throws Exception {
    assertTrue(store.readAfter("logs/nodes/never-seen/PLATFORM", null).isEmpty());
    assertTrue(store.readOlder("logs/nodes/never-seen/PLATFORM", null, 10).lines().isEmpty());
  }

  // A processId is a host:port string for every process kind except AGENT (design doc Part
  // B/O-9/O-11) -- e.g. "metrics/CONTROLPLANE/127.0.0.1:8080". java.nio.file.Path on Windows
  // reserves ':' for drive letters and throws InvalidPathException anywhere else in a path, which
  // previously made this a hard 400 on every real Windows-hosted control-plane/store/fafnir
  // replica's own metrics/traces (AGENT's plain nodeId never contains a colon, so it alone worked
  // by accident). Round-tripping here is the regression test for that fix -- on a
  // non-Windows CI runner this passed even before the fix, so this needs to keep passing
  // everywhere, not just prove "no crash on Windows."
  @Test
  void a_subtree_path_containing_a_colon_round_trips_without_an_invalid_path_error()
      throws Exception {
    store.appendLines(
        "metrics/CONTROLPLANE/127.0.0.1:8080", List.of(line("2026-08-10T10:00:00Z", "sample")));

    List<Map<String, Object>> lines = store.readAfter("metrics/CONTROLPLANE/127.0.0.1:8080", null);
    assertEquals(1, lines.size());
    assertEquals("sample", lines.get(0).get("message"));

    // The on-disk layout is an implementation detail (nothing reads a directory name back into a
    // processId), but asserting it directly here is what actually distinguishes "fixed" from
    // "happened not to throw" -- the sanitized directory must exist, the literal-colon one must
    // not.
    assertTrue(Files.isDirectory(tempDir.resolve("metrics/CONTROLPLANE/127.0.0.1_8080")));
  }
}
