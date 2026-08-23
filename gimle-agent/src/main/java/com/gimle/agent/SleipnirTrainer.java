package com.gimle.agent;

import com.gimle.core.protocol.ControlMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent-side orchestration for populating {@link SleipnirCache}: spawns exactly one training worker
 * JVM (JEP 514's {@code -XX:AOTCacheOutput=}, {@code WorkerMain}'s own {@code
 * gimle.worker.aotTraining} early-exit path from Phase A), waits for it to boot and exit cleanly,
 * then hands the verified output to {@link SleipnirCache#commit}. No hosted module is ever
 * installed during training, so the cache it produces holds no tenant or module bytes by
 * construction.
 *
 * <p>Deliberately a separate class from {@link SleipnirCache}: this class's work is slow,
 * background, and one-shot (a subprocess spawn-and-wait, done once per agent lifetime), while
 * {@code SleipnirCache}'s {@code cacheFor} lookup is fast, synchronous, and called on {@code
 * startInstance}'s hot path -- a real difference in concurrency shape {@code ArtifactPullCache}'s
 * own single-class precedent doesn't have.
 */
final class SleipnirTrainer {

  private static final Logger log = LoggerFactory.getLogger(SleipnirTrainer.class);
  private static final Duration TRAINING_TIMEOUT = Duration.ofSeconds(60);

  private final String javaExecutable;
  private final SleipnirCache cache;
  private final TrainingRun trainingRun;
  private final ConcurrentHashMap<String, Object> locksByKey = new ConcurrentHashMap<>();
  private final AtomicBoolean started = new AtomicBoolean(false);

  SleipnirTrainer(String javaExecutable, SleipnirCache cache) {
    this(javaExecutable, cache, new RealTrainingRun());
  }

  /** Test-only seam: a fake {@link TrainingRun} avoids a real JVM spawn per resilience test. */
  SleipnirTrainer(String javaExecutable, SleipnirCache cache, TrainingRun trainingRun) {
    this.javaExecutable = javaExecutable;
    this.cache = cache;
    this.trainingRun = trainingRun;
  }

  /**
   * Fires training in the background, once, at agent startup -- {@code commandTail} is a CLI
   * argument to {@code AgentMain.main}, known up front (the same value every worker on this node
   * gets spawned with), so unlike an instance-triggered action this can start immediately, off the
   * critical path, the same "construct, then {@code .start()}" shape {@code NetworkPolicyRelay}
   * already establishes. {@code started} guards against a second call (there is only ever one
   * caller today, {@code AgentMain.main}, but the guard costs nothing and documents the intent).
   */
  void start(List<String> commandTail) {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    Thread.ofVirtual().name("gimle-sleipnir-trainer").start(() -> trainIfNeeded(commandTail));
  }

  /**
   * The synchronous body {@link #start} backgrounds -- exposed separately so tests can drive it
   * directly without a thread, the same test-driving convention {@code NetworkPolicyRelay#pollOnce}
   * already establishes. Idempotent: a cache hit (or an ineligible classpath) is a fast no-op; two
   * concurrent calls for the same key serialize rather than racing a duplicate training JVM.
   */
  void trainIfNeeded(List<String> commandTail) {
    Optional<String> maybeKey = cache.keyFor(commandTail);
    if (maybeKey.isEmpty()) {
      return; // ineligible classpath -- SleipnirCache.keyFor already logged why, once.
    }
    String key = maybeKey.get();
    if (cache.cacheFor(commandTail).isPresent()) {
      cache.sweep(); // still sweep: clears stale entries from a prior jar/flag version.
      return;
    }
    Object lock = locksByKey.computeIfAbsent(key, k -> new Object());
    synchronized (lock) {
      // Re-check under the lock: a concurrent caller may have just finished training this exact
      // key while this thread was waiting to acquire it.
      if (cache.cacheFor(commandTail).isPresent()) {
        cache.sweep();
        return;
      }
      trainOnce(key, commandTail);
    }
  }

  private void trainOnce(String key, List<String> commandTail) {
    Path tmp;
    try {
      tmp = cache.newTrainingOutputPath(key);
    } catch (IOException e) {
      log.warn(
          "Sleipnir: could not prepare a training output path for key {}: {}", key, e.getMessage());
      return;
    }
    try {
      trainingRun.run(buildTrainingCommand(commandTail, tmp), TRAINING_TIMEOUT);
      if (Files.size(tmp) <= 0) {
        throw new IOException("training produced an empty AOT cache file");
      }
      cache.commit(key, tmp);
    } catch (Exception e) {
      // Any failure here -- timeout, non-zero exit, an empty file, IOException -- must never
      // affect worker spawning: log and continue uncached, exactly like a missing cache today.
      log.warn(
          "Sleipnir: training failed for key {}; workers will spawn uncached: {}",
          key,
          e.getMessage());
      deleteQuietly(tmp);
    } finally {
      cache.sweep();
    }
  }

  /**
   * {@code javaExecutable} + the same stable flags every real worker gets (so a JFR/AOT interaction
   * is exercised exactly as it will be in production) + fixed representative limits (independent of
   * any real instance's own resource request -- this JVM never hosts a module) + the two
   * training-specific flags + {@code commandTail} + a placeholder node id/tenant (this worker never
   * reports real instance state, so neither needs to be meaningful) -- the control-socket path is
   * appended by {@link TrainingRun} itself, mirroring {@code WorkerProcessSupervisor}'s own "always
   * appended last, by the spawner" convention.
   */
  private List<String> buildTrainingCommand(List<String> commandTail, Path tmpOutput) {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable);
    command.addAll(AgentMain.stableWorkerFlags());
    command.add("-Xmx512m");
    command.add("-XX:ActiveProcessorCount=1");
    command.add("-XX:AOTCacheOutput=" + tmpOutput.toAbsolutePath());
    command.add("-Dgimle.worker.aotTraining=true");
    command.addAll(commandTail);
    command.add("sleipnir-training");
    command.add("");
    return command;
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      log.warn("Sleipnir: failed to delete leftover training output {}: {}", file, e.getMessage());
    }
  }

  /** Spawns one training worker and waits for it to boot (send {@code Hello}) then exit cleanly. */
  interface TrainingRun {
    void run(List<String> command, Duration timeout) throws IOException;
  }

  /**
   * Production {@link TrainingRun}: real {@code ProcessBuilder} spawn against a real {@link
   * ControlChannelServer}, mirroring {@code WorkerStartupBenchIT.spawnAndAwaitHello}'s shape --
   * that method is test code in {@code src/test} and can't be depended on from here, so this is a
   * new, small implementation rather than shared code, the same non-sharing precedent {@code
   * WorkerConnection}'s own javadoc already documents between the agent and worker sides of the
   * control channel.
   */
  private static final class RealTrainingRun implements TrainingRun {

    @Override
    public void run(List<String> command, Duration timeout) throws IOException {
      Path socketPath = Files.createTempDirectory("gimle-sleipnir-training-uds-").resolve("c.sock");
      List<String> full = new ArrayList<>(command);
      full.add(socketPath.toString());
      Instant deadline = Instant.now().plus(timeout);

      try (ControlChannelServer server = new ControlChannelServer(socketPath)) {
        Process process = new ProcessBuilder(full).redirectErrorStream(true).start();
        List<String> outputLines = Collections.synchronizedList(new ArrayList<>());
        Thread.ofVirtual().start(() -> drainOutput(process, outputLines));

        CompletableFuture<Void> helloFuture = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> awaitHello(server, helloFuture));
        try {
          helloFuture.get(millisUntil(deadline), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
          process.destroyForcibly();
          throw new IOException(
              "training worker did not send Hello within " + timeout + ": " + e.getMessage(), e);
        }

        boolean exited;
        try {
          exited = process.waitFor(millisUntil(deadline), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          process.destroyForcibly();
          throw new IOException("interrupted awaiting training worker exit", e);
        }
        if (!exited) {
          process.destroyForcibly();
          throw new IOException(
              "training worker did not exit within "
                  + timeout
                  + "; output=\n"
                  + String.join("\n", outputLines));
        }
        if (process.exitValue() != 0) {
          throw new IOException(
              "training worker exited "
                  + process.exitValue()
                  + "; output=\n"
                  + String.join("\n", outputLines));
        }
      }
    }

    private static long millisUntil(Instant deadline) {
      return Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
    }

    private static void drainOutput(Process process, List<String> outputLines) {
      try (var reader = process.inputReader()) {
        String line;
        while ((line = reader.readLine()) != null) {
          outputLines.add(line);
        }
      } catch (IOException ignored) {
        // Process destroyed mid-read (the common case on timeout/failure) -- nothing left to
        // capture.
      }
    }

    private static void awaitHello(
        ControlChannelServer server, CompletableFuture<Void> helloFuture) {
      try (WorkerConnection connection = server.accept()) {
        Optional<ControlMessage> received = connection.receive();
        if (received.isPresent() && received.get() instanceof ControlMessage.Hello) {
          helloFuture.complete(null);
        } else {
          helloFuture.completeExceptionally(
              new IllegalStateException("expected Hello, got " + received));
        }
      } catch (IOException e) {
        helloFuture.completeExceptionally(new UncheckedIOException(e));
      }
    }
  }
}
