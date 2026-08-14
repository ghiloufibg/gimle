package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.core.restart.RestartTracker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Acceptance test: a real {@link WorkerProcessSupervisor} supervising a real crashing subprocess
 * (not a mock), proving the worker-level {@code CrashLoopBackOff}-equivalent tier end-to-end --
 * kill detection, respawn, escalating backoff, and eventual give-up, none of which {@code
 * RestartTrackerTest}'s own unit tests (which drive the tracker directly) can prove by themselves.
 */
class WorkerProcessSupervisorTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void backoff_delay_escalates_across_repeated_crashes_then_gives_up() throws Exception {
    Path counterFile = tempDir.resolve("counter-escalate");
    Path socketPath = tempDir.resolve("socket-escalate");
    // Crashes on every single invocation (immediateCrashCount is unreachably high) -- a genuine
    // fast crash loop.
    List<String> command = crashingCommand(counterFile, Integer.MAX_VALUE, 0, 1);

    RestartTracker tracker =
        new RestartTracker(
            Duration.ofMillis(1000), 2.0, Duration.ofSeconds(10), 4, Duration.ofMinutes(10));
    AtomicBoolean exhausted = new AtomicBoolean();
    CountDownLatch exhaustedLatch = new CountDownLatch(1);

    try (WorkerProcessSupervisor supervisor =
        new WorkerProcessSupervisor(
            "escalate",
            command,
            socketPath,
            tracker,
            id -> {
              exhausted.set(true);
              exhaustedLatch.countDown();
            },
            Optional.empty(),
            Duration.ofMillis(2000))) {
      supervisor.start();

      List<Instant> spawnTimes =
          observeDistinctPidTimestamps(supervisor, 5, Duration.ofSeconds(45));
      assertTrue(
          exhaustedLatch.await(10, TimeUnit.SECONDS),
          "expected the restart budget to be exhausted after 5 fast crashes");
      assertTrue(exhausted.get());

      List<Duration> gaps = new ArrayList<>();
      for (int i = 1; i < spawnTimes.size(); i++) {
        gaps.add(Duration.between(spawnTimes.get(i - 1), spawnTimes.get(i)));
      }
      // Expected ~1s, 2s, 4s, 8s (capped at 10s) -- a generous 1.3x margin over strictly
      // increasing tolerates real scheduling/JVM-startup jitter while still distinguishing genuine
      // escalation from the bug this guards against (every gap collapsing to ~1s because
      // recordSuccess() fired immediately after every respawn, regardless of stability).
      for (int i = 1; i < gaps.size(); i++) {
        Duration previous = gaps.get(i - 1);
        Duration current = gaps.get(i);
        assertTrue(
            current.toMillis() > previous.toMillis() * 1.3,
            "expected gap "
                + i
                + " ("
                + current
                + ") to be markedly longer than the previous gap ("
                + previous
                + "); observed gaps="
                + gaps);
      }
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void a_respawn_that_stays_up_past_the_stability_threshold_resets_the_backoff() throws Exception {
    Path counterFile = tempDir.resolve("counter-reset");
    Path socketPath = tempDir.resolve("socket-reset");
    // Crashes instantly on the first 2 invocations, then stays up 1500ms before crashing again --
    // long enough to clear the 1000ms stability threshold below.
    List<String> command = crashingCommand(counterFile, 2, 1500, 1);

    RestartTracker tracker =
        new RestartTracker(
            Duration.ofMillis(500), 3.0, Duration.ofSeconds(5), 5, Duration.ofMinutes(10));

    try (WorkerProcessSupervisor supervisor =
        new WorkerProcessSupervisor(
            "reset",
            command,
            socketPath,
            tracker,
            id -> fail("should not exhaust its budget"),
            Optional.empty(),
            Duration.ofMillis(1000))) {
      supervisor.start();

      List<Instant> spawnTimes =
          observeDistinctPidTimestamps(supervisor, 4, Duration.ofSeconds(20));
      List<Duration> gaps = new ArrayList<>();
      for (int i = 1; i < spawnTimes.size(); i++) {
        gaps.add(Duration.between(spawnTimes.get(i - 1), spawnTimes.get(i)));
      }

      // gap 0: spawn1 -> spawn2, attempt 1 delay (~500ms) + JVM startup overhead.
      // gap 1: spawn2 -> spawn3, attempt 2 delay (~1500ms = 500ms * 3^1) + startup -- the
      //        escalated delay before the third (stable-for-1500ms) process starts.
      // gap 2: spawn3 -> spawn4 -- unlike the other two, this one *includes* spawn3's own 1500ms
      //        stable lifetime (it doesn't crash until then), plus whatever delay follows: ~500ms
      //        (attempt 1 again, total ~2.2s) if the stability confirmation correctly reset the
      //        tracker while spawn3 was still sleeping, or ~4500ms (500ms * 3^2, total ~6.2s) if
      //        it didn't. The two are far enough apart (2.2s vs 6.2s) that a 4s cutoff cleanly
      //        distinguishes them despite scheduling/JVM-startup jitter.
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

  @Test
  @Timeout(value = 20, unit = TimeUnit.SECONDS)
  void an_exit_code_3_is_classified_as_oom() throws Exception {
    Path counterFile = tempDir.resolve("counter-oom");
    Path socketPath = tempDir.resolve("socket-oom");
    Path workerLogRoot = tempDir.resolve("logs-oom");
    List<String> command =
        crashingCommand(counterFile, Integer.MAX_VALUE, 0, WorkerProcessSupervisor.OOM_EXIT_CODE);
    RestartTracker tracker =
        new RestartTracker(
            Duration.ofMillis(200), 2.0, Duration.ofSeconds(5), 10, Duration.ofMinutes(10));
    BlockingQueue<CrashInfo> crashes = new LinkedBlockingQueue<>();

    try (WorkerProcessSupervisor supervisor =
        new WorkerProcessSupervisor(
            "oom",
            command,
            socketPath,
            tracker,
            id -> {},
            Optional.empty(),
            Duration.ofSeconds(30),
            Optional.of(workerLogRoot),
            crashes::add)) {
      supervisor.start();

      CrashInfo crash = crashes.poll(10, TimeUnit.SECONDS);
      assertNotNull(crash, "expected a crash classification to be reported");
      assertEquals(CrashInfo.Cause.OOM, crash.cause());
      assertEquals(WorkerProcessSupervisor.OOM_EXIT_CODE, crash.exitCode());
    }
  }

  @Test
  @Timeout(value = 20, unit = TimeUnit.SECONDS)
  void an_exit_with_a_fresh_crash_dump_is_classified_as_native_crash() throws Exception {
    Path counterFile = tempDir.resolve("counter-native");
    Path socketPath = tempDir.resolve("socket-native");
    Path workerLogRoot = tempDir.resolve("logs-native");
    List<String> command =
        crashingCommandWithHsErr(counterFile, Integer.MAX_VALUE, 0, 134, workerLogRoot);
    RestartTracker tracker =
        new RestartTracker(
            Duration.ofMillis(200), 2.0, Duration.ofSeconds(5), 10, Duration.ofMinutes(10));
    BlockingQueue<CrashInfo> crashes = new LinkedBlockingQueue<>();

    try (WorkerProcessSupervisor supervisor =
        new WorkerProcessSupervisor(
            "native",
            command,
            socketPath,
            tracker,
            id -> {},
            Optional.empty(),
            Duration.ofSeconds(30),
            Optional.of(workerLogRoot),
            crashes::add)) {
      supervisor.start();

      CrashInfo crash = crashes.poll(10, TimeUnit.SECONDS);
      assertNotNull(crash, "expected a crash classification to be reported");
      assertEquals(CrashInfo.Cause.NATIVE_CRASH, crash.cause());
      assertTrue(crash.hsErrLog().isPresent());
      assertTrue(Files.exists(crash.hsErrLog().orElseThrow()));
    }
  }

  @Test
  @Timeout(value = 20, unit = TimeUnit.SECONDS)
  void a_plain_exit_with_no_crash_dump_is_classified_as_unknown() throws Exception {
    Path counterFile = tempDir.resolve("counter-unknown");
    Path socketPath = tempDir.resolve("socket-unknown");
    Path workerLogRoot = tempDir.resolve("logs-unknown");
    List<String> command = crashingCommand(counterFile, Integer.MAX_VALUE, 0, 1);
    RestartTracker tracker =
        new RestartTracker(
            Duration.ofMillis(200), 2.0, Duration.ofSeconds(5), 10, Duration.ofMinutes(10));
    BlockingQueue<CrashInfo> crashes = new LinkedBlockingQueue<>();

    try (WorkerProcessSupervisor supervisor =
        new WorkerProcessSupervisor(
            "unknown",
            command,
            socketPath,
            tracker,
            id -> {},
            Optional.empty(),
            Duration.ofSeconds(30),
            Optional.of(workerLogRoot),
            crashes::add)) {
      supervisor.start();

      CrashInfo crash = crashes.poll(10, TimeUnit.SECONDS);
      assertNotNull(crash, "expected a crash classification to be reported");
      assertEquals(CrashInfo.Cause.UNKNOWN, crash.cause());
      assertTrue(crash.hsErrLog().isEmpty());
    }
  }

  private List<String> crashingCommand(
      Path counterFile, int immediateCrashCount, long stableSleepMillis, int exitCode) {
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");
    return List.of(
        javaExecutable,
        "-cp",
        classpath,
        CrashingWorkerDriver.class.getName(),
        counterFile.toAbsolutePath().toString(),
        Integer.toString(immediateCrashCount),
        Long.toString(stableSleepMillis),
        Integer.toString(exitCode));
  }

  private List<String> crashingCommandWithHsErr(
      Path counterFile,
      int immediateCrashCount,
      long stableSleepMillis,
      int exitCode,
      Path hsErrDir) {
    List<String> command =
        new ArrayList<>(
            crashingCommand(counterFile, immediateCrashCount, stableSleepMillis, exitCode));
    command.add(hsErrDir.toAbsolutePath().toString());
    return command;
  }

  /**
   * Polls {@link WorkerProcessSupervisor#process()} (package-private, test/inspection only) until
   * {@code count} distinct pids have been observed, timestamping the moment each new pid first
   * appears -- {@code Process#pid()} stays queryable after the process has already exited, so this
   * never races the crash itself, only the much-coarser respawn timing.
   */
  private List<Instant> observeDistinctPidTimestamps(
      WorkerProcessSupervisor supervisor, int count, Duration timeout) throws InterruptedException {
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
