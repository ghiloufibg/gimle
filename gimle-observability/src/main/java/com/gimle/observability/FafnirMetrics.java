package com.gimle.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;

/**
 * Per-endpoint Micrometer wiring for {@code gimle-fafnir} -- the same {@link
 * MeterRegistry}/tag/counter/timer shape {@link WorkerMetrics} already establishes for {@code
 * gimle-worker}, kept as its own class rather than folded into {@code WorkerMetrics} because the
 * two track different dimensions ({@code ModuleId} there, {@code endpoint}/{@code verb} here) and
 * neither's tag set means anything on the other's caller. Defaults to an in-memory {@link
 * SimpleMeterRegistry}, matching every other metrics wrapper in this class's own module.
 */
public final class FafnirMetrics {

  private final MeterRegistry registry;
  private final TaggedRequestMetrics metrics;

  public FafnirMetrics() {
    this(new SimpleMeterRegistry());
  }

  public FafnirMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.metrics =
        new TaggedRequestMetrics(
            registry,
            "gimle.fafnir.request.latency",
            "gimle.fafnir.request.count",
            "gimle.fafnir.request.errors",
            true);
  }

  public MeterRegistry registry() {
    return registry;
  }

  public void recordRequest(String endpoint, String verb, Duration latency, boolean error) {
    metrics.record(tagsFor(endpoint, verb), latency, error);
  }

  /** Same "cumulative total, zero if never recorded" contract as {@link WorkerMetrics}. */
  public double requestCount(String endpoint, String verb) {
    return metrics.count(tagsFor(endpoint, verb));
  }

  public double errorCount(String endpoint, String verb) {
    return metrics.errorCount(tagsFor(endpoint, verb));
  }

  /**
   * Feeds {@code LoginThrottle}-based rate limiting on the {@code /secrets/*} surface -- a
   * consecutive-authorization-failure signal, tagged only by {@code verb} rather than by calling
   * principal, to keep this counter's cardinality bounded regardless of fleet size.
   */
  public void recordAuthzFailure(String verb) {
    Counter.builder("gimle.fafnir.authz.failures").tag("verb", verb).register(registry).increment();
  }

  public double authzFailureCount(String verb) {
    Counter counter = registry.find("gimle.fafnir.authz.failures").tag("verb", verb).counter();
    return counter == null ? 0.0 : counter.count();
  }

  private static Tags tagsFor(String endpoint, String verb) {
    return Tags.of("endpoint", endpoint, "verb", verb);
  }
}
