package com.gimle.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;

/**
 * Per-endpoint Micrometer wiring for {@code gimle-andvari} -- the same {@link
 * MeterRegistry}/tag/counter/timer shape {@link FafnirMetrics}/{@link StoreMetrics}/{@link
 * WorkerMetrics} already establish, kept as its own class rather than folded into one of those
 * because none of their tag dimensions mean anything to Andvari's own caller ({@code endpoint}/
 * {@code verb}, the same pair {@link FafnirMetrics} tracks, but under Andvari's own metric-name
 * prefix so a shared Muninn deployment can tell the two processes' requests apart). Defaults to an
 * in-memory {@link SimpleMeterRegistry}, matching every other metrics wrapper in this module.
 */
public final class AndvariMetrics {

  private final MeterRegistry registry;
  private final TaggedRequestMetrics metrics;

  public AndvariMetrics() {
    this(new SimpleMeterRegistry());
  }

  public AndvariMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.metrics =
        new TaggedRequestMetrics(
            registry,
            "gimle.andvari.request.latency",
            "gimle.andvari.request.count",
            "gimle.andvari.request.errors",
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
