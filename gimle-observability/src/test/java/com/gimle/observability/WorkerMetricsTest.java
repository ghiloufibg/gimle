package com.gimle.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkerMetricsTest {

  private static final ModuleId ID =
      new ModuleId("com.gimle.example.orders", Version.parse("1.0.0"));

  @Test
  void record_request_increments_count_and_records_latency() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    WorkerMetrics metrics = new WorkerMetrics(registry);

    metrics.recordRequest(ID, Duration.ofMillis(42), false);
    metrics.recordRequest(ID, Duration.ofMillis(58), false);

    assertEquals(
        2.0,
        registry.find("gimle.module.request.count").tag("module", ID.name()).counter().count());
    assertEquals(2, registry.find("gimle.module.request.latency").timer().count());
  }

  @Test
  void record_request_with_error_also_increments_error_counter() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    WorkerMetrics metrics = new WorkerMetrics(registry);

    metrics.recordRequest(ID, Duration.ofMillis(10), true);

    assertEquals(1.0, registry.find("gimle.module.request.errors").counter().count());
  }

  @Test
  void error_counter_is_not_created_when_no_error_ever_recorded() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    WorkerMetrics metrics = new WorkerMetrics(registry);

    metrics.recordRequest(ID, Duration.ofMillis(10), false);

    Search search = registry.find("gimle.module.request.errors");
    assertNull(search.counter());
  }

  @Test
  void thread_count_gauge_reflects_the_latest_recorded_value_not_the_first() {
    // Regression test: MeterRegistry#gauge(name, tags, boxedNumber) would silently freeze at
    // whatever value was passed the first time, since the registry only re-reads the same
    // (immutable) boxed instance thereafter -- WorkerMetrics must use a mutable holder instead.
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    WorkerMetrics metrics = new WorkerMetrics(registry);

    metrics.recordThreadCount(ID, 3);
    Gauge gauge = registry.find("gimle.module.threads").tag("module", ID.name()).gauge();
    assertNotNull(gauge);
    assertEquals(3.0, gauge.value());

    metrics.recordThreadCount(ID, 7);
    assertEquals(
        7.0, gauge.value(), "gauge must reflect the latest value, not the first one recorded");
  }

  @Test
  void metaspace_gauge_reflects_the_latest_recorded_value_not_the_first() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    WorkerMetrics metrics = new WorkerMetrics(registry);

    metrics.recordMetaspaceBytes(ID, 1_000_000L);
    metrics.recordMetaspaceBytes(ID, 2_500_000L);

    Gauge gauge = registry.find("gimle.module.metaspace.bytes").tag("module", ID.name()).gauge();
    assertNotNull(gauge);
    assertEquals(2_500_000.0, gauge.value());
  }

  @Test
  void request_count_reflects_the_cumulative_total() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    WorkerMetrics metrics = new WorkerMetrics(registry);

    metrics.recordRequest(ID, Duration.ofMillis(10), false);
    metrics.recordRequest(ID, Duration.ofMillis(10), false);
    metrics.recordRequest(ID, Duration.ofMillis(10), true);

    assertEquals(3.0, metrics.requestCount(ID));
    assertEquals(1.0, metrics.errorCount(ID));
  }

  @Test
  void request_and_error_count_are_zero_before_any_request_is_recorded() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    WorkerMetrics metrics = new WorkerMetrics(registry);

    assertEquals(0.0, metrics.requestCount(ID));
    assertEquals(0.0, metrics.errorCount(ID));
  }

  @Test
  void metrics_for_different_modules_are_tagged_independently() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    WorkerMetrics metrics = new WorkerMetrics(registry);
    ModuleId other = new ModuleId("com.gimle.example.catalog", Version.parse("1.0.0"));

    metrics.recordThreadCount(ID, 3);
    metrics.recordThreadCount(other, 9);

    assertEquals(
        3.0, registry.find("gimle.module.threads").tag("module", ID.name()).gauge().value());
    assertEquals(
        9.0, registry.find("gimle.module.threads").tag("module", other.name()).gauge().value());
  }
}
