package com.gimle.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;

/**
 * Per-RPC-kind Micrometer wiring for {@code gimle-mimir}'s {@code StoreNode} (design doc Part
 * B/O-10) -- tagged by the {@code StoreRpc.Request}'s own concrete type name (e.g. {@code
 * "Propose"}, {@code "GetTenant"}) rather than an HTTP endpoint/verb pair the way {@link
 * ApiServerMetrics}/{@link FafnirMetrics} are, since {@code StoreNode}'s dispatch is a single Java
 * method switching on request type, not an HTTP router.
 */
public final class StoreMetrics {

  private final MeterRegistry registry;

  public StoreMetrics() {
    this(new SimpleMeterRegistry());
  }

  public StoreMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public MeterRegistry registry() {
    return registry;
  }

  public void recordRequest(String rpcKind, Duration latency, boolean error) {
    Tags tags = Tags.of("rpc", rpcKind);
    Timer.builder("gimle.store.request.latency")
        .tags(tags)
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(registry)
        .record(latency);
    Counter.builder("gimle.store.request.count").tags(tags).register(registry).increment();
    if (error) {
      Counter.builder("gimle.store.request.errors").tags(tags).register(registry).increment();
    }
  }

  /** Same "cumulative total, zero if never recorded" contract as {@link FafnirMetrics}. */
  public double requestCount(String rpcKind) {
    Counter counter =
        registry.find("gimle.store.request.count").tags(Tags.of("rpc", rpcKind)).counter();
    return counter == null ? 0.0 : counter.count();
  }

  public double errorCount(String rpcKind) {
    Counter counter =
        registry.find("gimle.store.request.errors").tags(Tags.of("rpc", rpcKind)).counter();
    return counter == null ? 0.0 : counter.count();
  }
}
