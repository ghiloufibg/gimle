package com.gimle.core.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestIdentifier;

/**
 * Drives {@link SagaTestListener} directly with synthetic identifiers against a stub ingest server,
 * so nothing here depends on (or mutates) the global system-property activation path.
 */
class SagaTestListenerTest {

  private static final String RUN_ID = "20260816T120000_abc1234";
  private static final String MODULE = "test-module";
  private static final String FIXTURE_CLASS = "com.gimle.core.saga.FakeGreeterTest";

  private final List<String> receivedLines = Collections.synchronizedList(new ArrayList<>());
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void ships_started_and_finished_events_for_a_passing_test() throws Exception {
    SagaTestListener listener = listenerAgainstStubServer(100);
    TestIdentifier test = testIdentifier("greets_politely");

    listener.executionStarted(test);
    listener.executionFinished(test, TestExecutionResult.successful());
    awaitReceived(2);

    SagaEvent.TestStarted startedEvent =
        assertInstanceOf(SagaEvent.TestStarted.class, decodedEvent(0));
    assertEquals(RUN_ID, startedEvent.runId());
    assertEquals("test-module:FakeGreeterTest#greets_politely", startedEvent.testId());
    assertEquals(1, startedEvent.attempt());
    assertEquals(List.of(), startedEvent.tags());
    SagaEvent.TestFinished finishedEvent =
        assertInstanceOf(SagaEvent.TestFinished.class, decodedEvent(1));
    assertEquals(startedEvent.testId(), finishedEvent.testId());
    assertEquals(SagaEvent.Outcome.PASSED, finishedEvent.outcome());
    assertEquals(1, finishedEvent.attempt());
    assertTrue(finishedEvent.durationMillis() >= 0);
    assertEquals(Optional.empty(), finishedEvent.failureType());
    assertEquals(Optional.empty(), finishedEvent.failureMessage());
    assertEquals(Optional.empty(), finishedEvent.failureSignature());
  }

  @Test
  void maps_a_failed_test_to_failure_type_message_and_signature() throws Exception {
    SagaTestListener listener = listenerAgainstStubServer(100);
    TestIdentifier test = testIdentifier("greets_rudely");
    AssertionError failure = new AssertionError("expected 2 but was 3");

    listener.executionStarted(test);
    listener.executionFinished(test, TestExecutionResult.failed(failure));
    awaitReceived(2);

    SagaEvent.TestFinished finishedEvent =
        assertInstanceOf(SagaEvent.TestFinished.class, decodedEvent(1));
    assertEquals(SagaEvent.Outcome.FAILED, finishedEvent.outcome());
    assertEquals(Optional.of("java.lang.AssertionError"), finishedEvent.failureType());
    assertEquals(Optional.of("expected 2 but was 3"), finishedEvent.failureMessage());
    StackTraceElement top = failure.getStackTrace()[0];
    String expectedFrame =
        top.getClassName() + "." + top.getMethodName() + "(" + top.getFileName() + ")";
    assertEquals(
        Optional.of(
            FailureSignature.of("java.lang.AssertionError", "expected 2 but was 3", expectedFrame)),
        finishedEvent.failureSignature());
  }

  @Test
  void maps_an_aborted_test_without_failure_fields() throws Exception {
    SagaTestListener listener = listenerAgainstStubServer(100);
    TestIdentifier test = testIdentifier("greets_assumption");

    listener.executionStarted(test);
    listener.executionFinished(
        test, TestExecutionResult.aborted(new RuntimeException("assumption failed")));
    awaitReceived(2);

    SagaEvent.TestFinished finishedEvent =
        assertInstanceOf(SagaEvent.TestFinished.class, decodedEvent(1));
    assertEquals(SagaEvent.Outcome.ABORTED, finishedEvent.outcome());
    assertEquals(Optional.empty(), finishedEvent.failureType());
    assertEquals(Optional.empty(), finishedEvent.failureSignature());
  }

  @Test
  void maps_an_execution_skip_to_a_skipped_finished_event() throws Exception {
    SagaTestListener listener = listenerAgainstStubServer(100);
    TestIdentifier test = testIdentifier("greets_disabled");

    listener.executionSkipped(test, "disabled");
    awaitReceived(1);

    SagaEvent.TestFinished finishedEvent =
        assertInstanceOf(SagaEvent.TestFinished.class, decodedEvent(0));
    assertEquals(SagaEvent.Outcome.SKIPPED, finishedEvent.outcome());
    assertEquals("test-module:FakeGreeterTest#greets_disabled", finishedEvent.testId());
    assertEquals(1, finishedEvent.attempt());
  }

  @Test
  void numbers_attempts_across_re_executions_of_the_same_test() throws Exception {
    SagaTestListener listener = listenerAgainstStubServer(100);
    TestIdentifier test = testIdentifier("greets_flakily");

    listener.executionStarted(test);
    listener.executionFinished(test, TestExecutionResult.failed(new AssertionError("flake")));
    listener.executionStarted(test);
    listener.executionFinished(test, TestExecutionResult.successful());
    awaitReceived(4);

    assertEquals(1, assertInstanceOf(SagaEvent.TestStarted.class, decodedEvent(0)).attempt());
    assertEquals(1, assertInstanceOf(SagaEvent.TestFinished.class, decodedEvent(1)).attempt());
    assertEquals(2, assertInstanceOf(SagaEvent.TestStarted.class, decodedEvent(2)).attempt());
    SagaEvent.TestFinished retry = assertInstanceOf(SagaEvent.TestFinished.class, decodedEvent(3));
    assertEquals(2, retry.attempt());
    assertEquals(SagaEvent.Outcome.PASSED, retry.outcome());
  }

  @Test
  void numbers_attempts_across_separate_listener_instances_for_the_same_test() throws Exception {
    // Surefire's own rerun-on-failure mechanism (rerunFailingTestsCount, driving
    // failOnFlakeCount) re-invokes the JUnit Platform Launcher for each retry batch within the
    // same forked JVM; a fresh Launcher rediscovers a brand-new SagaTestListener via
    // ServiceLoader. The flake ledger's own rule -- a FAILED attempt N immediately followed by a
    // PASSED attempt N+1 -- must still fire across that instance boundary, not just within one
    // listener's lifetime (see numbers_attempts_across_re_executions_of_the_same_test above).
    startServer(
        exchange -> {
          recordLines(exchange.getRequestBody().readAllBytes());
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    TestIdentifier test = testIdentifier("greets_flakily_across_relaunches");

    SagaTestListener first = new SagaTestListener(endpoint(), RUN_ID, MODULE, 100);
    first.executionStarted(test);
    first.executionFinished(test, TestExecutionResult.failed(new AssertionError("flake")));
    // first and second ship over independent virtual threads with no ordering guarantee between
    // them; draining first's pair before second starts is what makes the event order below
    // deterministic, not an artifact of the fix itself.
    awaitReceived(2);

    SagaTestListener second = new SagaTestListener(endpoint(), RUN_ID, MODULE, 100);
    second.executionStarted(test);
    second.executionFinished(test, TestExecutionResult.successful());
    awaitReceived(4);

    assertEquals(1, assertInstanceOf(SagaEvent.TestStarted.class, decodedEvent(0)).attempt());
    assertEquals(1, assertInstanceOf(SagaEvent.TestFinished.class, decodedEvent(1)).attempt());
    assertEquals(2, assertInstanceOf(SagaEvent.TestStarted.class, decodedEvent(2)).attempt());
    SagaEvent.TestFinished retry = assertInstanceOf(SagaEvent.TestFinished.class, decodedEvent(3));
    assertEquals(2, retry.attempt());
    assertEquals(SagaEvent.Outcome.PASSED, retry.outcome());
  }

  @Test
  void ignores_container_events() throws Exception {
    SagaTestListener listener = listenerAgainstStubServer(100);
    TestIdentifier container = containerIdentifier();
    TestIdentifier test = testIdentifier("greets_politely");

    listener.executionStarted(container);
    listener.executionStarted(test);
    listener.executionFinished(test, TestExecutionResult.successful());
    listener.executionFinished(container, TestExecutionResult.successful());
    listener.testPlanExecutionFinished(null);

    assertEquals(2, receivedLines.size());
  }

  @Test
  void swallows_everything_when_the_endpoint_is_unreachable() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    SagaTestListener listener =
        new SagaTestListener("http://localhost:" + closedPort, RUN_ID, MODULE, 100);
    TestIdentifier test = testIdentifier("greets_politely");

    listener.executionStarted(test);
    listener.executionFinished(test, TestExecutionResult.failed(new AssertionError("boom")));
    listener.testPlanExecutionFinished(null);
    // Reaching here without an exception is the assertion: shipping failures stay invisible.
  }

  @Test
  void drops_events_instead_of_blocking_when_the_queue_is_full() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    startServer(
        exchange -> {
          recordLines(exchange.getRequestBody().readAllBytes());
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    SagaTestListener listener = new SagaTestListener(endpoint(), RUN_ID, MODULE, 2);
    TestIdentifier test = testIdentifier("greets_slowly");

    long emitStart = System.nanoTime();
    for (int i = 0; i < 50; i++) {
      listener.executionStarted(test);
      listener.executionFinished(test, TestExecutionResult.successful());
    }
    long emitMillis = (System.nanoTime() - emitStart) / 1_000_000;
    release.countDown();
    listener.testPlanExecutionFinished(null);

    // Emitting 100 events into a stalled, capacity-2 pipeline must return immediately (offer,
    // never put) and shed most of them rather than ever back-pressuring the test thread.
    assertTrue(emitMillis < 2_000, "emitting took " + emitMillis + "ms");
    assertTrue(receivedLines.size() < 50, "expected drops, got " + receivedLines.size());
  }

  private SagaTestListener listenerAgainstStubServer(final int queueCapacity) throws IOException {
    startServer(
        exchange -> {
          recordLines(exchange.getRequestBody().readAllBytes());
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    return new SagaTestListener(endpoint(), RUN_ID, MODULE, queueCapacity);
  }

  private void startServer(final com.sun.net.httpserver.HttpHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/api/ingest", handler);
    server.start();
  }

  private String endpoint() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  private void recordLines(final byte[] body) {
    for (final String line : new String(body, StandardCharsets.UTF_8).split("\n")) {
      if (!line.isBlank()) {
        receivedLines.add(line);
      }
    }
  }

  private void awaitReceived(final int count) throws InterruptedException {
    awaitCondition(() -> receivedLines.size() >= count);
    assertEquals(count, receivedLines.size());
  }

  private static void awaitCondition(final BooleanSupplier condition) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        fail("condition not met within 5s");
      }
      Thread.sleep(20);
    }
  }

  private SagaEvent decodedEvent(final int index) {
    return SagaEventCodec.decode(receivedLines.get(index));
  }

  private static TestIdentifier testIdentifier(final String methodName) {
    return identifier(
        TestDescriptor.Type.TEST,
        MethodSource.from(FIXTURE_CLASS, methodName),
        FIXTURE_CLASS + "#" + methodName,
        methodName + "()");
  }

  private static TestIdentifier containerIdentifier() {
    return identifier(
        TestDescriptor.Type.CONTAINER,
        ClassSource.from(FIXTURE_CLASS),
        FIXTURE_CLASS,
        "FakeGreeterTest");
  }

  private static TestIdentifier identifier(
      final TestDescriptor.Type type,
      final TestSource source,
      final String uniqueIdSegment,
      final String displayName) {
    final TestDescriptor descriptor =
        new AbstractTestDescriptor(
            UniqueId.root("saga-fixture", uniqueIdSegment), displayName, source) {
          @Override
          public Type getType() {
            return type;
          }
        };
    return TestIdentifier.from(descriptor);
  }
}
