package com.gimle.agent;

import com.gimle.core.restart.RestartTracker;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

  private final String workerId;
  private final List<String> baseCommand;
  private final Path controlSocketPath;
  private final RestartTracker restartTracker;
  private final Consumer<String> onRestartBudgetExhausted;

  private volatile Process process;
  private volatile boolean closed;

  public WorkerProcessSupervisor(
      String workerId,
      List<String> baseCommand,
      Path controlSocketPath,
      RestartTracker restartTracker,
      Consumer<String> onRestartBudgetExhausted) {
    this.workerId = workerId;
    this.baseCommand = List.copyOf(baseCommand);
    this.controlSocketPath = controlSocketPath;
    this.restartTracker = restartTracker;
    this.onRestartBudgetExhausted = onRestartBudgetExhausted;
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
        log.info("[worker {}] {}", workerId, line);
      }
    } catch (IOException e) {
      // The pipe closes when the worker exits; nothing left to drain.
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
                  restartTracker.recordSuccess();
                } catch (IOException e) {
                  log.error("worker {} respawn failed: {}", workerId, e.getMessage());
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
