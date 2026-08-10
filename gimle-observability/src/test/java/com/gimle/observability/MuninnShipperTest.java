package com.gimle.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Against a stub ingest {@link HttpServer} (no real Muninn process needed) -- matches {@code
 * AgentLogServer}/{@code LogFileReader}'s own existing test style for this class of surface.
 */
class MuninnShipperTest {

  private HttpServer stub;
  private MuninnShipper shipper;

  @AfterEach
  void tearDown() {
    if (shipper != null) {
      shipper.close();
    }
    if (stub != null) {
      stub.stop(0);
    }
  }

  private HttpServer startStub(java.util.function.Function<String, Integer> statusForBatch)
      throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/ingest",
        exchange -> {
          try (InputStream in = exchange.getRequestBody()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int status = statusForBatch.apply(body);
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
          } finally {
            exchange.close();
          }
        });
    server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    return server;
  }

  @Test
  @Timeout(10)
  void a_successful_tick_ships_new_log_lines_and_advances_the_cursor(@TempDir Path tempDir)
      throws Exception {
    List<String> receivedBodies = new CopyOnWriteArrayList<>();
    stub =
        startStub(
            body -> {
              receivedBodies.add(body);
              return 200;
            });

    Path logFile = tempDir.resolve("app.log");
    Files.writeString(logFile, structuredLine("2026-08-10T10:00:00Z", "one") + "\n");

    shipper =
        new MuninnShipper(
            "127.0.0.1:" + stub.getAddress().getPort(), "/ingest", Duration.ofMillis(50));
    shipper.startShippingLogFile(logFile, 1);

    awaitUntil(() -> !receivedBodies.isEmpty(), Duration.ofSeconds(5));
    assertTrue(receivedBodies.get(0).contains("\"message\":\"one\""));

    // A second tick with no new lines ships nothing further -- proves the cursor advanced past
    // the line already shipped, not that the shipper simply re-sends everything every tick.
    int countAfterFirstShip = receivedBodies.size();
    Thread.sleep(200);
    assertEquals(countAfterFirstShip, receivedBodies.size());
  }

  @Test
  @Timeout(10)
  void a_failed_tick_does_not_advance_the_cursor_and_retries_next_tick(@TempDir Path tempDir)
      throws Exception {
    AtomicBoolean succeedNext = new AtomicBoolean(false);
    List<String> receivedBodies = new CopyOnWriteArrayList<>();
    stub =
        startStub(
            body -> {
              receivedBodies.add(body);
              return succeedNext.get() ? 200 : 500;
            });

    Path logFile = tempDir.resolve("app.log");
    Files.writeString(logFile, structuredLine("2026-08-10T10:00:00Z", "retry-me") + "\n");

    shipper =
        new MuninnShipper(
            "127.0.0.1:" + stub.getAddress().getPort(), "/ingest", Duration.ofMillis(50));
    shipper.startShippingLogFile(logFile, 1);

    // At least two failed attempts observed, each re-shipping the same still-unshipped line.
    awaitUntil(() -> receivedBodies.size() >= 2, Duration.ofSeconds(5));
    assertTrue(receivedBodies.stream().allMatch(b -> b.contains("retry-me")));

    // Now let it succeed -- the shipping thread must still be alive and ticking after prior
    // failures (no uncaught exception ever escaped it).
    succeedNext.set(true);
    int countBeforeSuccess = receivedBodies.size();
    awaitUntil(() -> receivedBodies.size() > countBeforeSuccess, Duration.ofSeconds(5));
  }

  @Test
  @Timeout(10)
  void a_metrics_tick_ships_one_ndjson_line_per_meter() throws Exception {
    List<String> receivedBodies = new CopyOnWriteArrayList<>();
    stub =
        startStub(
            body -> {
              receivedBodies.add(body);
              return 200;
            });

    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Counter.builder("gimle.test.requests").register(registry).increment(42);
    Timer.builder("gimle.test.latency").register(registry).record(Duration.ofMillis(5));

    shipper =
        new MuninnShipper(
            "127.0.0.1:" + stub.getAddress().getPort(), "/ingest", Duration.ofMillis(50));
    shipper.startShippingMetrics(registry);

    awaitUntil(() -> !receivedBodies.isEmpty(), Duration.ofSeconds(5));
    String body = receivedBodies.get(0);
    long lineCount = body.lines().filter(l -> !l.isBlank()).count();
    assertEquals(2, lineCount, "expected one line per meter (counter + timer)");
    assertTrue(body.contains("gimle.test.requests"));
    assertTrue(body.contains("gimle.test.latency"));
  }

  @Test
  @Timeout(10)
  void ship_trace_batch_is_a_one_shot_post_with_no_periodic_ticking() throws Exception {
    List<String> receivedBodies = new CopyOnWriteArrayList<>();
    CountDownLatch received = new CountDownLatch(1);
    stub =
        startStub(
            body -> {
              receivedBodies.add(body);
              received.countDown();
              return 200;
            });

    shipper =
        new MuninnShipper(
            "127.0.0.1:" + stub.getAddress().getPort(), "/ingest", Duration.ofSeconds(60));
    shipper.shipTraceBatch(
        List.of(Map.of("timestamp", "2026-08-10T10:00:00Z", "traceId", "abc123")));

    assertTrue(received.await(5, TimeUnit.SECONDS));
    assertEquals(1, receivedBodies.size());
    assertTrue(receivedBodies.get(0).contains("abc123"));

    // No ticker was ever started -- give it a moment and confirm no second POST arrives.
    Thread.sleep(200);
    assertEquals(1, receivedBodies.size());
  }

  /**
   * {@code LogFileReader.parseLine} only treats a line as structured (preserving it verbatim) if it
   * has both {@code timestamp} and {@code level} fields -- otherwise it's wrapped as a raw {@code
   * SYSTEM} capture with a synthetic timestamp, discarding the original content entirely. Every
   * log-shipping test line here needs both fields to actually exercise cursor advancement against a
   * stable, caller-chosen timestamp.
   */
  private static String structuredLine(String timestamp, String message) {
    return Json.write(Map.of("timestamp", timestamp, "level", "INFO", "message", message));
  }

  private static void awaitUntil(java.util.function.BooleanSupplier condition, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within " + timeout);
      }
      Thread.sleep(20);
    }
  }
}
