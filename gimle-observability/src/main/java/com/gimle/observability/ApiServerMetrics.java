package com.gimle.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;

/**
 * Per-endpoint Micrometer wiring for {@code gimle-controlplane}'s {@code ApiServer} (design doc
 * Part B/O-10) -- the identical {@link MeterRegistry}/tag/counter/timer shape {@link FafnirMetrics}
 * already establishes for {@code gimle-fafnir}, kept as its own class rather than shared with it
 * because the two track different callers' endpoint namespaces and neither's tag set means anything
 * on the other's. Defaults to an in-memory {@link SimpleMeterRegistry}, matching every other
 * metrics wrapper in this module.
 */
public final class ApiServerMetrics {

  private final MeterRegistry registry;

  public ApiServerMetrics() {
    this(new SimpleMeterRegistry());
  }

  public ApiServerMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public MeterRegistry registry() {
    return registry;
  }

  public void recordRequest(String endpoint, String verb, Duration latency, boolean error) {
    Tags tags = tagsFor(endpoint, verb);
    Timer.builder("gimle.controlplane.request.latency")
        .tags(tags)
        .register(registry)
        .record(latency);
    Counter.builder("gimle.controlplane.request.count").tags(tags).register(registry).increment();
    if (error) {
      Counter.builder("gimle.controlplane.request.errors")
          .tags(tags)
          .register(registry)
          .increment();
    }
  }

  /** Same "cumulative total, zero if never recorded" contract as {@link FafnirMetrics}. */
  public double requestCount(String endpoint, String verb) {
    Counter counter =
        registry.find("gimle.controlplane.request.count").tags(tagsFor(endpoint, verb)).counter();
    return counter == null ? 0.0 : counter.count();
  }

  public double errorCount(String endpoint, String verb) {
    Counter counter =
        registry.find("gimle.controlplane.request.errors").tags(tagsFor(endpoint, verb)).counter();
    return counter == null ? 0.0 : counter.count();
  }

  private static Tags tagsFor(String endpoint, String verb) {
    return Tags.of("endpoint", endpoint, "verb", verb);
  }
}
