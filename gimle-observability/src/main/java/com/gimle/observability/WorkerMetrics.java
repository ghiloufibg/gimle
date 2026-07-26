package com.gimle.observability;

import com.gimle.core.module.ModuleId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-module tagged Micrometer wiring — one {@link MeterRegistry} per worker JVM (design §5.1):
 * request rate/latency/error counters, thread counts, classloader/metaspace footprint. Defaults to
 * an in-memory {@link SimpleMeterRegistry} (no exporter wired up yet — Phase 2 doesn't need one,
 * just the counters existing and queryable); a real registry can be supplied instead once a later
 * phase adds a backend.
 *
 * <p>Gauges are backed by an internally-tracked, mutable {@link AtomicLong} per module, registered
 * once and updated in place — passing a boxed primitive to {@code MeterRegistry#gauge} directly
 * would silently freeze at whatever value was passed the first time, since the registry only
 * re-reads the same (immutable) {@code Long} instance thereafter.
 */
public final class WorkerMetrics {

  private final MeterRegistry registry;
  private final Map<ModuleId, AtomicLong> threadCounts = new ConcurrentHashMap<>();
  private final Map<ModuleId, AtomicLong> metaspaceBytes = new ConcurrentHashMap<>();

  public WorkerMetrics() {
    this(new SimpleMeterRegistry());
  }

  public WorkerMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public MeterRegistry registry() {
    return registry;
  }

  public void record_request(ModuleId id, Duration latency, boolean error) {
    Tags tags = tags_for(id);
    Timer.builder("gimle.module.request.latency").tags(tags).register(registry).record(latency);
    Counter.builder("gimle.module.request.count").tags(tags).register(registry).increment();
    if (error) {
      Counter.builder("gimle.module.request.errors").tags(tags).register(registry).increment();
    }
  }

  public void record_thread_count(ModuleId id, long count) {
    gauge_holder(threadCounts, "gimle.module.threads", id).set(count);
  }

  public void record_metaspace_bytes(ModuleId id, long bytes) {
    gauge_holder(metaspaceBytes, "gimle.module.metaspace.bytes", id).set(bytes);
  }

  private AtomicLong gauge_holder(Map<ModuleId, AtomicLong> holders, String name, ModuleId id) {
    return holders.computeIfAbsent(
        id, key -> registry.gauge(name, tags_for(id), new AtomicLong(), AtomicLong::get));
  }

  private static Tags tags_for(ModuleId id) {
    return Tags.of("module", id.name(), "version", id.version().toString());
  }
}
