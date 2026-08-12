package com.gimle.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;

/**
 * Per-endpoint Micrometer wiring for {@code gimle-fafnir} (design doc §9's Observability
 * subsection) -- the same {@link MeterRegistry}/tag/counter/timer shape {@link WorkerMetrics}
 * already establishes for {@code gimle-worker}, kept as its own class rather than folded into
 * {@code WorkerMetrics} because the two track different dimensions ({@code ModuleId} there, {@code
 * endpoint}/{@code verb} here) and neither's tag set means anything on the other's caller. Defaults
 * to an in-memory {@link SimpleMeterRegistry}, matching every other metrics wrapper in this class's
 * own module.
 */
public final class FafnirMetrics {

  private final MeterRegistry registry;

  public FafnirMetrics() {
    this(new SimpleMeterRegistry());
  }

  public FafnirMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public MeterRegistry registry() {
    return registry;
  }

  public void recordRequest(String endpoint, String verb, Duration latency, boolean error) {
    Tags tags = tagsFor(endpoint, verb);
    Timer.builder("gimle.fafnir.request.latency")
        .tags(tags)
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(registry)
        .record(latency);
    Counter.builder("gimle.fafnir.request.count").tags(tags).register(registry).increment();
    if (error) {
      Counter.builder("gimle.fafnir.request.errors").tags(tags).register(registry).increment();
    }
  }

  /** Same "cumulative total, zero if never recorded" contract as {@link WorkerMetrics}. */
  public double requestCount(String endpoint, String verb) {
    Counter counter =
        registry.find("gimle.fafnir.request.count").tags(tagsFor(endpoint, verb)).counter();
    return counter == null ? 0.0 : counter.count();
  }

  public double errorCount(String endpoint, String verb) {
    Counter counter =
        registry.find("gimle.fafnir.request.errors").tags(tagsFor(endpoint, verb)).counter();
    return counter == null ? 0.0 : counter.count();
  }

  /**
   * Feeds {@code LoginThrottle}-based rate limiting on the {@code /secrets/*} surface (design doc
   * §9's Rate limiting subsection) -- a consecutive-authorization-failure signal, tagged only by
   * {@code verb} rather than by calling principal, to keep this counter's cardinality bounded
   * regardless of fleet size.
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
