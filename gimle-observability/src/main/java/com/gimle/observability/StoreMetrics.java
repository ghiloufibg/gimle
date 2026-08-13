package com.gimle.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;

/**
 * Per-RPC-kind Micrometer wiring for {@code gimle-mimir}'s {@code StoreNode} -- tagged by the
 * {@code StoreRpc.Request}'s own concrete type name (e.g. {@code "Propose"}, {@code "GetTenant"})
 * rather than an HTTP endpoint/verb pair the way {@link ApiServerMetrics}/{@link FafnirMetrics}
 * are, since {@code StoreNode}'s dispatch is a single Java method switching on request type, not an
 * HTTP router.
 */
public final class StoreMetrics {

  private final MeterRegistry registry;
  private final TaggedRequestMetrics metrics;

  public StoreMetrics() {
    this(new SimpleMeterRegistry());
  }

  public StoreMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.metrics =
        new TaggedRequestMetrics(
            registry,
            "gimle.store.request.latency",
            "gimle.store.request.count",
            "gimle.store.request.errors",
            true);
  }

  public MeterRegistry registry() {
    return registry;
  }

  public void recordRequest(String rpcKind, Duration latency, boolean error) {
    metrics.record(tagsFor(rpcKind), latency, error);
  }

  /** Same "cumulative total, zero if never recorded" contract as {@link FafnirMetrics}. */
  public double requestCount(String rpcKind) {
    return metrics.count(tagsFor(rpcKind));
  }

  public double errorCount(String rpcKind) {
    return metrics.errorCount(tagsFor(rpcKind));
  }

  private static Tags tagsFor(String rpcKind) {
    return Tags.of("rpc", rpcKind);
  }
}
