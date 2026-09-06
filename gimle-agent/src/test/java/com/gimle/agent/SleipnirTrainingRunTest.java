package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SleipnirTrainer.RealTrainingRun} against a real child process -- {@link
 * StandInTrainingWorker} stands in for the worker JVM, so this covers the spawn/handshake/shutdown
 * mechanics themselves without needing an AOT-eligible worker classpath (that end-to-end case is
 * {@code SleipnirTrainerRealRunIT}'s). The case that matters: a cache is written only by a worker
 * that ends of its own accord, which a worker only does once its control channel closes.
 */
class SleipnirTrainingRunTest {

  private static final String JAVA_EXECUTABLE =
      Path.of(System.getProperty("java.home"), "bin", "java").toString();

  // Long enough for a JVM to start and hand back a Hello, short enough that the two cases where
  // nothing ever arrives are quick to fail.
  private static final Duration TIMEOUT = Duration.ofSeconds(15);

  @TempDir Path tempDir;

  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  void closing_the_control_channel_is_what_makes_a_training_worker_write_its_cache()
      throws Exception {
    Path cacheFile = tempDir.resolve("worker.aot");

    new SleipnirTrainer.RealTrainingRun().run(standInCommand(cacheFile), cacheFile, TIMEOUT);

    assertTrue(Files.isRegularFile(cacheFile), "no AOT cache was written to " + cacheFile);
    assertTrue(Files.size(cacheFile) > 0, "the AOT cache written to " + cacheFile + " is empty");
  }

  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  void a_worker_told_to_return_from_main_instead_never_ends_and_never_writes_a_cache()
      throws Exception {
    Path cacheFile = tempDir.resolve("worker.aot");
    List<String> command = new ArrayList<>(standInCommand(cacheFile));
    command.add(1, "-Dgimle.worker.aotTraining=true"); // a JVM flag, so before -cp

    IOException failure =
        assertThrows(
            IOException.class,
            () -> new SleipnirTrainer.RealTrainingRun().run(command, cacheFile, TIMEOUT));

    assertTrue(
        failure.getMessage().contains("did not exit within"),
        "unexpected failure message: " + failure.getMessage());
    assertTrue(Files.notExists(cacheFile));
  }

  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  void a_training_worker_that_writes_no_cache_is_reported_as_a_failure() throws Exception {
    Path cacheFile = tempDir.resolve("worker.aot");
    // A directory where the cache file is meant to land: the stand-in's own write fails, so
    // nothing ever appears there -- the same observable outcome as a JVM whose cache assembly
    // never ran.
    Files.createDirectories(cacheFile);

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                new SleipnirTrainer.RealTrainingRun()
                    .run(standInCommand(cacheFile), cacheFile, TIMEOUT));

    assertTrue(
        failure.getMessage().contains("without writing an AOT cache"),
        "unexpected failure message: " + failure.getMessage());
  }

  /**
   * The control-socket path is deliberately absent: {@link SleipnirTrainer.RealTrainingRun} appends
   * it itself, exactly as it does to a real worker's own command.
   */
  private List<String> standInCommand(Path cacheFile) {
    return List.of(
        JAVA_EXECUTABLE,
        "-cp",
        System.getProperty("java.class.path"),
        StandInTrainingWorker.class.getName(),
        cacheFile.toString());
  }
}
