package com.gimle.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MeterSnapshotCodecTest {

  @Test
  void an_empty_registry_produces_an_empty_string() {
    assertEquals("", MeterSnapshotCodec.toNdjson(new SimpleMeterRegistry()));
  }

  @Test
  void one_line_per_meter_with_the_meters_own_name() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Counter.builder("gimle.test.requests").register(registry).increment(42);
    Timer.builder("gimle.test.latency").register(registry).record(Duration.ofMillis(5));

    String body = MeterSnapshotCodec.toNdjson(registry);

    long lineCount = body.lines().filter(l -> !l.isBlank()).count();
    assertEquals(2, lineCount, "expected one line per meter (counter + timer)");
    assertTrue(body.contains("gimle.test.requests"));
    assertTrue(body.contains("gimle.test.latency"));
  }

  @Test
  void a_timer_with_percentiles_ships_a_percentiles_map() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Timer.builder("gimle.test.latency.p99")
        .publishPercentiles(0.5, 0.99)
        .register(registry)
        .record(Duration.ofMillis(5));

    String body = MeterSnapshotCodec.toNdjson(registry);

    assertTrue(body.contains("\"percentiles\""));
    assertTrue(body.contains("\"0.5\""));
    assertTrue(body.contains("\"0.99\""));
  }

  @Test
  void a_timer_without_percentiles_omits_the_percentiles_key() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Timer.builder("gimle.test.latency.plain").register(registry).record(Duration.ofMillis(5));

    String body = MeterSnapshotCodec.toNdjson(registry);

    assertTrue(body.contains("gimle.test.latency.plain"));
    assertTrue(
        body.lines().noneMatch(line -> line.contains("\"percentiles\"")),
        "a percentile-less timer must not gain a percentiles key");
  }
}
