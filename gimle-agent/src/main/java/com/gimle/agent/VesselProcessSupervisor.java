package com.gimle.agent;

import com.gimle.core.protocol.Json;
import com.gimle.core.restart.RestartTracker;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spawns and supervises one vessel process: {@code command} is the complete, ready-to-run command
 * line (unlike {@link WorkerProcessSupervisor}, which appends a control-socket path itself, a
 * vessel process never speaks Gimlé's own control protocol, so there is nothing to append), started
 * with {@code env} applied on top of whatever this agent's own JVM inherited. Restart-on-crash
 * reuses the same {@link RestartTracker}-driven destroy-and-respawn policy {@link
 * WorkerProcessSupervisor} already established for a dedicated worker JVM -- including that class's
 * {@code recordSuccess()}-once-stabilized behavior (see {@link #scheduleStabilityConfirmation}) --
 * a vessel gets the identical Tier-2-equivalent crash-domain guarantee -- but captures every
 * stdout/stderr line unconditionally as this instance's own APPLICATION log rather than
 * JSON-sniffing it: a vessel's output is whatever arbitrary program it runs, never Gimlé's own
 * structured Logback JSON.
 */
final class VesselProcessSupervisor implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(VesselProcessSupervisor.class);

  private final String key;
  private final List<String> command;
  private final Map<String, String> env;
  // Empty for a single-jar vessel (inherits the agent's own cwd, as always); a bundle's unpacked
  // directory (or a subdirectory its entrypoint names) otherwise, so relative paths in the
  // entrypoint command resolve against the bundle's own files.
  private final Optional<Path> workingDirectory;
  private final RestartTracker restartTracker;
  private final Consumer<String> onRestartBudgetExhausted;
  private final Path applicationLogFile;
  private final Consumer<String> onRespawned;
  private final Duration stableUptimeThreshold;

  private volatile Process process;
  private volatile boolean closed;
  private volatile OutputStream logStream;

  VesselProcessSupervisor(
      String key,
      List<String> command,
      Map<String, String> env,
      Optional<Path> workingDirectory,
      RestartTracker restartTracker,
      Consumer<String> onRestartBudgetExhausted,
      Path applicationLogFile,
      Consumer<String> onRespawned) {
    this(
        key,
        command,
        env,
        workingDirectory,
        restartTracker,
        onRestartBudgetExhausted,
        applicationLogFile,
        onRespawned,
        WorkerProcessSupervisor.DEFAULT_STABLE_UPTIME_THRESHOLD);
  }

  /**
   * Same as the eight-arg constructor, with an explicit {@code stableUptimeThreshold} rather than
   * {@link WorkerProcessSupervisor#DEFAULT_STABLE_UPTIME_THRESHOLD} -- for tests that need a
   * respawned vessel to be confirmed stable inside a bounded test timeout instead of ten real
   * seconds, the same reason {@link WorkerProcessSupervisor} exposes the equivalent overload.
   */
  VesselProcessSupervisor(
      String key,
      List<String> command,
      Map<String, String> env,
      Optional<Path> workingDirectory,
      RestartTracker restartTracker,
      Consumer<String> onRestartBudgetExhausted,
      Path applicationLogFile,
      Consumer<String> onRespawned,
      Duration stableUptimeThreshold) {
    this.key = key;
    this.command = List.copyOf(command);
    this.env = Map.copyOf(env);
    this.workingDirectory = workingDirectory;
    this.restartTracker = restartTracker;
    this.onRestartBudgetExhausted = onRestartBudgetExhausted;
    this.applicationLogFile = applicationLogFile;
    this.onRespawned = onRespawned;
    this.stableUptimeThreshold = stableUptimeThreshold;
  }

  synchronized void start() throws IOException {
    spawn();
  }

  /** The live process handle, for a caller to check {@link Process#isAlive()} directly. */
  Process process() {
    return process;
  }

  private void spawn() throws IOException {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.environment().putAll(env);
    workingDirectory.ifPresent(directory -> pb.directory(directory.toFile()));
    pb.redirectErrorStream(true);
    process = pb.start();
    log.info("spawned vessel {} as pid {}", key, process.pid());
    Thread.ofVirtual()
        .name("gimle-vessel-output-" + key + "-" + process.pid())
        .start(() -> drainOutput(process));
    process.onExit().thenRun(this::onExit);
  }

  private void drainOutput(Process vessel) {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(vessel.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        appendApplicationLine(line);
      }
    } catch (IOException e) {
      // The pipe closes when the vessel exits; nothing left to drain.
    } finally {
      closeLog();
    }
  }

  private void appendApplicationLine(String line) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("timestamp", Instant.now().toString());
    entry.put("level", "INFO");
    entry.put("category", "APPLICATION");
    entry.put("message", line);
    byte[] bytes = (Json.write(entry) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    try {
      OutputStream out = logStreamOrOpen();
      synchronized (this) {
        out.write(bytes);
        out.flush();
      }
    } catch (IOException e) {
      log.warn("failed to write vessel application log line for {}: {}", key, e.getMessage());
    }
  }

  private synchronized OutputStream logStreamOrOpen() throws IOException {
    if (logStream == null) {
      Path parent = applicationLogFile.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      logStream =
          Files.newOutputStream(
              applicationLogFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    return logStream;
  }

  private synchronized void closeLog() {
    if (logStream != null) {
      try {
        logStream.close();
      } catch (IOException e) {
        // best-effort close when the vessel's output pipe closes
      } finally {
        logStream = null;
      }
    }
  }

  private void onExit() {
    if (closed) {
      return; // a deliberate stop(), not a crash -- no respawn.
    }
    int exitCode = process.exitValue();
    log.warn("vessel {} exited unexpectedly (code {})", key, exitCode);

    Instant now = Instant.now();
    if (!restartTracker.recordFailureAndCheckShouldRetry(now)) {
      log.error("vessel {} exhausted its restart budget; giving up", key);
      onRestartBudgetExhausted.accept(key);
      return;
    }

    var delay = restartTracker.delayUntilNextAttempt(now);
    Thread.ofVirtual()
        .name("gimle-vessel-respawn-" + key)
        .start(
            () -> {
              try {
                Thread.sleep(delay);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              synchronized (this) {
                if (closed) {
                  return;
                }
                try {
                  spawn();
                  scheduleStabilityConfirmation(process);
                } catch (IOException e) {
                  log.error("vessel {} respawn failed: {}", key, e.getMessage());
                  return;
                }
              }
              onRespawned.accept(key);
            });
  }

  /**
   * Only calls {@link RestartTracker#recordSuccess()} once {@code spawnedProcess} has stayed alive
   * for {@code stableUptimeThreshold} -- see {@link
   * WorkerProcessSupervisor#DEFAULT_STABLE_UPTIME_THRESHOLD}'s javadoc for why an immediate call
   * would defeat backoff escalation for a fast crash loop. Without this, a vessel that crashes and
   * cleanly recovers every couple of minutes -- running healthily far longer than any reasonable
   * "stabilized" threshold in between -- accumulates toward the same restart budget with no reset,
   * and is eventually abandoned even though every individual outage was brief. Guarded against a
   * stale confirmation firing after a later respawn already replaced {@link #process}: that later
   * respawn's own confirmation is what gets to decide, not this one.
   */
  private void scheduleStabilityConfirmation(Process spawnedProcess) {
    Thread.ofVirtual()
        .name("gimle-vessel-stability-check-" + key)
        .start(
            () -> {
              try {
                Thread.sleep(stableUptimeThreshold);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              synchronized (this) {
                if (!closed && process == spawnedProcess && spawnedProcess.isAlive()) {
                  restartTracker.recordSuccess();
                }
              }
            });
  }

  /** Deliberate shutdown: the exit this triggers is not treated as a crash to respawn from. */
  synchronized void stop() {
    closed = true;
    if (process != null) {
      process.destroyForcibly();
    }
  }

  @Override
  public void close() {
    stop();
  }
}
