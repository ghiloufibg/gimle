package com.gimle.muninn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link RetentionSweeper#sweep()} directly rather than waiting on its own scheduler --
 * the scheduler is just a {@code scheduleAtFixedRate} wrapper around this same method, already
 * proven correct by every other ticker in this codebase.
 */
class RetentionSweeperTest {

  @TempDir Path tempDir;

  @Test
  void a_day_file_older_than_the_retention_window_is_deleted() throws Exception {
    Path dir = tempDir.resolve("logs/nodes/n1/PLATFORM");
    Files.createDirectories(dir);
    String oldDay = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(40).toString();
    Files.writeString(dir.resolve(oldDay + ".log"), "{}\n");

    try (RetentionSweeper sweeper = new RetentionSweeper(tempDir, 30, Duration.ofDays(1))) {
      sweeper.sweep();
    }

    assertFalse(Files.exists(dir.resolve(oldDay + ".log")));
  }

  @Test
  void a_day_file_within_the_retention_window_survives() throws Exception {
    Path dir = tempDir.resolve("logs/nodes/n1/PLATFORM");
    Files.createDirectories(dir);
    String recentDay = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(2).toString();
    Files.writeString(dir.resolve(recentDay + ".log"), "{}\n");

    try (RetentionSweeper sweeper = new RetentionSweeper(tempDir, 30, Duration.ofDays(1))) {
      sweeper.sweep();
    }

    assertTrue(Files.exists(dir.resolve(recentDay + ".log")));
  }

  @Test
  void sweeping_twice_is_idempotent_and_does_not_error_on_an_already_deleted_file()
      throws Exception {
    Path dir = tempDir.resolve("logs/nodes/n1/PLATFORM");
    Files.createDirectories(dir);
    String oldDay = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(40).toString();
    Files.writeString(dir.resolve(oldDay + ".log"), "{}\n");

    try (RetentionSweeper sweeper = new RetentionSweeper(tempDir, 30, Duration.ofDays(1))) {
      sweeper.sweep();
      sweeper.sweep();
    }

    assertFalse(Files.exists(dir.resolve(oldDay + ".log")));
  }

  @Test
  void sweeping_a_data_root_that_does_not_exist_yet_is_a_no_op() throws Exception {
    Path missing = tempDir.resolve("never-created");
    try (RetentionSweeper sweeper = new RetentionSweeper(missing, 30, Duration.ofDays(1))) {
      sweeper.sweep();
    }
  }
}
