package com.gimle.agent;

import com.gimle.core.logging.LogFileReader;
import com.gimle.core.protocol.Json;
import com.gimle.core.restart.RestartTracker;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spawns and supervises one worker JVM. {@code baseCommand} is a supplier of everything up to (but
 * not including) the worker's sole application argument -- the control-socket path, which this
 * class appends itself -- invoked fresh on every spawn, including a crash-triggered respawn, rather
 * than snapshotted once: some of what the caller bakes into that command (a Sleipnir-trained AOT
 * cache path, most notably) can only become available in the background after this supervisor is
 * first constructed, and a respawn is exactly the spawn most worth benefiting from it once it has.
 * Caller supplies the fully-formed command (java executable, any {@code ResourceLimiter}-derived
 * JVM flags, module-path/classpath, main class) since discovering {@code gimle-worker}'s own
 * runtime artifacts is a packaging concern outside this class's job.
 *
 * <p>Restart is driven by {@link Process#onExit()} without a prior deliberate {@link #stop()} --
 * matching this platform's worker-level restart tier of destroy-and-respawn within sub-second
 * latency -- using the same {@link RestartTracker} shape module-level restart uses inside {@code
 * gimle-worker}, just with different (caller-supplied) numeric parameters.
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
   * ever reaching the intended "give up after N attempts in the window" outcome.
   */
  static final Duration DEFAULT_STABLE_UPTIME_THRESHOLD = Duration.ofSeconds(10);

  /** HotSpot's exit code for {@code -XX:+ExitOnOutOfMemoryError} -- see {@link CrashInfo.Cause}. */
  static final int OOM_EXIT_CODE = 3;

  private static final Consumer<CrashInfo> NO_OP_ON_CRASH = info -> {};
  private static final Consumer<String> NO_OP_ON_RESPAWNED = workerId -> {};

  private final String workerId;
  private final Supplier<List<String>> baseCommand;
  private final Path controlSocketPath;
  private final RestartTracker restartTracker;
  private final Consumer<String> onRestartBudgetExhausted;
  private final Optional<Path> systemLogFile;
  private final Duration stableUptimeThreshold;
  private final Optional<Path> workerLogRoot;
  private final Consumer<CrashInfo> onCrash;
  private final Consumer<String> onRespawned;

  private volatile Process process;
  private volatile boolean closed;
  private volatile OutputStream systemLogStream;
  private volatile long lastPid;
  // Bytes written to systemLogStream's current target since it was last opened or rotated --
  // tracked in memory rather than re-stat()ing the file on every captured line, the same
  // avoid-a-syscall-per-write posture SizeBasedTriggeringPolicy's own checkIncrement exists to
  // relax, just unconditional here since this path's volume is nowhere near where that matters.
  private long systemLogBytes;

  public WorkerProcessSupervisor(
      String workerId,
      Supplier<List<String>> baseCommand,
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
        DEFAULT_STABLE_UPTIME_THRESHOLD,
        Optional.empty(),
        NO_OP_ON_CRASH,
        NO_OP_ON_RESPAWNED);
  }

  /**
   * {@code systemLogFile} is where drained stdout lines that don't parse as JSON get appended
   * verbatim, tagged {@code category: "SYSTEM"} -- raw output that bypassed the worker's own
   * Logback JSON encoding entirely (a JVM startup banner before Logback initializes, a module's
   * stray {@code System.out.println}). Empty for a caller that doesn't want SYSTEM capture (e.g.
   * tests).
   */
  public WorkerProcessSupervisor(
      String workerId,
      Supplier<List<String>> baseCommand,
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
        DEFAULT_STABLE_UPTIME_THRESHOLD,
        Optional.empty(),
        NO_OP_ON_CRASH,
        NO_OP_ON_RESPAWNED);
  }

  /**
   * Same as the five/six-arg constructors, with an explicit {@code stableUptimeThreshold} (see
   * {@link #DEFAULT_STABLE_UPTIME_THRESHOLD}) rather than the default -- for tests that need a
   * respawned process to be confirmed stable inside a bounded test timeout instead of ten real
   * seconds.
   */
  public WorkerProcessSupervisor(
      String workerId,
      Supplier<List<String>> baseCommand,
      Path controlSocketPath,
      RestartTracker restartTracker,
      Consumer<String> onRestartBudgetExhausted,
      Optional<Path> systemLogFile,
      Duration stableUptimeThreshold) {
    this(
        workerId,
        baseCommand,
        controlSocketPath,
        restartTracker,
        onRestartBudgetExhausted,
        systemLogFile,
        stableUptimeThreshold,
        Optional.empty(),
        NO_OP_ON_CRASH,
        NO_OP_ON_RESPAWNED);
  }

  /**
   * {@code workerLogRoot} is where {@code -XX:ErrorFile=<workerLogRoot>/hs_err_pid%p.log} (see
   * {@link AgentMain#buildWorkerCommand}) writes a native crash dump -- {@code empty} means no
   * crash-dump correlation, matching every shorter overload above. {@code onCrash} is called from
   * {@link #onExit} with a best-effort {@link CrashInfo} classification of every unexpected exit,
   * before the respawn decision is made; the default no-op matches this class's previous behavior
   * of only ever logging the raw exit code. Delegates to the ten-arg canonical constructor with a
   * no-op {@code onRespawned} -- for a caller (or test) that doesn't care about redriving a
   * respawned worker's handshake.
   */
  public WorkerProcessSupervisor(
      String workerId,
      Supplier<List<String>> baseCommand,
      Path controlSocketPath,
      RestartTracker restartTracker,
      Consumer<String> onRestartBudgetExhausted,
      Optional<Path> systemLogFile,
      Duration stableUptimeThreshold,
      Optional<Path> workerLogRoot,
      Consumer<CrashInfo> onCrash) {
    this(
        workerId,
        baseCommand,
        controlSocketPath,
        restartTracker,
        onRestartBudgetExhausted,
        systemLogFile,
        stableUptimeThreshold,
        workerLogRoot,
        onCrash,
        NO_OP_ON_RESPAWNED);
  }

  /**
   * {@code onRespawned} is called with {@code workerId} after a crash-triggered respawn's {@link
   * #spawn()} call returns successfully -- a fresh worker process shares this supervisor's {@code
   * workerId} and control-socket path, but starts with none of the platform state (installed
   * module, resolved layer, started lifecycle) the previous process had, so the caller needs a
   * signal to re-drive that handshake from scratch. Called outside this instance's own monitor
   * lock, since the caller's handshake involves a blocking {@code accept()} that must never hold up
   * a concurrent {@link #stop()}.
   */
  public WorkerProcessSupervisor(
      String workerId,
      Supplier<List<String>> baseCommand,
      Path controlSocketPath,
      RestartTracker restartTracker,
      Consumer<String> onRestartBudgetExhausted,
      Optional<Path> systemLogFile,
      Duration stableUptimeThreshold,
      Optional<Path> workerLogRoot,
      Consumer<CrashInfo> onCrash,
      Consumer<String> onRespawned) {
    this.workerId = workerId;
    this.baseCommand = baseCommand;
    this.controlSocketPath = controlSocketPath;
    this.restartTracker = restartTracker;
    this.onRestartBudgetExhausted = onRestartBudgetExhausted;
    this.systemLogFile = systemLogFile;
    this.stableUptimeThreshold = stableUptimeThreshold;
    this.workerLogRoot = workerLogRoot;
    this.onCrash = onCrash;
    this.onRespawned = onRespawned;
  }

  public synchronized void start() throws IOException {
    spawn();
  }

  private void spawn() throws IOException {
    // Recomputed on every call (initial spawn and every respawn alike) -- not cached from
    // construction time -- so a respawn after this instance's supervisor was first built can pick
    // up state that only became available since (a Sleipnir cache that finished training in the
    // background, most notably) instead of being permanently locked to whatever the very first
    // spawn saw.
    List<String> command = new ArrayList<>(baseCommand.get());
    command.add(controlSocketPath.toString());
    // Deliberately not inheritIO(): that would have the worker write directly to this JVM's own
    // native stdout, which -- when this JVM is itself a Surefire-forked test process -- is the
    // exact stream Surefire's own process-to-plugin protocol uses, corrupting it. Draining through
    // a pipe on a dedicated thread keeps the worker's output visible (via this logger) without
    // that collision, and without risking the worker blocking on a full, unread pipe buffer.
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    process = pb.start();
    lastPid = process.pid();
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
          // Already captured structurally by the worker's own Logback file appenders;
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

  /**
   * The same size/count rotation {@link com.gimle.core.logging.RollingFileAppenders} gives every
   * Logback-routed log stream, hand-rolled here since this capture never goes through Logback --
   * raw stdout/stderr lines are drained and written directly. Reuses that class's own {@code .%i}
   * {@code FixedWindowRollingPolicy} naming and the same {@code gimle.log.maxFileSizeBytes}/ {@code
   * gimle.log.maxFiles} properties, so {@link com.gimle.core.logging.LogFileReader} (and this
   * agent's own {@code readMergedSystemLogs}) reads a rotated SYSTEM-capture file exactly the same
   * way it already reads a rotated platform/instance one. Before this, the file was opened {@code
   * CREATE, APPEND} and never rotated at all, growing unbounded for the lifetime of a long-lived or
   * crash-looping deployment.
   */
  private static final long DEFAULT_MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

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
      synchronized (this) {
        OutputStream out = systemLogStreamOrOpenLocked();
        long maxFileSize = Long.getLong("gimle.log.maxFileSizeBytes", DEFAULT_MAX_FILE_SIZE_BYTES);
        if (systemLogBytes + bytes.length > maxFileSize) {
          out = rotateSystemLogLocked();
        }
        out.write(bytes);
        out.flush();
        systemLogBytes += bytes.length;
      }
    } catch (IOException e) {
      log.warn("failed to write SYSTEM capture line for worker {}: {}", workerId, e.getMessage());
    }
  }

  private synchronized OutputStream systemLogStreamOrOpenLocked() throws IOException {
    if (systemLogStream == null) {
      Path file = systemLogFile.orElseThrow();
      Path parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      systemLogStream =
          Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      // A respawned supervisor reopens a file that may already carry bytes from before this
      // process restart -- picking that up rather than assuming 0 is what keeps the very next
      // write from silently exceeding maxFileSize before this class's own check ever fires.
      systemLogBytes = Files.exists(file) ? Files.size(file) : 0;
    }
    return systemLogStream;
  }

  /**
   * Classic {@code FixedWindowRollingPolicy} rollover: drop whatever already sits at the oldest
   * index, shift every remaining rotated copy up by one, then rename the active file to {@code .1}
   * and reopen a fresh empty one in its place.
   */
  private synchronized OutputStream rotateSystemLogLocked() throws IOException {
    Path file = systemLogFile.orElseThrow();
    if (systemLogStream != null) {
      systemLogStream.close();
      systemLogStream = null;
    }
    int maxIndex = Math.max(1, LogFileReader.configuredMaxFiles() - 1);
    Path oldest = file.resolveSibling(file.getFileName() + "." + maxIndex);
    Files.deleteIfExists(oldest);
    for (int i = maxIndex - 1; i >= 1; i--) {
      Path src = file.resolveSibling(file.getFileName() + "." + i);
      if (Files.exists(src)) {
        Files.move(
            src,
            file.resolveSibling(file.getFileName() + "." + (i + 1)),
            StandardCopyOption.REPLACE_EXISTING);
      }
    }
    if (Files.exists(file)) {
      Files.move(
          file,
          file.resolveSibling(file.getFileName() + ".1"),
          StandardCopyOption.REPLACE_EXISTING);
    }
    systemLogBytes = 0;
    return systemLogStreamOrOpenLocked();
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
    int exitCode = process.exitValue();
    log.warn("worker {} exited unexpectedly (code {})", workerId, exitCode);
    onCrash.accept(classifyCrash(exitCode));

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
                  return;
                }
              }
              onRespawned.accept(workerId);
            });
  }

  /**
   * Best-effort classification of the exit just observed -- exit code {@value #OOM_EXIT_CODE}
   * (HotSpot's own {@code -XX:+ExitOnOutOfMemoryError} code, unambiguous and portable) is {@code
   * OOM}; anything else with a fresh {@code hs_err_pid<pid>.log} on disk is {@code NATIVE_CRASH}
   * (that file is only ever written for a genuine native-level fault); everything else is {@code
   * UNKNOWN}. Uses {@link #lastPid}, captured at spawn time, rather than {@code process.pid()} --
   * safer to rely on than re-querying a {@link Process} handle after it has already exited.
   */
  private CrashInfo classifyCrash(int exitCode) {
    if (exitCode == OOM_EXIT_CODE) {
      return new CrashInfo(CrashInfo.Cause.OOM, exitCode, hsErrLogPath());
    }
    Optional<Path> hsErr = hsErrLogPath().filter(Files::exists);
    if (hsErr.isPresent()) {
      return new CrashInfo(CrashInfo.Cause.NATIVE_CRASH, exitCode, hsErr);
    }
    return new CrashInfo(CrashInfo.Cause.UNKNOWN, exitCode, Optional.empty());
  }

  private Optional<Path> hsErrLogPath() {
    return workerLogRoot.map(root -> root.resolve("hs_err_pid" + lastPid + ".log"));
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

  /**
   * The worker id this supervisor was constructed with -- fixed for its lifetime, including across
   * every respawn. Under Tier 1 density, several {@code SupervisedInstance}s can share one
   * supervisor; {@link AgentMain}'s crash-callback wiring uses this to find every instance a
   * crashed worker hosted, not just the one that happened to spawn it first.
   */
  String workerId() {
    return workerId;
  }
}
