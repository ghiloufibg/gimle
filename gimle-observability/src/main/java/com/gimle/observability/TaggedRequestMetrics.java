package com.gimle.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/**
 * The Timer/Counter/error-Counter triple every per-process metrics wrapper in this package
 * registers under its own metric-name prefix -- factored out once {@link AgentMetrics}, {@link
 * ApiServerMetrics}, {@link FafnirMetrics}, {@link StoreMetrics}, and {@link WorkerMetrics} had
 * each reimplemented the identical wiring under their own names and tag dimensions.
 */
final class TaggedRequestMetrics {

  private final MeterRegistry registry;
  private final String latencyName;
  private final String countName;
  private final String errorsName;
  private final boolean publishPercentiles;

  TaggedRequestMetrics(
      MeterRegistry registry,
      String latencyName,
      String countName,
      String errorsName,
      boolean publishPercentiles) {
    this.registry = registry;
    this.latencyName = latencyName;
    this.countName = countName;
    this.errorsName = errorsName;
    this.publishPercentiles = publishPercentiles;
  }

  void record(Tags tags, Duration latency, boolean error) {
    Timer.Builder timerBuilder = Timer.builder(latencyName).tags(tags);
    if (publishPercentiles) {
      timerBuilder.publishPercentiles(0.5, 0.95, 0.99);
    }
    timerBuilder.register(registry).record(latency);
    Counter.builder(countName).tags(tags).register(registry).increment();
    if (error) {
      Counter.builder(errorsName).tags(tags).register(registry).increment();
    }
  }

  /** Same "cumulative total, zero if never recorded" contract every caller of this class needs. */
  double count(Tags tags) {
    Counter counter = registry.find(countName).tags(tags).counter();
    return counter == null ? 0.0 : counter.count();
  }

  double errorCount(Tags tags) {
    Counter counter = registry.find(errorsName).tags(tags).counter();
    return counter == null ? 0.0 : counter.count();
  }
}
