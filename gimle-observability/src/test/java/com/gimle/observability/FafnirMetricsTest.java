package com.gimle.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FafnirMetricsTest {

  @Test
  void record_request_increments_count_and_records_latency() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FafnirMetrics metrics = new FafnirMetrics(registry);

    metrics.recordRequest("secrets-get", "GET", Duration.ofMillis(12), false);
    metrics.recordRequest("secrets-get", "GET", Duration.ofMillis(8), false);

    assertEquals(2.0, metrics.requestCount("secrets-get", "GET"));
    assertEquals(2, registry.find("gimle.fafnir.request.latency").timer().count());
  }

  @Test
  void request_latency_timer_publishes_percentiles_for_muninn_shipping() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FafnirMetrics metrics = new FafnirMetrics(registry);

    metrics.recordRequest("secrets-get", "GET", Duration.ofMillis(12), false);

    assertEquals(
        3,
        registry
            .find("gimle.fafnir.request.latency")
            .timer()
            .takeSnapshot()
            .percentileValues()
            .length,
        "expected p50/p95/p99 to be configured on the request-latency timer");
  }

  @Test
  void record_request_with_error_also_increments_error_counter() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FafnirMetrics metrics = new FafnirMetrics(registry);

    metrics.recordRequest("secrets-put", "PUT", Duration.ofMillis(5), true);

    assertEquals(1.0, metrics.errorCount("secrets-put", "PUT"));
  }

  @Test
  void error_counter_is_not_created_when_no_error_ever_recorded() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FafnirMetrics metrics = new FafnirMetrics(registry);

    metrics.recordRequest("secrets-get", "GET", Duration.ofMillis(5), false);

    Search search = registry.find("gimle.fafnir.request.errors");
    assertNull(search.counter());
  }

  @Test
  void request_and_error_count_are_zero_before_any_request_is_recorded() {
    FafnirMetrics metrics = new FafnirMetrics(new SimpleMeterRegistry());

    assertEquals(0.0, metrics.requestCount("secrets-get", "GET"));
    assertEquals(0.0, metrics.errorCount("secrets-get", "GET"));
  }

  @Test
  void different_endpoints_and_verbs_are_tagged_independently() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FafnirMetrics metrics = new FafnirMetrics(registry);

    metrics.recordRequest("secrets-get", "GET", Duration.ofMillis(1), false);
    metrics.recordRequest("secrets-put", "PUT", Duration.ofMillis(1), false);

    assertEquals(1.0, metrics.requestCount("secrets-get", "GET"));
    assertEquals(1.0, metrics.requestCount("secrets-put", "PUT"));
    assertEquals(0.0, metrics.requestCount("secrets-get", "PUT"));
  }

  @Test
  void authz_failures_are_recorded_and_tagged_by_verb_only() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FafnirMetrics metrics = new FafnirMetrics(registry);

    metrics.recordAuthzFailure("READ");
    metrics.recordAuthzFailure("READ");
    metrics.recordAuthzFailure("WRITE");

    assertEquals(2.0, metrics.authzFailureCount("READ"));
    assertEquals(1.0, metrics.authzFailureCount("WRITE"));
  }

  @Test
  void authz_failure_count_is_zero_before_any_failure_is_recorded() {
    FafnirMetrics metrics = new FafnirMetrics(new SimpleMeterRegistry());

    assertEquals(0.0, metrics.authzFailureCount("READ"));
  }
}
