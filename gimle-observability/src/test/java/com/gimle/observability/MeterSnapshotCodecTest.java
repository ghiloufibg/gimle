package com.gimle.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * {@link MuninnShipperTest} already exercises {@link MeterSnapshotCodec#toLine} indirectly, end to
 * end, through {@code MuninnShipper#tickMetrics}'s unchanged List-of-lines path (design doc §6c:
 * zero behavior change there). This covers the other consumer directly: {@link
 * MeterSnapshotCodec#toNdjson}, the joined-{@code String} shape a worker JVM builds and hands to
 * its agent as a {@code ControlMessage.MetricsSnapshot} payload (design doc §6c/§6d), which nothing
 * else exercises.
 */
class MeterSnapshotCodecTest {

  @Test
  void an_empty_registry_produces_an_empty_string() {
    assertEquals("", MeterSnapshotCodec.toNdjson(new SimpleMeterRegistry()));
  }

  @Test
  void one_ndjson_line_per_meter_newline_terminated() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Counter.builder("gimle.test.a").register(registry).increment();
    Counter.builder("gimle.test.b").register(registry).increment();

    String ndjson = MeterSnapshotCodec.toNdjson(registry);

    long lineCount = ndjson.lines().filter(l -> !l.isBlank()).count();
    assertEquals(2, lineCount);
    assertTrue(ndjson.endsWith("\n"));
    assertTrue(ndjson.contains("gimle.test.a"));
    assertTrue(ndjson.contains("gimle.test.b"));
  }
}
