package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.core.restart.RestartTracker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test: {@code WorkerProcessSupervisor.closeSystemLog()} used to close the SYSTEM
 * capture stream without nulling the field, so every respawn after the first silently lost SYSTEM
 * capture (a swallowed {@code ClosedChannelException}) -- proven live with a scratch test during
 * the 2026-07-27 re-audit and fixed by nulling {@code systemLogStream} inside {@code
 * closeSystemLog()}.
 */
class SystemLogCaptureTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void system_log_capture_survives_a_respawn() throws Exception {
    Path counterFile = tempDir.resolve("counter-syslog");
    Path socketPath = tempDir.resolve("socket-syslog");
    Path systemLogFile = tempDir.resolve("system.log");

    // Crashes instantly on every invocation, printing one plain stdout banner line first each
    // time -- forces at least two spawns of the same worker id, exercising the respawn path.
    List<String> command = crashingCommand(counterFile, Integer.MAX_VALUE, 0, 1);

    RestartTracker tracker =
        new RestartTracker(
            Duration.ofMillis(100), 2.0, Duration.ofSeconds(5), 5, Duration.ofMinutes(10));

    try (WorkerProcessSupervisor supervisor =
        new WorkerProcessSupervisor(
            "syslog-probe",
            () -> command,
            socketPath,
            tracker,
            id -> {},
            Optional.of(systemLogFile),
            Duration.ofMillis(200))) {
      supervisor.start();

      // Wait for two *banner* lines specifically, not just two lines of any kind: some sandboxes'
      // java launcher itself emits an incidental line to stdout before any Java main() runs (e.g.
      // "Picked up JAVA_TOOL_OPTIONS: ..." when that env var is set), which the same
      // SYSTEM-capture mechanism correctly captures too. Counting that toward a generic
      // two-lines-total threshold would let the try-block exit -- closing the supervisor -- after
      // only the first spawn's own banner ever appeared, never actually exercising the respawn
      // path this test means to cover.
      waitForBannerLineCount(systemLogFile, 2, Duration.ofSeconds(20));
    }

    List<String> lines = Files.readAllLines(systemLogFile);
    long bannerLines = lines.stream().filter(line -> line.contains("plain text banner")).count();
    assertTrue(
        bannerLines >= 2, "expected SYSTEM capture to survive a respawn; captured lines=" + lines);
  }

  private void waitForBannerLineCount(Path file, int count, Duration timeout)
      throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      if (Files.exists(file)) {
        try {
          long bannerLines =
              Files.readAllLines(file).stream()
                  .filter(line -> line.contains("plain text banner"))
                  .count();
          if (bannerLines >= count) {
            return;
          }
        } catch (Exception e) {
          // file may be mid-write; retry
        }
      }
      Thread.sleep(50);
    }
    fail("timed out waiting for " + count + " banner lines in " + file);
  }

  private List<String> crashingCommand(
      Path counterFile, int immediateCrashCount, long stableSleepMillis, int exitCode) {
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");
    return List.of(
        javaExecutable,
        "-cp",
        classpath,
        PlainStdoutCrashDriver.class.getName(),
        counterFile.toAbsolutePath().toString(),
        Integer.toString(immediateCrashCount),
        Long.toString(stableSleepMillis),
        Integer.toString(exitCode));
  }

  private static String javaExecutable() {
    Optional<String> command = ProcessHandle.current().info().command();
    if (command.isPresent()) {
      return command.get();
    }
    Path javaBin = Path.of(System.getProperty("java.home"), "bin");
    for (String candidate : List.of("java", "java.exe")) {
      Path path = javaBin.resolve(candidate);
      if (Files.isRegularFile(path)) {
        return path.toString();
      }
    }
    throw new IllegalStateException("could not locate the java launcher under " + javaBin);
  }
}
