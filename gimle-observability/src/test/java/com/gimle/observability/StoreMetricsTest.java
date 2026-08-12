package com.gimle.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class StoreMetricsTest {

  @Test
  void record_request_increments_count_and_records_latency() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    StoreMetrics metrics = new StoreMetrics(registry);

    metrics.recordRequest("GetTenant", Duration.ofMillis(12), false);
    metrics.recordRequest("GetTenant", Duration.ofMillis(8), false);

    assertEquals(2.0, metrics.requestCount("GetTenant"));
    assertEquals(2, registry.find("gimle.store.request.latency").timer().count());
  }

  @Test
  void request_latency_timer_publishes_percentiles_for_muninn_shipping() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    StoreMetrics metrics = new StoreMetrics(registry);

    metrics.recordRequest("GetTenant", Duration.ofMillis(12), false);

    assertEquals(
        3,
        registry
            .find("gimle.store.request.latency")
            .timer()
            .takeSnapshot()
            .percentileValues()
            .length,
        "expected p50/p95/p99 to be configured on the request-latency timer");
  }

  @Test
  void record_request_with_error_also_increments_error_counter() {
    StoreMetrics metrics = new StoreMetrics(new SimpleMeterRegistry());

    metrics.recordRequest("Propose", Duration.ofMillis(5), true);

    assertEquals(1.0, metrics.errorCount("Propose"));
  }

  @Test
  void error_counter_is_not_created_when_no_error_ever_recorded() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    StoreMetrics metrics = new StoreMetrics(registry);

    metrics.recordRequest("GetTenant", Duration.ofMillis(5), false);

    Search search = registry.find("gimle.store.request.errors");
    assertNull(search.counter());
  }

  @Test
  void request_and_error_count_are_zero_before_any_request_is_recorded() {
    StoreMetrics metrics = new StoreMetrics(new SimpleMeterRegistry());

    assertEquals(0.0, metrics.requestCount("GetTenant"));
    assertEquals(0.0, metrics.errorCount("GetTenant"));
  }

  @Test
  void different_rpc_kinds_are_tagged_independently() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    StoreMetrics metrics = new StoreMetrics(registry);

    metrics.recordRequest("GetTenant", Duration.ofMillis(1), false);
    metrics.recordRequest("Propose", Duration.ofMillis(1), false);

    assertEquals(1.0, metrics.requestCount("GetTenant"));
    assertEquals(1.0, metrics.requestCount("Propose"));
    assertEquals(0.0, metrics.requestCount("ListDeployments"));
  }
}
