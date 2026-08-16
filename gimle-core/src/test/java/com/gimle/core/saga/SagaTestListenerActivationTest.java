package com.gimle.core.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestIdentifier;

/**
 * Exercises the system-property activation path of the no-arg constructor -- the one the JUnit
 * ServiceLoader registration uses in every test JVM. Dormancy is the safety property being pinned:
 * with no endpoint property, construction must yield a listener with no shipper thread or queue at
 * all. Locked because activation is decided at construction time from JVM-global properties.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class SagaTestListenerActivationTest {

  private final List<String> receivedLines = Collections.synchronizedList(new ArrayList<>());
  private HttpServer server;

  @AfterEach
  void cleanUp() {
    System.clearProperty(SagaTestListener.ENDPOINT_PROPERTY);
    System.clearProperty(SagaTestListener.RUN_ID_PROPERTY);
    System.clearProperty(SagaTestListener.MODULE_PROPERTY);
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void remains_dormant_when_the_endpoint_property_is_unset() {
    System.clearProperty(SagaTestListener.ENDPOINT_PROPERTY);

    SagaTestListener listener = new SagaTestListener();

    assertFalse(listener.active());
    // Every callback must be a no-op on a dormant listener, not just harmless.
    TestIdentifier test = testIdentifier("greets_politely");
    listener.executionStarted(test);
    listener.executionFinished(test, TestExecutionResult.successful());
    listener.executionSkipped(test, "disabled");
    listener.testPlanExecutionFinished(null);
  }

  @Test
  void remains_dormant_when_the_endpoint_property_is_blank() {
    System.setProperty(SagaTestListener.ENDPOINT_PROPERTY, "   ");

    assertFalse(new SagaTestListener().active());
  }

  @Test
  void activates_from_properties_and_ships_with_the_configured_run_id_and_module()
      throws Exception {
    startIngestServer();
    System.setProperty(
        SagaTestListener.ENDPOINT_PROPERTY, "http://localhost:" + server.getAddress().getPort());
    System.setProperty(SagaTestListener.RUN_ID_PROPERTY, "20260816T120000_abc1234");
    System.setProperty(SagaTestListener.MODULE_PROPERTY, "gimle-fixture");

    SagaTestListener listener = new SagaTestListener();
    assertTrue(listener.active());
    TestIdentifier test = testIdentifier("greets_politely");
    listener.executionStarted(test);
    listener.executionFinished(test, TestExecutionResult.successful());
    listener.testPlanExecutionFinished(null);

    awaitReceived(2);
    SagaEvent.TestStarted started =
        assertInstanceOf(SagaEvent.TestStarted.class, SagaEventCodec.decode(receivedLines.get(0)));
    assertEquals("20260816T120000_abc1234", started.runId());
    assertEquals("gimle-fixture:FakeGreeterTest#greets_politely", started.testId());
  }

  @Test
  void generates_a_local_run_id_when_none_is_configured() throws Exception {
    startIngestServer();
    System.setProperty(
        SagaTestListener.ENDPOINT_PROPERTY, "http://localhost:" + server.getAddress().getPort());
    System.setProperty(SagaTestListener.MODULE_PROPERTY, "gimle-fixture");

    SagaTestListener listener = new SagaTestListener();
    listener.executionSkipped(testIdentifier("greets_disabled"), "disabled");
    listener.testPlanExecutionFinished(null);

    awaitReceived(1);
    SagaEvent decoded = SagaEventCodec.decode(receivedLines.get(0));
    assertTrue(assertInstanceOf(SagaEvent.TestFinished.class, decoded).runId().endsWith("_local"));
  }

  private void startIngestServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/api/ingest",
        exchange -> {
          for (final String line :
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                  .split("\n")) {
            if (!line.isBlank()) {
              receivedLines.add(line);
            }
          }
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();
  }

  private void awaitReceived(final int count) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (receivedLines.size() < count) {
      if (System.nanoTime() > deadline) {
        fail("expected " + count + " lines, got " + receivedLines.size());
      }
      Thread.sleep(20);
    }
    assertEquals(count, receivedLines.size());
  }

  private static TestIdentifier testIdentifier(final String methodName) {
    final String className = "com.gimle.core.saga.FakeGreeterTest";
    final TestDescriptor descriptor =
        new AbstractTestDescriptor(
            UniqueId.root("saga-fixture", className + "#" + methodName),
            methodName + "()",
            MethodSource.from(className, methodName)) {
          @Override
          public Type getType() {
            return Type.TEST;
          }
        };
    return TestIdentifier.from(descriptor);
  }
}
