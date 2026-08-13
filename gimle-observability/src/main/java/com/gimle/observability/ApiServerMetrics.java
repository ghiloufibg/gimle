package com.gimle.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;

/**
 * Per-endpoint Micrometer wiring for {@code gimle-controlplane}'s {@code ApiServer} -- the
 * identical {@link MeterRegistry}/tag/counter/timer shape {@link FafnirMetrics} already establishes
 * for {@code gimle-fafnir}, kept as its own class rather than shared with it because the two track
 * different callers' endpoint namespaces and neither's tag set means anything on the other's.
 * Defaults to an in-memory {@link SimpleMeterRegistry}, matching every other metrics wrapper in
 * this module.
 */
public final class ApiServerMetrics {

  private final MeterRegistry registry;
  private final TaggedRequestMetrics metrics;

  public ApiServerMetrics() {
    this(new SimpleMeterRegistry());
  }

  public ApiServerMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.metrics =
        new TaggedRequestMetrics(
            registry,
            "gimle.controlplane.request.latency",
            "gimle.controlplane.request.count",
            "gimle.controlplane.request.errors",
            true);
  }

  public MeterRegistry registry() {
    return registry;
  }

  public void recordRequest(String endpoint, String verb, Duration latency, boolean error) {
    metrics.record(tagsFor(endpoint, verb), latency, error);
  }

  /** Same "cumulative total, zero if never recorded" contract as {@link FafnirMetrics}. */
  public double requestCount(String endpoint, String verb) {
    return metrics.count(tagsFor(endpoint, verb));
  }

  public double errorCount(String endpoint, String verb) {
    return metrics.errorCount(tagsFor(endpoint, verb));
  }

  private static Tags tagsFor(String endpoint, String verb) {
    return Tags.of("endpoint", endpoint, "verb", verb);
  }
}
