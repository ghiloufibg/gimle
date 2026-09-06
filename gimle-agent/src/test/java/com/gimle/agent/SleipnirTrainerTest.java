package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SleipnirTrainer} driven directly via {@link SleipnirTrainer#trainIfNeeded} against an
 * injectable {@link SleipnirTrainer.TrainingRun} fake -- the same test-driving convention {@code
 * NetworkPolicyRelayTest} already establishes for {@code pollOnce()} -- so every resilience case
 * here runs with no real JVM spawn. The one test that must spawn a real training JVM end to end is
 * separately gated behind {@code @Tag("aotbench")}, reusing {@link RealWorkerClasspath} the same
 * way {@code WorkerStartupBenchIT} does.
 */
class SleipnirTrainerTest {

  private static final String JAVA_EXECUTABLE =
      Path.of(System.getProperty("java.home"), "bin", "java").toString();

  @TempDir Path tempDir;

  private SleipnirCache cache;
  private RecordingTrainingRun fake;
  private SleipnirTrainer trainer;

  private void setUp() {
    cache =
        new SleipnirCache(tempDir.resolve("aot-cache"), new ConcurrentHashMap<>(), JAVA_EXECUTABLE);
    fake = new RecordingTrainingRun();
    trainer = new SleipnirTrainer(JAVA_EXECUTABLE, cache, fake);
  }

  @Test
  void an_ineligible_directory_classpath_never_invokes_training() throws IOException {
    setUp();
    List<String> commandTail = List.of("-cp", tempDir.toString(), "com.gimle.worker.WorkerMain");

    trainer.trainIfNeeded(commandTail);

    assertEquals(0, fake.invocations.get());
    assertFalse(cache.cacheFor(commandTail).isPresent());
  }

  @Test
  void a_training_failure_leaves_no_cache_entry_and_never_propagates() throws IOException {
    setUp();
    List<String> commandTail = eligibleCommandTail();
    fake.failure = new IOException("simulated training failure");

    trainer.trainIfNeeded(commandTail); // must not throw

    assertEquals(1, fake.invocations.get());
    assertFalse(cache.cacheFor(commandTail).isPresent());
  }

  @Test
  void a_successful_training_run_commits_and_a_second_call_does_not_train_again()
      throws IOException {
    setUp();
    List<String> commandTail = eligibleCommandTail();

    trainer.trainIfNeeded(commandTail);
    assertEquals(1, fake.invocations.get());
    assertTrue(cache.cacheFor(commandTail).isPresent());

    trainer.trainIfNeeded(commandTail);
    assertEquals(1, fake.invocations.get(), "a cache hit must short-circuit before training again");
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void a_concurrent_second_call_for_the_same_key_does_not_start_a_second_training_run()
      throws Exception {
    setUp();
    List<String> commandTail = eligibleCommandTail();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    fake.entered = entered;
    fake.release = release;

    Thread first = Thread.ofVirtual().start(() -> trainer.trainIfNeeded(commandTail));
    assertTrue(entered.await(5, TimeUnit.SECONDS), "the first call never entered training");

    Thread second = Thread.ofVirtual().start(() -> trainer.trainIfNeeded(commandTail));
    // Proving a negative (the second call did NOT start a second training run) has no faster
    // deterministic signal than a bounded wait without deeper lock instrumentation -- the per-key
    // lock, if broken, would very likely have let the second call race past well within this
    // window, since nothing else on this path takes any time.
    Thread.sleep(300);
    assertEquals(
        1, fake.invocations.get(), "a concurrent second call must not have started a second run");

    release.countDown();
    first.join(5_000);
    second.join(5_000);

    assertEquals(1, fake.invocations.get());
    assertTrue(cache.cacheFor(commandTail).isPresent());
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void start_fires_training_at_most_once_even_when_called_twice() throws Exception {
    setUp();
    List<String> commandTail = eligibleCommandTail();
    CountDownLatch entered = new CountDownLatch(1);
    fake.entered = entered;

    trainer.start(commandTail);
    assertTrue(entered.await(5, TimeUnit.SECONDS), "the first start() never fired training");
    // Wait for the background training thread to actually finish committing, not just enter --
    // cacheFor is the observable "training is done" signal available from outside the class.
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!cache.cacheFor(commandTail).isPresent() && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertTrue(cache.cacheFor(commandTail).isPresent(), "training never committed");

    trainer.start(commandTail); // must be a no-op -- started already flipped
    Thread.sleep(200); // give a wrongly-fired second background thread a moment to have run

    assertEquals(1, fake.invocations.get());
  }

  /** A commandTail whose classpath entry is a real (fake-content) jar file -- eligible. */
  private List<String> eligibleCommandTail() throws IOException {
    Path jar = tempDir.resolve("worker.jar");
    Files.writeString(jar, "fake-worker-bytes");
    return List.of("-cp", jar.toString(), "com.gimle.worker.WorkerMain");
  }

  /**
   * Records invocations and, on success, writes real bytes to whatever {@code -XX:AOTCacheOutput=}
   * path {@link SleipnirTrainer} built the command with -- {@code SleipnirTrainer}'s own success
   * path checks the output file is non-empty before committing, so a fake standing in for a real
   * JVM spawn must still produce one.
   */
  private static final class RecordingTrainingRun implements SleipnirTrainer.TrainingRun {
    final AtomicInteger invocations = new AtomicInteger();
    volatile CountDownLatch entered;
    volatile CountDownLatch release;
    volatile IOException failure;

    @Override
    public void run(List<String> command, Path expectedOutput, Duration timeout)
        throws IOException {
      invocations.incrementAndGet();
      CountDownLatch enteredLatch = entered;
      if (enteredLatch != null) {
        enteredLatch.countDown();
      }
      CountDownLatch releaseLatch = release;
      if (releaseLatch != null) {
        try {
          releaseLatch.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      IOException toThrow = failure;
      if (toThrow != null) {
        throw toThrow;
      }
      Files.writeString(Path.of(extractAotCacheOutput(command)), "trained-bytes");
    }

    private static String extractAotCacheOutput(List<String> command) {
      String prefix = "-XX:AOTCacheOutput=";
      for (String arg : command) {
        if (arg.startsWith(prefix)) {
          return arg.substring(prefix.length());
        }
      }
      throw new IllegalStateException("no -XX:AOTCacheOutput= found in command: " + command);
    }
  }
}
