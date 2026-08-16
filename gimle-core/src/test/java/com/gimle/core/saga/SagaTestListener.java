package com.gimle.core.saga;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streams live test events from this test JVM to a Saga report server as {@link SagaEvent} NDJSON
 * batches POSTed to {@code <endpoint>/api/ingest}.
 *
 * <p>Registered via {@code META-INF/services} in gimle-core's test-jar, so it rides into every test
 * JVM in the repo. Two properties are therefore non-negotiable:
 *
 * <ul>
 *   <li><b>Dormant by default.</b> Unless {@code -Dgimle.saga.endpoint} is set when the JVM starts,
 *       construction keeps a single null field and every callback returns after one null check — no
 *       thread, no queue, no allocation.
 *   <li><b>Never blocks or fails a test.</b> When active, callbacks only {@code offer} into a
 *       bounded in-memory queue (full queue: the event is silently dropped); one virtual thread
 *       drains it, and every shipping failure is swallowed. One attempt per batch, no retries —
 *       best-effort by design.
 * </ul>
 */
public final class SagaTestListener implements TestExecutionListener {

  static final String ENDPOINT_PROPERTY = "gimle.saga.endpoint";
  static final String RUN_ID_PROPERTY = "gimle.saga.runId";
  static final String MODULE_PROPERTY = "gimle.saga.module";

  private static final int DEFAULT_QUEUE_CAPACITY = 10_000;
  private static final Duration FLUSH_WAIT = Duration.ofSeconds(3);

  private final Shipper shipper;

  public SagaTestListener() {
    final String endpoint = System.getProperty(ENDPOINT_PROPERTY);
    this.shipper =
        endpoint == null || endpoint.isBlank()
            ? null
            : new Shipper(endpoint.trim(), resolveRunId(), resolveModule(), DEFAULT_QUEUE_CAPACITY);
  }

  SagaTestListener(
      final String endpoint, final String runId, final String module, final int queueCapacity) {
    this.shipper = new Shipper(endpoint, runId, module, queueCapacity);
  }

  boolean active() {
    return shipper != null;
  }

  @Override
  public void executionStarted(final TestIdentifier identifier) {
    if (shipper == null || !identifier.isTest()) {
      return;
    }
    shipper.testStarted(identifier);
  }

  @Override
  public void executionFinished(final TestIdentifier identifier, final TestExecutionResult result) {
    if (shipper == null || !identifier.isTest()) {
      return;
    }
    shipper.testFinished(identifier, result);
  }

  @Override
  public void executionSkipped(final TestIdentifier identifier, final String reason) {
    if (shipper == null || !identifier.isTest()) {
      return;
    }
    shipper.testSkipped(identifier);
  }

  @Override
  public void testPlanExecutionFinished(final TestPlan testPlan) {
    if (shipper == null) {
      return;
    }
    shipper.awaitDrained(FLUSH_WAIT);
  }

  private static String resolveRunId() {
    final String configured = System.getProperty(RUN_ID_PROPERTY);
    if (configured != null && !configured.isBlank()) {
      return configured.trim();
    }
    return DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        + "_local";
  }

  private static String resolveModule() {
    final String configured = System.getProperty(MODULE_PROPERTY);
    if (configured != null && !configured.isBlank()) {
      return configured.trim();
    }
    final Path fileName = Path.of(System.getProperty("user.dir", "")).getFileName();
    return fileName == null ? "unknown" : fileName.toString();
  }

  /** All active-mode state, so a dormant listener carries nothing but one null reference. */
  private static final class Shipper {

    private static final Logger log = LoggerFactory.getLogger(SagaTestListener.class);

    private static final int MAX_BATCH = 500;
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);

    private final URI ingestUri;
    private final String runId;
    private final String module;
    private final BlockingQueue<SagaEvent> queue;
    private final HttpClient client;
    private final Map<String, StartedAttempt> started = new ConcurrentHashMap<>();
    // Static, not per-instance: Surefire's own rerun-on-failure mechanism (rerunFailingTestsCount,
    // driving failOnFlakeCount) re-invokes the JUnit Platform Launcher for each retry batch within
    // the same forked JVM, and a fresh Launcher rediscovers a brand-new SagaTestListener via
    // ServiceLoader -- an instance field here would silently reset every retry back to attempt 1,
    // and the flake ledger's own rule (a FAILED attempt N immediately followed by a PASSED attempt
    // N+1) would never fire for exactly the in-JVM reruns it exists to catch. Scoped by testId, so
    // unrelated tests never collide; bounded by the forked JVM's own lifetime, same as every other
    // piece of this listener's state.
    private static final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    // Events accepted into the queue but not yet through their (single) shipping attempt; lets
    // the end-of-plan flush wait for in-flight work without racing the drain thread's dequeue.
    private final AtomicLong pending = new AtomicLong();

    private record StartedAttempt(String testId, int attempt, long startNanos) {}

    Shipper(final String endpoint, final String runId, final String module, final int capacity) {
      this.ingestUri = URI.create(endpoint.replaceAll("/+$", "") + "/api/ingest");
      this.runId = runId;
      this.module = module;
      this.queue = new ArrayBlockingQueue<>(capacity);
      this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
      Thread.ofVirtual().name("gimle-saga-shipper").start(this::drainLoop);
    }

    void testStarted(final TestIdentifier identifier) {
      final String testId = testIdOf(identifier);
      final int attempt = nextAttempt(testId);
      started.put(identifier.getUniqueId(), new StartedAttempt(testId, attempt, System.nanoTime()));
      final List<String> tags = identifier.getTags().stream().map(TestTag::getName).toList();
      offer(new SagaEvent.TestStarted(runId, testId, tags, attempt));
    }

    void testFinished(final TestIdentifier identifier, final TestExecutionResult result) {
      final StartedAttempt start = started.remove(identifier.getUniqueId());
      final String testId = start == null ? testIdOf(identifier) : start.testId();
      final int attempt = start == null ? nextAttempt(testId) : start.attempt();
      final long durationMillis =
          start == null ? 0 : (System.nanoTime() - start.startNanos()) / 1_000_000;
      final SagaEvent.Outcome outcome =
          switch (result.getStatus()) {
            case SUCCESSFUL -> SagaEvent.Outcome.PASSED;
            case FAILED -> SagaEvent.Outcome.FAILED;
            case ABORTED -> SagaEvent.Outcome.ABORTED;
          };
      final Optional<Throwable> failure =
          outcome == SagaEvent.Outcome.FAILED ? result.getThrowable() : Optional.empty();
      offer(
          new SagaEvent.TestFinished(
              runId,
              testId,
              attempt,
              outcome,
              durationMillis,
              failure.map(t -> t.getClass().getName()),
              failure.map(Throwable::getMessage),
              failure.map(Shipper::signatureOf)));
    }

    void testSkipped(final TestIdentifier identifier) {
      final String testId = testIdOf(identifier);
      offer(
          new SagaEvent.TestFinished(
              runId,
              testId,
              nextAttempt(testId),
              SagaEvent.Outcome.SKIPPED,
              0,
              Optional.empty(),
              Optional.empty(),
              Optional.empty()));
    }

    void awaitDrained(final Duration wait) {
      final long deadline = System.nanoTime() + wait.toNanos();
      while (pending.get() > 0 && System.nanoTime() < deadline) {
        try {
          Thread.sleep(20);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }

    private int nextAttempt(final String testId) {
      return attempts.computeIfAbsent(testId, k -> new AtomicInteger()).incrementAndGet();
    }

    private void offer(final SagaEvent event) {
      if (queue.offer(event)) {
        pending.incrementAndGet();
      }
    }

    private void drainLoop() {
      final List<SagaEvent> batch = new ArrayList<>();
      while (true) {
        final SagaEvent head;
        try {
          head = queue.poll(100, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        if (head == null) {
          continue;
        }
        batch.clear();
        batch.add(head);
        queue.drainTo(batch, MAX_BATCH - 1);
        post(batch);
      }
    }

    private void post(final List<SagaEvent> batch) {
      try {
        final StringBuilder body = new StringBuilder();
        for (final SagaEvent event : batch) {
          body.append(SagaEventCodec.encode(event)).append('\n');
        }
        final HttpRequest request =
            HttpRequest.newBuilder(ingestUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        client.send(request, HttpResponse.BodyHandlers.discarding());
      } catch (final Exception e) {
        // A report server being down must never surface into a test run.
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        log.debug("saga event batch dropped: {}", e.toString());
      } finally {
        pending.addAndGet(-batch.size());
      }
    }

    private String testIdOf(final TestIdentifier identifier) {
      if (identifier.getSource().orElse(null) instanceof MethodSource method) {
        final String className = method.getClassName();
        return module
            + ":"
            + className.substring(className.lastIndexOf('.') + 1)
            + "#"
            + method.getMethodName();
      }
      // Non-method test sources (e.g. Cucumber pickles) have no class/method pair to name.
      return module + ":" + identifier.getDisplayName();
    }

    private static String signatureOf(final Throwable failure) {
      return FailureSignature.of(
          failure.getClass().getName(), failure.getMessage(), topFrame(failure));
    }

    private static String topFrame(final Throwable failure) {
      final StackTraceElement[] trace = failure.getStackTrace();
      if (trace.length == 0) {
        return null;
      }
      final StackTraceElement top = trace[0];
      final String file = top.getFileName() == null ? "Unknown" : top.getFileName();
      return top.getClassName() + "." + top.getMethodName() + "(" + file + ")";
    }
  }
}
