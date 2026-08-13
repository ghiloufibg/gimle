package com.gimle.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import com.gimle.core.time.TestClock;
import com.gimle.core.time.TestScheduler;
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
import java.util.Optional;
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

  /**
   * The real interval every process ships on ({@code MUNINN_SHIP_INTERVAL} in {@code AgentMain} and
   * friends), rather than the 50ms stand-in these tests used to need in order to outrun a ticker.
   * On a {@link TestScheduler} a five-second interval costs exactly as little as a 50ms one.
   */
  private static final Duration SHIP_INTERVAL = Duration.ofSeconds(5);

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

  /**
   * A shipper whose ticks fire only when the test says so. The HTTP POST inside a tick is
   * synchronous, so once {@link TestScheduler#advance} returns, every ship that tick was going to
   * do has already happened -- which is what lets the assertions below count POSTs exactly instead
   * of polling for "at least" some number.
   */
  private MuninnShipper shipperOn(TestScheduler scheduler, Duration tickInterval) {
    return new MuninnShipper(
        "127.0.0.1:" + stub.getAddress().getPort(),
        "/ingest",
        tickInterval,
        Optional.empty(),
        scheduler);
  }

  @Test
  @Timeout(10)
  void a_successful_tick_ships_new_log_lines_and_advances_the_cursor(
      @TempDir Path tempDir, TestClock clock) throws Exception {
    List<String> receivedBodies = new CopyOnWriteArrayList<>();
    stub =
        startStub(
            body -> {
              receivedBodies.add(body);
              return 200;
            });

    Path logFile = tempDir.resolve("app.log");
    Files.writeString(logFile, structuredLine("2026-08-10T10:00:00Z", "one") + "\n");

    // The real 5-second production ship interval, not a 50ms stand-in the test has to outrun.
    TestScheduler scheduler = new TestScheduler(clock);
    shipper = shipperOn(scheduler, SHIP_INTERVAL);
    shipper.startShippingLogFile(logFile, 1);

    scheduler.runUntilIdle(); // the tick scheduled at zero delay
    assertEquals(1, receivedBodies.size());
    assertTrue(receivedBodies.get(0).contains("\"message\":\"one\""));

    // A second tick with no new lines ships nothing further -- proves the cursor advanced past the
    // line already shipped, not that the shipper simply re-sends everything every tick. Exactly
    // one POST total, rather than "no more had arrived by the time we looked".
    scheduler.advance(SHIP_INTERVAL.multipliedBy(3));
    assertEquals(1, receivedBodies.size());
  }

  @Test
  @Timeout(10)
  void a_failed_tick_does_not_advance_the_cursor_and_retries_next_tick(
      @TempDir Path tempDir, TestClock clock) throws Exception {
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

    TestScheduler scheduler = new TestScheduler(clock);
    shipper = shipperOn(scheduler, SHIP_INTERVAL);
    shipper.startShippingLogFile(logFile, 1);

    // Exactly two failed attempts, each re-shipping the same still-unshipped line -- an exact
    // count, where polling could only ever assert "at least two by now".
    scheduler.runUntilIdle();
    scheduler.advance(SHIP_INTERVAL);
    assertEquals(2, receivedBodies.size());
    assertTrue(receivedBodies.stream().allMatch(b -> b.contains("retry-me")));

    // Now let it succeed -- the ticker must still be scheduled after prior failures (no uncaught
    // exception ever escaped the tick body and cancelled the periodic task).
    succeedNext.set(true);
    scheduler.advance(SHIP_INTERVAL);
    assertEquals(3, receivedBodies.size());

    // And having succeeded, the cursor has advanced: further ticks ship nothing at all.
    scheduler.advance(SHIP_INTERVAL.multipliedBy(2));
    assertEquals(3, receivedBodies.size());
  }

  @Test
  @Timeout(10)
  void a_metrics_tick_ships_one_ndjson_line_per_meter(TestClock clock) throws Exception {
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

    TestScheduler scheduler = new TestScheduler(clock);
    shipper = shipperOn(scheduler, SHIP_INTERVAL);
    shipper.startShippingMetrics(registry);

    scheduler.runUntilIdle();
    assertEquals(1, receivedBodies.size());
    String body = receivedBodies.get(0);
    long lineCount = body.lines().filter(l -> !l.isBlank()).count();
    assertEquals(2, lineCount, "expected one line per meter (counter + timer)");
    assertTrue(body.contains("gimle.test.requests"));
    assertTrue(body.contains("gimle.test.latency"));
  }

  @Test
  @Timeout(10)
  void a_timer_built_with_percentiles_ships_a_percentiles_map_in_its_ndjson_line(TestClock clock)
      throws Exception {
    List<String> receivedBodies = new CopyOnWriteArrayList<>();
    stub =
        startStub(
            body -> {
              receivedBodies.add(body);
              return 200;
            });

    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Timer timer =
        Timer.builder("gimle.test.latency.p99")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
    timer.record(Duration.ofMillis(5));
    timer.record(Duration.ofMillis(15));

    TestScheduler scheduler = new TestScheduler(clock);
    shipper = shipperOn(scheduler, SHIP_INTERVAL);
    shipper.startShippingMetrics(registry);

    scheduler.runUntilIdle();
    assertEquals(1, receivedBodies.size());
    String body = receivedBodies.get(0);
    assertTrue(body.contains("\"percentiles\""));
    assertTrue(body.contains("\"0.5\""));
    assertTrue(body.contains("\"0.95\""));
    assertTrue(body.contains("\"0.99\""));
  }

  @Test
  @Timeout(10)
  void a_timer_built_without_percentiles_ships_no_percentiles_key(TestClock clock)
      throws Exception {
    List<String> receivedBodies = new CopyOnWriteArrayList<>();
    stub =
        startStub(
            body -> {
              receivedBodies.add(body);
              return 200;
            });

    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Timer.builder("gimle.test.latency.plain").register(registry).record(Duration.ofMillis(5));

    TestScheduler scheduler = new TestScheduler(clock);
    shipper = shipperOn(scheduler, SHIP_INTERVAL);
    shipper.startShippingMetrics(registry);

    scheduler.runUntilIdle();
    // Backward-compat pin for the change in MuninnShipper#meterToJsonLine: a Timer that never opted
    // into publishPercentiles(...) must ship exactly the same line shape as before this feature.
    assertEquals(1, receivedBodies.size());
    assertTrue(receivedBodies.get(0).contains("gimle.test.latency.plain"));
    assertTrue(
        receivedBodies.stream().noneMatch(body -> body.contains("\"percentiles\"")),
        "a percentile-less timer must not gain a percentiles key");
  }

  @Test
  @Timeout(10)
  void ship_trace_batch_is_a_one_shot_post_with_no_periodic_ticking(TestClock clock)
      throws Exception {
    List<String> receivedBodies = new CopyOnWriteArrayList<>();
    CountDownLatch received = new CountDownLatch(1);
    stub =
        startStub(
            body -> {
              receivedBodies.add(body);
              received.countDown();
              return 200;
            });

    TestScheduler scheduler = new TestScheduler(clock);
    shipper = shipperOn(scheduler, SHIP_INTERVAL);
    shipper.shipTraceBatch(
        List.of(Map.of("timestamp", "2026-08-10T10:00:00Z", "traceId", "abc123")));

    assertTrue(received.await(5, TimeUnit.SECONDS));
    assertEquals(1, receivedBodies.size());
    assertTrue(receivedBodies.get(0).contains("abc123"));

    // No ticker was ever started, asserted directly rather than by waiting to see whether one
    // fires: nothing is scheduled at all, so no amount of elapsed time could produce a second POST.
    assertEquals(0, scheduler.pendingTaskCount());
    assertEquals(0, scheduler.advance(SHIP_INTERVAL.multipliedBy(10)));
    assertEquals(1, receivedBodies.size());
  }

  @Test
  @Timeout(10)
  void ship_prepared_batch_posts_the_given_body_verbatim_with_no_periodic_ticking(TestClock clock)
      throws Exception {
    List<String> receivedBodies = new CopyOnWriteArrayList<>();
    CountDownLatch received = new CountDownLatch(1);
    stub =
        startStub(
            body -> {
              receivedBodies.add(body);
              received.countDown();
              return 200;
            });

    TestScheduler scheduler = new TestScheduler(clock);
    shipper = shipperOn(scheduler, SHIP_INTERVAL);
    shipper.shipPreparedBatch("{\"name\":\"gimle.relayed\"}\n");

    assertTrue(received.await(5, TimeUnit.SECONDS));
    assertEquals(1, receivedBodies.size());
    assertTrue(receivedBodies.get(0).contains("gimle.relayed"));

    // No re-serialization, no ticker started -- the body reaches Muninn exactly as given.
    assertEquals(0, scheduler.pendingTaskCount());
    assertEquals(0, scheduler.advance(SHIP_INTERVAL.multipliedBy(10)));
    assertEquals(1, receivedBodies.size());
  }

  @Test
  void ship_prepared_batch_is_a_noop_for_an_empty_body(TestClock clock) throws Exception {
    List<String> receivedBodies = new CopyOnWriteArrayList<>();
    stub =
        startStub(
            body -> {
              receivedBodies.add(body);
              return 200;
            });

    TestScheduler scheduler = new TestScheduler(clock);
    shipper = shipperOn(scheduler, SHIP_INTERVAL);
    shipper.shipPreparedBatch("");

    Thread.sleep(100);
    assertTrue(receivedBodies.isEmpty());
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
}
