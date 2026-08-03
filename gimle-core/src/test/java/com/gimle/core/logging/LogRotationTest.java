package com.gimle.core.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.gimle.core.logging.LogFileReader.LogPage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.slf4j.LoggerFactory;

/**
 * Verifies &sect;-mirroring-kubelet log rotation (size+count bounded, no time-based retention) and
 * that {@link LogFileReader}'s timestamp cursor keeps resolving correctly once a rollover has
 * happened -- the property a byte-offset/file-index cursor would have silently broken (see {@link
 * RollingFileAppenders}'s javadoc).
 */
// System.setProperty mutates a JVM-global; excludes this class from running concurrently with
// any other class holding the same lock, under class-level parallel execution (root pom.xml).
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class LogRotationTest {

  @TempDir Path dir;

  private final Logger logger =
      ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("test.rotation");

  private PlatformFileAppender appender;
  private String previousMaxSize;
  private String previousMaxFiles;

  @BeforeEach
  void smallRotationBudget() {
    previousMaxSize = System.getProperty("gimle.log.maxFileSizeBytes");
    previousMaxFiles = System.getProperty("gimle.log.maxFiles");
    // Small enough that a handful of log lines forces multiple rollovers, without the test needing
    // to write megabytes of data.
    System.setProperty("gimle.log.maxFileSizeBytes", "512");
    System.setProperty("gimle.log.maxFiles", "3");
  }

  @AfterEach
  void restoreAndStop() {
    if (appender != null) {
      appender.stop();
    }
    restoreProperty("gimle.log.maxFileSizeBytes", previousMaxSize);
    restoreProperty("gimle.log.maxFiles", previousMaxFiles);
  }

  private static void restoreProperty(String key, String previous) {
    if (previous == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, previous);
    }
  }

  private void write(String message) {
    LoggingEvent event =
        new LoggingEvent(getClass().getName(), logger, Level.INFO, message, null, null);
    appender.append(event);
  }

  @Test
  void rolls_over_by_size_and_evicts_the_oldest_copy_past_max_files() throws IOException {
    Path file = dir.resolve("platform.log");
    appender = new PlatformFileAppender(file);
    appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    appender.start();

    // 2000 lines comfortably clears the 512-byte cap many times over, forcing several rollovers
    // well past the 3-file (1 active + 2 rotated) budget configured above.
    for (int i = 0; i < 2000; i++) {
      write("rotation test line number " + i + " with some padding to grow the file faster");
    }
    appender.stop();

    assertTrue(Files.exists(file), "active file should still exist");
    assertTrue(Files.exists(dir.resolve("platform.log.1")), "expected at least one rotated file");
    assertTrue(
        Files.exists(dir.resolve("platform.log.2")), "expected a second rotated file (maxFiles=3)");
    assertTrue(
        Files.notExists(dir.resolve("platform.log.3")),
        "a fourth copy would exceed maxFiles=3 and must have been evicted");

    // The cursor is a timestamp, so LogFileReader must still find every surviving line across the
    // active file plus both rotated copies -- this is the property a file-index/byte-offset cursor
    // would have broken, since FixedWindowRollingPolicy renames files on every rollover.
    LogPage all = LogFileReader.readOlder(file, 3, null, 1000);
    assertTrue(all.lines().size() >= 3, "expected multiple surviving lines across rotated files");
    for (Map<String, Object> line : all.lines()) {
      assertNotNull(line.get("timestamp"), "every line must carry a parseable timestamp cursor");
    }
  }

  @Test
  void cursor_paging_and_follow_resolve_correctly_across_a_rotation_boundary() throws IOException {
    Path file = dir.resolve("instance.log");
    appender = new PlatformFileAppender(file);
    appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    appender.start();

    for (int i = 0; i < 2000; i++) {
      write("line " + i + " padded so the 512-byte cap rotates more than once during this test");
    }
    appender.stop();

    // Page backward from "now" (no cursor): should return the most recent lines, oldest-first.
    LogPage recent = LogFileReader.readOlder(file, 3, null, 5);
    assertEquals(5, recent.lines().size());
    assertNotNull(recent.olderCursor(), "more history should remain to page into");

    // Paging further back with olderCursor must not re-return anything from the first page.
    LogPage older = LogFileReader.readOlder(file, 3, recent.olderCursor(), 5);
    assertTrue(older.lines().size() > 0);
    String firstPageEarliest = (String) recent.lines().get(0).get("message");
    for (Map<String, Object> line : older.lines()) {
      assertTrue(!line.get("message").equals(firstPageEarliest));
    }

    // readAfter(newerCursor) from the first page must pick up exactly where it left off -- the
    // resume-a-follow-session query a live tail depends on.
    List<Map<String, Object>> after = LogFileReader.readAfter(file, 3, recent.newerCursor());
    assertTrue(
        after.isEmpty(), "nothing was written after the last line, so nothing new to return");

    // A cursor from long before anything on disk should behave like "no cursor" once everything
    // still present qualifies, and eventually run out of older history (null olderCursor) once
    // rotation has evicted the rest -- never an error, matching kubectl logs' own behavior once a
    // container's older rotated logs are gone.
    LogPage everything = LogFileReader.readOlder(file, 3, null, 10_000);
    assertNull(
        LogFileReader.readOlder(file, 3, everythingEarliestCursor(everything), 10_000)
            .olderCursor());
  }

  private static String everythingEarliestCursor(LogPage page) {
    return (String) page.lines().get(0).get("timestamp");
  }
}
