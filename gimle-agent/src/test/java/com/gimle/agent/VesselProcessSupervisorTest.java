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
