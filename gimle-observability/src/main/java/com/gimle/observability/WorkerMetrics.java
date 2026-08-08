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
 * Per-module tagged Micrometer wiring -- one {@link MeterRegistry} per worker JVM tracking request
 * rate/latency/error counters, thread counts, and classloader/metaspace footprint. Defaults to an
 * in-memory {@link SimpleMeterRegistry} with no exporter wired up, since the counters just need to
 * exist and be queryable; a real registry backed by an external exporter can be supplied instead.
 *
 * <p>Gauges are backed by an internally-tracked, mutable {@link AtomicLong} per module, registered
 * once and updated in place -- passing a boxed primitive to {@code MeterRegistry#gauge} directly
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

  public void recordRequest(ModuleId id, Duration latency, boolean error) {
    Tags tags = tagsFor(id);
    Timer.builder("gimle.module.request.latency").tags(tags).register(registry).record(latency);
    Counter.builder("gimle.module.request.count").tags(tags).register(registry).increment();
    if (error) {
      Counter.builder("gimle.module.request.errors").tags(tags).register(registry).increment();
    }
  }

  /**
   * The cumulative request count {@link #recordRequest} has ever incremented for {@code id} --
   * {@code 0} if no request has been recorded yet, matching an absent counter rather than throwing.
   * A caller wanting a rate (e.g. {@code WorkerMain}'s periodic metrics report) takes two readings
   * a known interval apart and divides the delta, the same pattern {@code recordThreadCount}'s own
   * gauge-vs-counter split already implies: this class exposes cumulative totals, not rates.
   */
  public double requestCount(ModuleId id) {
    Counter counter = registry.find("gimle.module.request.count").tags(tagsFor(id)).counter();
    return counter == null ? 0.0 : counter.count();
  }

  /**
   * Same shape as {@link #requestCount}, for the error-only counter {@link #recordRequest} feeds.
   */
  public double errorCount(ModuleId id) {
    Counter counter = registry.find("gimle.module.request.errors").tags(tagsFor(id)).counter();
    return counter == null ? 0.0 : counter.count();
  }

  public void recordThreadCount(ModuleId id, long count) {
    gaugeHolder(threadCounts, "gimle.module.threads", id).set(count);
  }

  public void recordMetaspaceBytes(ModuleId id, long bytes) {
    gaugeHolder(metaspaceBytes, "gimle.module.metaspace.bytes", id).set(bytes);
  }

  private AtomicLong gaugeHolder(Map<ModuleId, AtomicLong> holders, String name, ModuleId id) {
    return holders.computeIfAbsent(
        id, key -> registry.gauge(name, tagsFor(id), new AtomicLong(), AtomicLong::get));
  }

  private static Tags tagsFor(ModuleId id) {
    return Tags.of("module", id.name(), "version", id.version().toString());
  }
}
