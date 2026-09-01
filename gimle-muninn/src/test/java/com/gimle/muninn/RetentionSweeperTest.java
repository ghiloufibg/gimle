package com.gimle.muninn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
    String oldDay = LocalDate.now(ZoneOffset.UTC).minusDays(40).toString();
    Files.writeString(dir.resolve(oldDay + ".log"), "{}\n");

    try (RetentionSweeper sweeper =
        new RetentionSweeper(tempDir, RetentionPolicy.uniform(30), Duration.ofDays(1))) {
      sweeper.sweep();
    }

    assertFalse(Files.exists(dir.resolve(oldDay + ".log")));
  }

  @Test
  void a_day_file_within_the_retention_window_survives() throws Exception {
    Path dir = tempDir.resolve("logs/nodes/n1/PLATFORM");
    Files.createDirectories(dir);
    String recentDay = LocalDate.now(ZoneOffset.UTC).minusDays(2).toString();
    Files.writeString(dir.resolve(recentDay + ".log"), "{}\n");

    try (RetentionSweeper sweeper =
        new RetentionSweeper(tempDir, RetentionPolicy.uniform(30), Duration.ofDays(1))) {
      sweeper.sweep();
    }

    assertTrue(Files.exists(dir.resolve(recentDay + ".log")));
  }

  @Test
  void sweeping_twice_is_idempotent_and_does_not_error_on_an_already_deleted_file()
      throws Exception {
    Path dir = tempDir.resolve("logs/nodes/n1/PLATFORM");
    Files.createDirectories(dir);
    String oldDay = LocalDate.now(ZoneOffset.UTC).minusDays(40).toString();
    Files.writeString(dir.resolve(oldDay + ".log"), "{}\n");

    try (RetentionSweeper sweeper =
        new RetentionSweeper(tempDir, RetentionPolicy.uniform(30), Duration.ofDays(1))) {
      sweeper.sweep();
      sweeper.sweep();
    }

    assertFalse(Files.exists(dir.resolve(oldDay + ".log")));
  }

  @Test
  void sweeping_a_data_root_that_does_not_exist_yet_is_a_no_op() throws Exception {
    Path missing = tempDir.resolve("never-created");
    try (RetentionSweeper sweeper =
        new RetentionSweeper(missing, RetentionPolicy.uniform(30), Duration.ofDays(1))) {
      sweeper.sweep();
    }
  }

  @Test
  void each_signal_subtree_is_swept_on_its_own_cutoff() throws Exception {
    String day = LocalDate.now(ZoneOffset.UTC).minusDays(10).toString();
    Path logs = tempDir.resolve("logs/nodes/n1/PLATFORM");
    Path metrics = tempDir.resolve("metrics/WORKER/n1_worker-1");
    Path traces = tempDir.resolve("traces/WORKER/n1_worker-1");
    for (Path dir : java.util.List.of(logs, metrics, traces)) {
      Files.createDirectories(dir);
      Files.writeString(dir.resolve(day + ".log"), "{}\n");
    }

    // Logs outlive the ten-day-old files; metrics and traces are both already past their own,
    // shorter windows.
    RetentionPolicy policy = new RetentionPolicy(30, 30, 7, 3);
    try (RetentionSweeper sweeper = new RetentionSweeper(tempDir, policy, Duration.ofDays(1))) {
      sweeper.sweep();
    }

    assertTrue(Files.exists(logs.resolve(day + ".log")));
    assertFalse(Files.exists(metrics.resolve(day + ".log")));
    assertFalse(Files.exists(traces.resolve(day + ".log")));
  }

  @Test
  void a_signal_with_no_override_of_its_own_follows_the_global_window() throws Exception {
    System.clearProperty("gimle.muninn.retentionDays");
    System.clearProperty("gimle.muninn.logs.retentionDays");
    System.clearProperty("gimle.muninn.metrics.retentionDays");
    System.clearProperty("gimle.muninn.traces.retentionDays");
    System.setProperty("gimle.muninn.retentionDays", "5");
    System.setProperty("gimle.muninn.traces.retentionDays", "2");
    try {
      RetentionPolicy policy = RetentionPolicy.fromConfig();
      assertEquals(5, policy.logsDays());
      assertEquals(5, policy.metricsDays());
      assertEquals(2, policy.tracesDays());

      String day = LocalDate.now(ZoneOffset.UTC).minusDays(3).toString();
      Path logs = tempDir.resolve("logs/nodes/n1/PLATFORM");
      Path traces = tempDir.resolve("traces/WORKER/n1_worker-1");
      for (Path dir : java.util.List.of(logs, traces)) {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(day + ".log"), "{}\n");
      }

      try (RetentionSweeper sweeper = new RetentionSweeper(tempDir, policy, Duration.ofDays(1))) {
        sweeper.sweep();
      }

      assertTrue(Files.exists(logs.resolve(day + ".log")));
      assertFalse(Files.exists(traces.resolve(day + ".log")));
    } finally {
      System.clearProperty("gimle.muninn.retentionDays");
      System.clearProperty("gimle.muninn.traces.retentionDays");
    }
  }

  @Test
  void a_day_file_under_no_known_signal_subtree_still_ages_out_on_the_global_window()
      throws Exception {
    Path stray = tempDir.resolve("something-else/n1");
    Files.createDirectories(stray);
    String oldDay = LocalDate.now(ZoneOffset.UTC).minusDays(40).toString();
    Files.writeString(stray.resolve(oldDay + ".log"), "{}\n");

    RetentionPolicy policy = new RetentionPolicy(30, 365, 365, 365);
    try (RetentionSweeper sweeper = new RetentionSweeper(tempDir, policy, Duration.ofDays(1))) {
      sweeper.sweep();
    }

    assertFalse(Files.exists(stray.resolve(oldDay + ".log")));
  }

  @Test
  void a_negative_retention_window_is_rejected_rather_than_silently_deleting_everything() {
    assertThrows(IllegalArgumentException.class, () -> new RetentionPolicy(30, -1, 30, 30));
  }
}
