package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.core.protocol.Json;
import com.gimle.core.restart.RestartTracker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real-subprocess acceptance test for {@link VesselProcessSupervisor}: a genuine spawned process,
 * not a mock, proving stdout capture into a vessel instance's own APPLICATION log and the same
 * crash-then-respawn behavior {@link WorkerProcessSupervisorTest} already proves for a worker JVM
 * (reusing that exact fixture, {@link CrashingWorkerDriver}, since it takes no control-socket
 * argument {@link VesselProcessSupervisor} would need to append -- there's nothing to append here).
 */
class VesselProcessSupervisorTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  @Test
  @Timeout(value = 20, unit = TimeUnit.SECONDS)
  void captures_stdout_lines_as_the_instance_application_log() throws Exception {
    Path applicationLogFile = tempDir.resolve("instances").resolve("greeter-0.log");
    List<String> command =
        List.of(
            javaExecutable(),
            "-cp",
            System.getProperty("java.class.path"),
            VesselOutputDriver.class.getName(),
            "hello from the vessel",
            "5000");
    RestartTracker tracker =
        new RestartTracker(
            Duration.ofMillis(200), 2.0, Duration.ofSeconds(5), 10, Duration.ofMinutes(10));

    try (VesselProcessSupervisor supervisor =
        new VesselProcessSupervisor(
            "greeter#0",
            command,
            Map.of(),
            Optional.empty(),
            tracker,
            id -> fail("should not exhaust its budget"),
            applicationLogFile,
            id -> {})) {
      supervisor.start();

      // Matched by content, not position: a JVM launched under this environment's own
      // JAVA_TOOL_OPTIONS can legitimately emit its own diagnostic line to stderr (merged into
      // this same captured stream) before the driver's own line ever runs.
      Map<String, Object> line =
          awaitLineWithMessage(applicationLogFile, "hello from the vessel", Duration.ofSeconds(10));
      assertEquals("APPLICATION", line.get("category"));
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void a_crashed_vessel_process_is_respawned() throws Exception {
    Path counterFile = tempDir.resolve("counter");
    Path applicationLogFile = tempDir.resolve("instances").resolve("worker-0.log");
    List<String> command =
        List.of(
            javaExecutable(),
            "-cp",
            System.getProperty("java.class.path"),
            CrashingWorkerDriver.class.getName(),
            counterFile.toAbsolutePath().toString(),
            Integer.toString(Integer.MAX_VALUE),
            "0",
            "1");
    RestartTracker tracker =
        new RestartTracker(
            Duration.ofMillis(200), 2.0, Duration.ofSeconds(5), 10, Duration.ofMinutes(10));
    AtomicBoolean respawned = new AtomicBoolean();
    CountDownLatch respawnedLatch = new CountDownLatch(1);

    try (VesselProcessSupervisor supervisor =
        new VesselProcessSupervisor(
            "worker#0",
            command,
            Map.of(),
            Optional.empty(),
            tracker,
            id -> fail("should not exhaust its budget for this small a crash count"),
            applicationLogFile,
            id -> {
              respawned.set(true);
              respawnedLatch.countDown();
            })) {
      supervisor.start();

      assertTrue(respawnedLatch.await(20, TimeUnit.SECONDS), "expected the vessel to be respawned");
      assertTrue(respawned.get());
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void exhausting_the_restart_budget_reports_it_and_stops_respawning() throws Exception {
    Path counterFile = tempDir.resolve("counter-exhaust");
    Path applicationLogFile = tempDir.resolve("instances").resolve("worker-0.log");
    List<String> command =
        List.of(
            javaExecutable(),
            "-cp",
            System.getProperty("java.class.path"),
            CrashingWorkerDriver.class.getName(),
            counterFile.toAbsolutePath().toString(),
            Integer.toString(Integer.MAX_VALUE),
            "0",
            "1");
    RestartTracker tracker =
        new RestartTracker(
            Duration.ofMillis(50), 1.0, Duration.ofMillis(50), 2, Duration.ofMinutes(10));
    CountDownLatch exhaustedLatch = new CountDownLatch(1);

    try (VesselProcessSupervisor supervisor =
        new VesselProcessSupervisor(
            "exhaust#0",
            command,
            Map.of(),
            Optional.empty(),
            tracker,
            id -> exhaustedLatch.countDown(),
            applicationLogFile,
            id -> {})) {
      supervisor.start();

      assertTrue(
          exhaustedLatch.await(20, TimeUnit.SECONDS),
          "expected the restart budget to be reported exhausted");
    }
  }

  /**
   * Mirrors {@code WorkerProcessSupervisorTest}'s own {@code
   * a_respawn_that_stays_up_past_the_stability_threshold_resets_the_backoff}: crashes instantly
   * twice, then stays up 1500ms (past the 1000ms stability threshold below) before crashing again.
   * Without {@code recordSuccess()} being called for a vessel, that third respawn would never reset
   * the tracker, and the gap after it would carry the still-escalated (not reset) delay.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void a_respawn_that_stays_up_past_the_stability_threshold_resets_the_backoff() throws Exception {
    Path counterFile = tempDir.resolve("counter-vessel-reset");
    Path applicationLogFile = tempDir.resolve("instances").resolve("reset-0.log");
    List<String> command = crashingCommand(counterFile, 2, 1500, 1);
    RestartTracker tracker =
        new RestartTracker(
            Duration.ofMillis(500), 3.0, Duration.ofSeconds(5), 5, Duration.ofMinutes(10));

    try (VesselProcessSupervisor supervisor =
        new VesselProcessSupervisor(
            "reset#0",
            command,
            Map.of(),
            Optional.empty(),
            tracker,
            id -> fail("should not exhaust its budget"),
            applicationLogFile,
            id -> {},
            Duration.ofMillis(1000))) {
      supervisor.start();

      List<Instant> spawnTimes =
          observeDistinctPidTimestamps(supervisor, 4, Duration.ofSeconds(20));
      List<Duration> gaps = new ArrayList<>();
      for (int i = 1; i < spawnTimes.size(); i++) {
        gaps.add(Duration.between(spawnTimes.get(i - 1), spawnTimes.get(i)));
      }

      // Same reasoning as the worker-level test this mirrors: gap 2 (spawn3 -> spawn4) includes
      // spawn3's own 1500ms stable lifetime plus whatever delay follows -- ~500ms (attempt 1
      // again) if the stability confirmation correctly reset the tracker while spawn3 was still
      // sleeping, or ~4500ms (500ms * 3^2) if it didn't. A 4s cutoff cleanly distinguishes them.
      assertTrue(
          gaps.get(2).toMillis() < 4000,
          "expected the backoff to have reset to roughly the initial delay after a stable"
              + " respawn, but gap after the stable period was "
              + gaps.get(2)
              + " (all gaps="
              + gaps
              + ")");
      assertTrue(
          gaps.get(1).toMillis() > 1000,
          "expected the second gap to reflect the escalated (not yet reset) delay; gaps=" + gaps);
    }
  }

  private List<String> crashingCommand(
      Path counterFile, int immediateCrashCount, long stableSleepMillis, int exitCode) {
    return List.of(
        javaExecutable(),
        "-cp",
        System.getProperty("java.class.path"),
        CrashingWorkerDriver.class.getName(),
        counterFile.toAbsolutePath().toString(),
        Integer.toString(immediateCrashCount),
        Long.toString(stableSleepMillis),
        Integer.toString(exitCode));
  }

  /**
   * Polls {@link VesselProcessSupervisor#process()} (package-private, test/inspection only) until
   * {@code count} distinct pids have been observed, timestamping the moment each new pid first
   * appears -- {@code Process#pid()} stays queryable after the process has already exited, so this
   * never races the crash itself, only the much-coarser respawn timing.
   */
  private List<Instant> observeDistinctPidTimestamps(
      VesselProcessSupervisor supervisor, int count, Duration timeout) throws InterruptedException {
    List<Instant> timestamps = new ArrayList<>();
    long lastPid = -1;
    Instant deadline = Instant.now().plus(timeout);
    while (timestamps.size() < count && Instant.now().isBefore(deadline)) {
      Process current = supervisor.process();
      if (current != null && current.pid() != lastPid) {
        lastPid = current.pid();
        timestamps.add(Instant.now());
      }
      Thread.sleep(20);
    }
    if (timestamps.size() < count) {
      fail("expected " + count + " distinct spawns, only observed " + timestamps.size());
    }
    return timestamps;
  }

  private static Map<String, Object> awaitLineWithMessage(
      Path file, String message, Duration timeout) throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      if (Files.isRegularFile(file)) {
        Optional<Map<String, Object>> found =
            readJsonLines(file).stream().filter(l -> message.equals(l.get("message"))).findFirst();
        if (found.isPresent()) {
          return found.get();
        }
      }
      Thread.sleep(50);
    }
    fail("timed out waiting for an application log line with message '" + message + "' in " + file);
    throw new AssertionError("unreachable");
  }

  private static List<Map<String, Object>> readJsonLines(Path file) {
    try {
      return Files.readAllLines(file).stream()
          .filter(line -> !line.isBlank())
          .map(line -> Json.asObject(Json.parse(line)))
          .toList();
    } catch (java.io.IOException e) {
      return List.of();
    }
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
