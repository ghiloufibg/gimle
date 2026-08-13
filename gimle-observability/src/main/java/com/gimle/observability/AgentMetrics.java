package com.gimle.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;

/**
 * A single Timer/Counter pair around {@code gimle-agent}'s own tick loop body, intentionally
 * coarser-grained than {@link ApiServerMetrics}/{@link FafnirMetrics}'s per-endpoint tags: an agent
 * tick isn't a request with a verb/endpoint shape, it's one fixed sequence (reconcile assignments,
 * send heartbeat, check certificate rotation) run on a timer, so there is exactly one dimension
 * worth tracking -- how long a tick took, and whether it failed.
 */
public final class AgentMetrics {

  private final MeterRegistry registry;
  private final TaggedRequestMetrics metrics;

  public AgentMetrics() {
    this(new SimpleMeterRegistry());
  }

  public AgentMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.metrics =
        new TaggedRequestMetrics(
            registry,
            "gimle.agent.tick.latency",
            "gimle.agent.tick.count",
            "gimle.agent.tick.errors",
            false);
  }

  public MeterRegistry registry() {
    return registry;
  }

  public void recordTick(Duration latency, boolean error) {
    metrics.record(Tags.empty(), latency, error);
  }

  /** Same "cumulative total, zero if never recorded" contract as {@link FafnirMetrics}. */
  public double tickCount() {
    return metrics.count(Tags.empty());
  }

  public double errorCount() {
    return metrics.errorCount(Tags.empty());
  }
}
