package com.gimle.muninn;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.logging.LogFileReader.LogPage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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

    List<Map<String, Object>> all = store.readAfter("logs/nodes/n1/PLATFORM", null, 1000);
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

    List<Map<String, Object>> after = reopened.readAfter("logs/nodes/n1/PLATFORM", null, 1000);
    assertEquals(3, after.size());
    assertEquals("one", after.get(0).get("message"));
    assertEquals("three", after.get(2).get("message"));

    LogPage page = reopened.readOlder("logs/nodes/n1/PLATFORM", null, 2);
    assertEquals(2, page.lines().size());
    assertEquals("two", page.lines().get(0).get("message"));
    assertEquals("three", page.lines().get(1).get("message"));
  }

  /**
   * Reproduces the real race between a read (list day files, then read each one) and {@code
   * RetentionSweeper}'s own {@code deleteIfExists} on the same directory: a second day file is
   * repeatedly recreated and deleted from another thread while the main thread keeps reading, so
   * across enough iterations the reader is guaranteed to land squarely between "file was in the
   * listing" and "file is now gone" -- the exact interleaving a single deterministic delete can't
   * express. Before the fix this surfaced as an {@code UncheckedIOException} escaping the read; the
   * fix's contract is that a vanished day file is silently skipped, same as a malformed line.
   */
  @Test
  void a_day_file_removed_by_a_concurrent_retention_sweep_is_skipped_not_thrown() throws Exception {
    String subtree = "logs/nodes/n1/PLATFORM";
    store.appendLines(subtree, List.of(line("2026-08-10T10:00:00Z", "survivor")));
    Path racyFile = tempDir.resolve(subtree).resolve("2026-08-11.log");

    AtomicBoolean stop = new AtomicBoolean(false);
    Thread racer =
        Thread.ofVirtual()
            .start(
                () -> {
                  while (!stop.get()) {
                    try {
                      store.appendLines(subtree, List.of(line("2026-08-11T10:00:00Z", "racy")));
                      Files.deleteIfExists(racyFile);
                    } catch (IOException ignored) {
                      // Benign: this thread's own write can itself lose a race against its own
                      // delete on the very next loop iteration -- not the condition under test.
                    }
                  }
                });
    try {
      for (int i = 0; i < 300; i++) {
        assertDoesNotThrow(() -> store.readAfter(subtree, null, 1000));
        assertDoesNotThrow(() -> store.readOlder(subtree, null, 1000));
      }
    } finally {
      stop.set(true);
      racer.join();
    }
  }

  @Test
  void reading_a_subtree_that_was_never_written_returns_empty_rather_than_erroring()
      throws Exception {
    assertTrue(store.readAfter("logs/nodes/never-seen/PLATFORM", null, 1000).isEmpty());
    assertTrue(store.readOlder("logs/nodes/never-seen/PLATFORM", null, 10).lines().isEmpty());
  }

  // A processId is a host:port string for every process kind except AGENT --
  // e.g. "metrics/CONTROLPLANE/127.0.0.1:8080". java.nio.file.Path on Windows
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

    List<Map<String, Object>> lines =
        store.readAfter("metrics/CONTROLPLANE/127.0.0.1:8080", null, 1000);
    assertEquals(1, lines.size());
    assertEquals("sample", lines.get(0).get("message"));

    // The on-disk layout is an implementation detail (nothing reads a directory name back into a
    // processId), but asserting it directly here is what actually distinguishes "fixed" from
    // "happened not to throw" -- the sanitized directory must exist, the literal-colon one must
    // not.
    assertTrue(Files.isDirectory(tempDir.resolve("metrics/CONTROLPLANE/127.0.0.1_8080")));
  }
}
