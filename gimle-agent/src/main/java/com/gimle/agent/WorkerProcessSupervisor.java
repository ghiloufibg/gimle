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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spawns and supervises one worker JVM (design §4.1, §4.3). {@code baseCommand} is everything up to
 * (but not including) the worker's sole application argument -- the control-socket path, which this
 * class appends itself, so the same base command is reused across every respawn. Caller supplies
 * the fully-formed command (java executable, any {@code ResourceLimiter}-derived JVM flags,
 * module-path/classpath, main class) since discovering {@code gimle-worker}'s own runtime artifacts
 * is a packaging concern outside this class's job.
 *
 * <p>Restart is driven by {@link Process#onExit()} without a prior deliberate {@link #stop()} --
 * "destroy-and-respawn... sub-second," per the design's worker-level restart tier -- using the same
 * {@link RestartTracker} shape module-level restart uses inside {@code gimle-worker}, just with
 * different (caller-supplied) numeric parameters.
 */
public final class WorkerProcessSupervisor implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(WorkerProcessSupervisor.class);

  /**
   * How long a respawned process must stay alive before its restart is considered a genuine
   * recovery rather than another lap of a fast crash loop. Calling {@link
   * RestartTracker#recordSuccess()} unconditionally right after {@code spawn()} returns -- proving
   * only that the OS accepted the {@code exec()} call, not that the program ran for even one
   * instant -- would reset the backoff window on every single cycle of a worker that crashes
   * immediately on every launch, so that loop would retry forever at the initial delay instead of
   * ever reaching the design's documented "give up after N attempts in the window" outcome.
   */
  private static final Duration DEFAULT_STABLE_UPTIME_THRESHOLD = Duration.ofSeconds(10);

  private final String workerId;
  private final List<String> baseCommand;
  private final Path controlSocketPath;
  private final RestartTracker restartTracker;
  private final Consumer<String> onRestartBudgetExhausted;
  private final Optional<Path> systemLogFile;
  private final Duration stableUptimeThreshold;

  private volatile Process process;
  private volatile boolean closed;
  private volatile OutputStream systemLogStream;

  public WorkerProcessSupervisor(
      String workerId,
      List<String> baseCommand,
      Path controlSocketPath,
      RestartTracker restartTracker,
      Consumer<String> onRestartBudgetExhausted) {
    this(
        workerId,
        baseCommand,
        controlSocketPath,
        restartTracker,
        onRestartBudgetExhausted,
        Optional.empty(),
        DEFAULT_STABLE_UPTIME_THRESHOLD);
  }

  /**
   * {@code systemLogFile} (log-explorer-design.md §5) is where drained stdout lines that don't
   * parse as JSON get appended verbatim, tagged {@code category: "SYSTEM"} -- raw output that
   * bypassed the worker's own Logback JSON encoding entirely (a JVM startup banner before Logback
   * initializes, a module's stray {@code System.out.println}). Empty for a caller that doesn't want
   * SYSTEM capture (e.g. tests).
   */
  public WorkerProcessSupervisor(
      String workerId,
      List<String> baseCommand,
      Path controlSocketPath,
      RestartTracker restartTracker,
      Consumer<String> onRestartBudgetExhausted,
      Optional<Path> systemLogFile) {
    this(
        workerId,
        baseCommand,
        controlSocketPath,
        restartTracker,
        onRestartBudgetExhausted,
        systemLogFile,
        DEFAULT_STABLE_UPTIME_THRESHOLD);
  }

  /**
   * Same as the five/six-arg constructors, with an explicit {@code stableUptimeThreshold} (see
   * {@link #DEFAULT_STABLE_UPTIME_THRESHOLD}) rather than the default -- for tests that need a
   * respawned process to be confirmed stable inside a bounded test timeout instead of ten real
   * seconds.
   */
  public WorkerProcessSupervisor(
      String workerId,
      List<String> baseCommand,
      Path controlSocketPath,
      RestartTracker restartTracker,
      Consumer<String> onRestartBudgetExhausted,
      Optional<Path> systemLogFile,
      Duration stableUptimeThreshold) {
    this.workerId = workerId;
    this.baseCommand = List.copyOf(baseCommand);
    this.controlSocketPath = controlSocketPath;
    this.restartTracker = restartTracker;
    this.onRestartBudgetExhausted = onRestartBudgetExhausted;
    this.systemLogFile = systemLogFile;
    this.stableUptimeThreshold = stableUptimeThreshold;
  }

  public synchronized void start() throws IOException {
    spawn();
  }

  private void spawn() throws IOException {
    List<String> command = new ArrayList<>(baseCommand);
    command.add(controlSocketPath.toString());
    // Deliberately not inheritIO(): that would have the worker write directly to this JVM's own
    // native stdout, which -- when this JVM is itself a Surefire-forked test process -- is the
    // exact stream Surefire's own process-to-plugin protocol uses, corrupting it. Draining through
    // a pipe on a dedicated thread keeps the worker's output visible (via this logger) without
    // that collision, and without risking the worker blocking on a full, unread pipe buffer.
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    process = pb.start();
    log.info("spawned worker {} as pid {}", workerId, process.pid());
    String pid = String.valueOf(process.pid());
    Thread.ofVirtual()
        .name("gimle-worker-output-" + workerId + "-" + pid)
        .start(() -> drainOutput(process));
    process.onExit().thenRun(this::onExit);
  }

  private void drainOutput(Process worker) {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(worker.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (isJsonLine(line)) {
          // Already captured structurally by the worker's own Logback file appenders (design §5);
          // re-logging it here would just duplicate it in a different, non-JSON format.
          continue;
        }
        log.info("[worker {}] {}", workerId, line);
        captureSystemLine(line);
      }
    } catch (IOException e) {
      // The pipe closes when the worker exits; nothing left to drain.
    } finally {
      closeSystemLog();
    }
  }

  private static boolean isJsonLine(String line) {
    try {
      Json.parse(line);
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private void captureSystemLine(String line) {
    if (systemLogFile.isEmpty()) {
      return;
    }
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("timestamp", Instant.now().toString());
    entry.put("category", "SYSTEM");
    entry.put("raw", line);
    byte[] bytes = (Json.write(entry) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    try {
      OutputStream out = systemLogStreamOrOpen();
      synchronized (this) {
        out.write(bytes);
        out.flush();
      }
    } catch (IOException e) {
      log.warn("failed to write SYSTEM capture line for worker {}: {}", workerId, e.getMessage());
    }
  }

  private synchronized OutputStream systemLogStreamOrOpen() throws IOException {
    if (systemLogStream == null) {
      Path file = systemLogFile.orElseThrow();
      if (file.getParent() != null) {
        Files.createDirectories(file.getParent());
      }
      systemLogStream =
          Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    return systemLogStream;
  }

  private synchronized void closeSystemLog() {
    if (systemLogStream != null) {
      try {
        systemLogStream.close();
      } catch (IOException e) {
        // best-effort close when the worker's output pipe closes
      } finally {
        systemLogStream = null;
      }
    }
  }

  private void onExit() {
    if (closed) {
      return; // a deliberate stop(), not a crash -- no respawn.
    }
    log.warn("worker {} exited unexpectedly (code {})", workerId, process.exitValue());

    Instant now = Instant.now();
    if (!restartTracker.recordFailureAndCheckShouldRetry(now)) {
      log.error("worker {} exhausted its restart budget; giving up", workerId);
      onRestartBudgetExhausted.accept(workerId);
      return;
    }

    Duration delay = restartTracker.delayUntilNextAttempt(now);
    Thread.ofVirtual()
        .name("gimle-worker-respawn-" + workerId)
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
                  log.error("worker {} respawn failed: {}", workerId, e.getMessage());
                }
              }
            });
  }

  /**
   * Only calls {@link RestartTracker#recordSuccess()} once {@code spawnedProcess} has stayed alive
   * for {@link #stableUptimeThreshold} -- see {@link #DEFAULT_STABLE_UPTIME_THRESHOLD}'s javadoc
   * for why an immediate call would defeat backoff escalation for a fast crash loop. Guarded
   * against a stale confirmation firing after a later respawn already replaced {@link #process}:
   * that later respawn's own confirmation is what gets to decide, not this one.
   */
  private void scheduleStabilityConfirmation(Process spawnedProcess) {
    Thread.ofVirtual()
        .name("gimle-worker-stability-check-" + workerId)
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
  public synchronized void stop() {
    closed = true;
    if (process != null) {
      process.destroyForcibly();
    }
  }

  @Override
  public void close() {
    stop();
  }

  /** For tests/inspection only -- supervision itself never reads this back. */
  Process process() {
    return process;
  }
}
