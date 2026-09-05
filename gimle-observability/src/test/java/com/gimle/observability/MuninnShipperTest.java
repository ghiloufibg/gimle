package com.gimle.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
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

  private HttpServer startStub(Function<String, Integer> statusForBatch) throws IOException {
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
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
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
        List.of("127.0.0.1:" + stub.getAddress().getPort()),
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

  /**
   * The regression case for the cursor's exact-duplicate-timestamp loss: {@code readAfter}'s own
   * comparison is strictly "after," so a second line landing at the identical instant as the
   * first-shipped one used to be excluded on every subsequent tick, forever, with no restart or
   * error involved. A second line sharing {@code "one"}'s exact timestamp is appended between two
   * ticks here -- before the fix this test fails with only one body ever received.
   */
  @Test
  @Timeout(10)
  void two_lines_sharing_the_exact_same_timestamp_across_ticks_are_both_shipped(
      @TempDir Path tempDir, TestClock clock) throws Exception {
    List<String> receivedBodies = new CopyOnWriteArrayList<>();
    stub =
        startStub(
            body -> {
              receivedBodies.add(body);
              return 200;
            });

    Path logFile = tempDir.resolve("app.log");
    String sharedTimestamp = "2026-08-10T10:00:00.500Z";
    Files.writeString(logFile, structuredLine(sharedTimestamp, "one") + "\n");

    TestScheduler scheduler = new TestScheduler(clock);
    shipper = shipperOn(scheduler, SHIP_INTERVAL);
    shipper.startShippingLogFile(logFile, 1);

    scheduler.runUntilIdle();
    assertEquals(1, receivedBodies.size());
    assertTrue(receivedBodies.get(0).contains("\"message\":\"one\""));

    // A sibling line lands at the identical instant on a later tick -- bursty logging can produce
    // this at any timestamp resolution, not just as a contrived edge case.
    Files.writeString(
        logFile, structuredLine(sharedTimestamp, "two") + "\n", StandardOpenOption.APPEND);
    scheduler.advance(SHIP_INTERVAL);
    assertEquals(2, receivedBodies.size(), "the same-instant sibling must still get shipped");
    assertTrue(receivedBodies.get(1).contains("\"message\":\"two\""));
    assertTrue(
        !receivedBodies.get(1).contains("\"message\":\"one\""),
        "the already-shipped sibling must not be re-sent");

    // And the cursor is now genuinely caught up -- a further tick with nothing new ships nothing.
    scheduler.advance(SHIP_INTERVAL.multipliedBy(3));
    assertEquals(2, receivedBodies.size());
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
  void a_batch_ships_to_every_configured_endpoint(@TempDir Path tempDir, TestClock clock)
      throws Exception {
    List<String> firstReceived = new CopyOnWriteArrayList<>();
    List<String> secondReceived = new CopyOnWriteArrayList<>();
    stub =
        startStub(
            body -> {
              firstReceived.add(body);
              return 200;
            });
    HttpServer secondStub =
        startStub(
            body -> {
              secondReceived.add(body);
              return 200;
            });
    try {
      Path logFile = tempDir.resolve("app.log");
      Files.writeString(logFile, structuredLine("2026-08-10T10:00:00Z", "fan-out") + "\n");

      TestScheduler scheduler = new TestScheduler(clock);
      shipper =
          new MuninnShipper(
              List.of(
                  "127.0.0.1:" + stub.getAddress().getPort(),
                  "127.0.0.1:" + secondStub.getAddress().getPort()),
              "/ingest",
              SHIP_INTERVAL,
              Optional.empty(),
              scheduler);
      shipper.startShippingLogFile(logFile, 1);

      scheduler.runUntilIdle();
      assertEquals(1, firstReceived.size());
      assertEquals(1, secondReceived.size());
      assertTrue(firstReceived.get(0).contains("\"message\":\"fan-out\""));
      assertTrue(secondReceived.get(0).contains("\"message\":\"fan-out\""));
    } finally {
      secondStub.stop(0);
    }
  }

  @Test
  @Timeout(10)
  void a_batch_still_lands_on_reachable_endpoints_when_one_configured_endpoint_is_down(
      @TempDir Path tempDir, TestClock clock) throws Exception {
    List<String> receivedBodies = new CopyOnWriteArrayList<>();
    stub =
        startStub(
            body -> {
              receivedBodies.add(body);
              return 200;
            });

    Path logFile = tempDir.resolve("app.log");
    Files.writeString(logFile, structuredLine("2026-08-10T10:00:00Z", "survives") + "\n");

    TestScheduler scheduler = new TestScheduler(clock);
    shipper =
        new MuninnShipper(
            // 127.0.0.1:1 is a privileged, never-listening port -- connection refused every
            // time, a deterministic stand-in for "this replica is down" (the same trick
            // MuninnSpanExporterTest already uses for its own unreachable-endpoint case).
            List.of("127.0.0.1:1", "127.0.0.1:" + stub.getAddress().getPort()),
            "/ingest",
            SHIP_INTERVAL,
            Optional.empty(),
            scheduler);
    shipper.startShippingLogFile(logFile, 1);

    scheduler.runUntilIdle();
    assertEquals(1, receivedBodies.size());
    assertTrue(receivedBodies.get(0).contains("\"message\":\"survives\""));

    // The cursor advanced despite the down endpoint -- a further tick with no new lines re-ships
    // nothing, proving the batch was not silently retried forever because of the one failure.
    scheduler.advance(SHIP_INTERVAL.multipliedBy(3));
    assertEquals(1, receivedBodies.size());
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
    // Backward-compat pin for the change in MeterSnapshotCodec#meterToJsonLine: a Timer that never
    // opted into publishPercentiles(...) must ship exactly the same line shape as before this
    // feature.
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

  /**
   * The whole point of persisting the cursor: Muninn's store is append-only, so a shipper that
   * restarts from "nothing shipped yet" adds another copy of every retained line each time, and a
   * line read back comes back once per restart since it was written.
   */
  @Test
  @Timeout(10)
  void a_restarted_shipper_resumes_where_the_previous_one_stopped(
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

    TestScheduler firstScheduler = new TestScheduler(clock);
    shipper = shipperOn(firstScheduler, SHIP_INTERVAL);
    shipper.startShippingLogFile(logFile, 1);
    firstScheduler.runUntilIdle();
    assertEquals(1, receivedBodies.size());
    shipper.close();

    Files.writeString(
        logFile,
        structuredLine("2026-08-10T10:00:05Z", "two") + "\n",
        java.nio.file.StandardOpenOption.APPEND);

    TestScheduler secondScheduler = new TestScheduler(clock);
    shipper = shipperOn(secondScheduler, SHIP_INTERVAL);
    shipper.startShippingLogFile(logFile, 1);
    secondScheduler.runUntilIdle();

    assertEquals(2, receivedBodies.size());
    assertTrue(receivedBodies.get(1).contains("\"message\":\"two\""), receivedBodies.get(1));
    assertFalse(receivedBodies.get(1).contains("\"message\":\"one\""), receivedBodies.get(1));
  }
}
