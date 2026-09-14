package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.core.logging.LogFileReader;
import com.gimle.core.logging.LogFilter;
import com.gimle.core.restart.RestartTracker;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the fix: the raw stdout/stderr SYSTEM-capture file used to be opened {@code CREATE,
 * APPEND} and never rotated, growing unbounded for the lifetime of a chatty or long-lived worker. A
 * real {@link WorkerProcessSupervisor} supervising a real subprocess that floods stdout with
 * plain-text lines, against a deliberately tiny {@code gimle.log.maxFileSizeBytes}, exercises the
 * actual rotation path rather than asserting against the private rotation method directly.
 */
class WorkerProcessSupervisorSystemLogRotationTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static final long ORIGINAL_MAX_FILE_SIZE = Long.getLong("gimle.log.maxFileSizeBytes", -1);
  private static final String ORIGINAL_MAX_FILES = System.getProperty("gimle.log.maxFiles");

  @AfterEach
  void restoreSystemProperties() {
    if (ORIGINAL_MAX_FILE_SIZE < 0) {
      System.clearProperty("gimle.log.maxFileSizeBytes");
    } else {
      System.setProperty("gimle.log.maxFileSizeBytes", Long.toString(ORIGINAL_MAX_FILE_SIZE));
    }
    if (ORIGINAL_MAX_FILES == null) {
      System.clearProperty("gimle.log.maxFiles");
    } else {
      System.setProperty("gimle.log.maxFiles", ORIGINAL_MAX_FILES);
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void a_chatty_worker_rotates_its_system_capture_instead_of_growing_it_unbounded()
      throws Exception {
    // Small enough that a few hundred short lines guarantee at least one rollover, large enough
    // that a single line can never itself exceed it.
    System.setProperty("gimle.log.maxFileSizeBytes", "2000");
    System.setProperty("gimle.log.maxFiles", "3");

    Path logFile = tempDir.resolve("worker-system.log");
    Path socketPath = tempDir.resolve("socket-rotation");
    List<String> command = chattyCommand(2000, "a moderately long filler line of raw text");

    RestartTracker tracker =
        new RestartTracker(
            Duration.ofSeconds(30), 2.0, Duration.ofMinutes(5), 100, Duration.ofMinutes(10));

    try (WorkerProcessSupervisor supervisor =
        new WorkerProcessSupervisor(
            "rotation", () -> command, socketPath, tracker, id -> {}, Optional.of(logFile))) {
      supervisor.start();

      Path firstRotatedFile = logFile.resolveSibling(logFile.getFileName() + ".1");
      awaitFileExists(firstRotatedFile, Duration.ofSeconds(20));

      // ChattyWorkerDriver blocks forever right after its last line, so once the active file's
      // size stops changing across several samples the writer -- and with it, rotation -- has
      // genuinely gone quiet. Sampling on a fixed sleep instead raced LogFileReader.readOlder
      // below against a still-rotating writer: it can enumerate a ".2"/".3" file that the writer
      // then deletes out from under it moments before the open, an intermittent
      // UncheckedIOException under any delay that stretches the writer's own 2000-line burst out
      // that far. The confirmed-stable size is reused for the assertion below rather than
      // re-sampled, so there is no second read racing a rotation this same wait already ruled out.
      long activeSize = awaitFileSizeStable(logFile, Duration.ofSeconds(10));

      // The active file must never be allowed to grow past roughly one line beyond the cap --
      // the whole point of rotating at all, not just that a ".1" file eventually appears.
      assertTrue(
          activeSize < 2000 + 500,
          "active SYSTEM capture file grew past its rotation cap: " + activeSize + " bytes");

      // Content survives the rotation, readable through the ordinary rotated-file-aware reader --
      // not silently discarded by the rename.
      List<?> rotatedLines =
          LogFileReader.readOlder(
                  logFile, LogFileReader.configuredMaxFiles(), null, 10_000, LogFilter.NONE)
              .lines();
      assertTrue(!rotatedLines.isEmpty(), "expected captured lines to survive rotation");
    }
  }

  private void awaitFileExists(Path file, Duration timeout) throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    while (!Files.exists(file) && Instant.now().isBefore(deadline)) {
      Thread.sleep(20);
    }
    assertTrue(Files.exists(file), "expected " + file + " to exist after rotation");
  }

  /**
   * Polls {@code file}'s size until it reads the same value on {@link #STABLE_STREAK_TARGET}
   * consecutive samples 100ms apart, then returns that size; times out otherwise. A streak of just
   * two matching samples once let a coincidence through: {@code ChattyWorkerDriver}'s lines are all
   * nearly the same length, so two samples 100ms apart can land on the same byte count purely by
   * chance while rotation is still genuinely in progress. Rotation itself also briefly renames the
   * active file away and reopens a fresh one in its place ({@code
   * WorkerProcessSupervisor#rotateSystemLogLocked}) -- an ordinary, expected part of an in-progress
   * rotation from this poller's outside-the-lock vantage point, not a reason to fail, so a
   * transient {@code NoSuchFileException} just resets the streak rather than propagating.
   */
  private static final int STABLE_STREAK_TARGET = 3;

  private long awaitFileSizeStable(Path file, Duration timeout) throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    long previousSize = -1;
    int streak = 0;
    while (Instant.now().isBefore(deadline)) {
      long currentSize;
      try {
        currentSize = Files.size(file);
      } catch (NoSuchFileException e) {
        previousSize = -1;
        streak = 0;
        Thread.sleep(100);
        continue;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      streak = currentSize == previousSize ? streak + 1 : 1;
      if (streak >= STABLE_STREAK_TARGET) {
        return currentSize;
      }
      previousSize = currentSize;
      Thread.sleep(100);
    }
    return fail("expected " + file + "'s size to stop changing within " + timeout);
  }

  private List<String> chattyCommand(int lineCount, String lineText) {
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");
    return List.of(
        javaExecutable,
        "-cp",
        classpath,
        ChattyWorkerDriver.class.getName(),
        Integer.toString(lineCount),
        lineText);
  }

  private static String javaExecutable() {
    Optional<String> command = ProcessHandle.current().info().command();
    if (command.isPresent()) {
      return command.get();
    }
    Path javaBin = Path.of(System.getProperty("java.home"), "bin");
    Path candidate = javaBin.resolve("java");
    if (!Files.isExecutable(candidate)) {
      throw new UncheckedIOException(new IOException("no usable java executable found"));
    }
    return candidate.toString();
  }
}
