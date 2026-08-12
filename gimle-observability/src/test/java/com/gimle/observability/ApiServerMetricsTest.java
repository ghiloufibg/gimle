package com.gimle.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ApiServerMetricsTest {

  @Test
  void record_request_increments_count_and_records_latency() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ApiServerMetrics metrics = new ApiServerMetrics(registry);

    metrics.recordRequest("deployments", "GET", Duration.ofMillis(12), false);
    metrics.recordRequest("deployments", "GET", Duration.ofMillis(8), false);

    assertEquals(2.0, metrics.requestCount("deployments", "GET"));
    assertEquals(2, registry.find("gimle.controlplane.request.latency").timer().count());
  }

  @Test
  void request_latency_timer_publishes_percentiles_for_muninn_shipping() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ApiServerMetrics metrics = new ApiServerMetrics(registry);

    metrics.recordRequest("deployments", "GET", Duration.ofMillis(12), false);

    assertEquals(
        3,
        registry
            .find("gimle.controlplane.request.latency")
            .timer()
            .takeSnapshot()
            .percentileValues()
            .length,
        "expected p50/p95/p99 to be configured on the request-latency timer");
  }

  @Test
  void record_request_with_error_also_increments_error_counter() {
    ApiServerMetrics metrics = new ApiServerMetrics(new SimpleMeterRegistry());

    metrics.recordRequest("nodes", "PUT", Duration.ofMillis(5), true);

    assertEquals(1.0, metrics.errorCount("nodes", "PUT"));
  }

  @Test
  void error_counter_is_not_created_when_no_error_ever_recorded() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ApiServerMetrics metrics = new ApiServerMetrics(registry);

    metrics.recordRequest("deployments", "GET", Duration.ofMillis(5), false);

    Search search = registry.find("gimle.controlplane.request.errors");
    assertNull(search.counter());
  }

  @Test
  void request_and_error_count_are_zero_before_any_request_is_recorded() {
    ApiServerMetrics metrics = new ApiServerMetrics(new SimpleMeterRegistry());

    assertEquals(0.0, metrics.requestCount("deployments", "GET"));
    assertEquals(0.0, metrics.errorCount("deployments", "GET"));
  }

  @Test
  void different_endpoints_and_verbs_are_tagged_independently() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ApiServerMetrics metrics = new ApiServerMetrics(registry);

    metrics.recordRequest("deployments", "GET", Duration.ofMillis(1), false);
    metrics.recordRequest("nodes", "GET", Duration.ofMillis(1), false);

    assertEquals(1.0, metrics.requestCount("deployments", "GET"));
    assertEquals(1.0, metrics.requestCount("nodes", "GET"));
    assertEquals(0.0, metrics.requestCount("deployments", "PUT"));
  }
}
