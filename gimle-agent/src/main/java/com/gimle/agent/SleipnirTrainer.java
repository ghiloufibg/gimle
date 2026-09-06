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
 * JVM (JEP 514's {@code -XX:AOTCacheOutput=}), waits for it to report {@code Hello}, then closes
 * its control channel so it shuts down through its own path, and hands the verified output to
 * {@link SleipnirCache#commit}. No hosted module is ever installed during training, so the cache it
 * produces holds no tenant or module bytes by construction.
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
      trainingRun.run(buildTrainingCommand(commandTail, tmp), tmp, TRAINING_TIMEOUT);
      if (!Files.isRegularFile(tmp) || Files.size(tmp) <= 0) {
        throw new IOException("training produced no usable AOT cache file at " + tmp);
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
   * any real instance's own resource request -- this JVM never hosts a module) + the one
   * training-specific flag + {@code commandTail} + a placeholder node id/tenant (this worker never
   * reports real instance state, so neither needs to be meaningful) -- the control-socket path is
   * appended by {@link TrainingRun} itself, mirroring {@code WorkerProcessSupervisor}'s own "always
   * appended last, by the spawner" convention.
   *
   * <p>Deliberately an ordinary worker command otherwise: the JVM writes the cache from the
   * shutdown path of a process that ends of its own accord, so this worker has to reach that path
   * the way every worker does -- its control channel closing -- rather than be told to return from
   * {@code main} early. Returning from {@code main} does not end a worker JVM at all (its
   * JFR-backed accounting keeps a non-daemon thread running for as long as the process lives), and
   * a worker terminated by a signal instead of ending on its own skips the cache write entirely.
   */
  private List<String> buildTrainingCommand(List<String> commandTail, Path tmpOutput) {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable);
    command.addAll(AgentMain.stableWorkerFlags());
    command.add("-Xmx512m");
    command.add("-XX:ActiveProcessorCount=1");
    command.add("-XX:AOTCacheOutput=" + tmpOutput.toAbsolutePath());
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

  /**
   * Spawns one training worker, waits for it to boot (send {@code Hello}), then brings it down
   * cleanly so the JVM writes {@code expectedOutput}.
   */
  interface TrainingRun {
    void run(List<String> command, Path expectedOutput, Duration timeout) throws IOException;
  }

  /**
   * Production {@link TrainingRun}: real {@code ProcessBuilder} spawn against a real {@link
   * ControlChannelServer}, mirroring {@code WorkerStartupBenchIT.spawnAndAwaitHello}'s shape --
   * that method is test code in {@code src/test} and can't be depended on from here, so this is a
   * new, small implementation rather than shared code, the same non-sharing precedent {@code
   * WorkerConnection}'s own javadoc already documents between the agent and worker sides of the
   * control channel.
   */
  static final class RealTrainingRun implements TrainingRun {

    @Override
    public void run(List<String> command, Path expectedOutput, Duration timeout)
        throws IOException {
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

        // awaitHello above has already closed the accepted connection, and this block's own
        // try-with-resources closes the listening socket: between them the worker sees its control
        // channel go away, which is how it is told to shut down. Hello is the end of training
        // anyway -- no module is ever installed into this worker, so nothing further would be
        // class-loaded by leaving it running.
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
        // A clean exit is not on its own proof that a cache was written: the JVM declines to write
        // one for reasons of its own (a classpath it cannot validate, most of all) and still exits
        // 0. Checked here, with the worker's own output to explain it, rather than left to surface
        // later as a bare "file not found" on a path.
        if (!Files.isRegularFile(expectedOutput) || Files.size(expectedOutput) <= 0) {
          throw new IOException(
              "training worker exited 0 without writing an AOT cache to "
                  + expectedOutput
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
