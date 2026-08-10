package com.gimle.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;

/**
 * A single Timer/Counter pair around {@code gimle-agent}'s own tick loop body (design doc Part
 * B/O-10) -- intentionally coarser-grained than {@link ApiServerMetrics}/{@link FafnirMetrics}'s
 * per-endpoint tags: an agent tick isn't a request with a verb/endpoint shape, it's one fixed
 * sequence (reconcile assignments, send heartbeat, check certificate rotation) run on a timer, so
 * there is exactly one dimension worth tracking -- how long a tick took, and whether it failed.
 */
public final class AgentMetrics {

  private final MeterRegistry registry;

  public AgentMetrics() {
    this(new SimpleMeterRegistry());
  }

  public AgentMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public MeterRegistry registry() {
    return registry;
  }

  public void recordTick(Duration latency, boolean error) {
    Timer.builder("gimle.agent.tick.latency").register(registry).record(latency);
    Counter.builder("gimle.agent.tick.count").register(registry).increment();
    if (error) {
      Counter.builder("gimle.agent.tick.errors").register(registry).increment();
    }
  }

  /** Same "cumulative total, zero if never recorded" contract as {@link FafnirMetrics}. */
  public double tickCount() {
    Counter counter = registry.find("gimle.agent.tick.count").counter();
    return counter == null ? 0.0 : counter.count();
  }

  public double errorCount() {
    Counter counter = registry.find("gimle.agent.tick.errors").counter();
    return counter == null ? 0.0 : counter.count();
  }
}
