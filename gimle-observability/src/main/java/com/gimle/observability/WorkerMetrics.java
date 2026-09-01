package com.gimle.observability;

import com.gimle.core.module.ModuleId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
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

  private static final String CIRCUIT_BREAKER_STATE = "gimle.fabric.circuitbreaker.state";
  private static final String CIRCUIT_BREAKER_TRANSITIONS =
      "gimle.fabric.circuitbreaker.transitions";

  private final MeterRegistry registry;
  private final TaggedRequestMetrics metrics;
  private final TaggedRequestMetrics clientMetrics;
  private final Map<ModuleId, AtomicLong> threadCounts = new ConcurrentHashMap<>();
  private final Map<ModuleId, AtomicLong> metaspaceBytes = new ConcurrentHashMap<>();
  private final Map<Tags, AtomicLong> circuitBreakerStates = new ConcurrentHashMap<>();

  public WorkerMetrics() {
    this(new SimpleMeterRegistry());
  }

  public WorkerMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.metrics =
        new TaggedRequestMetrics(
            registry,
            "gimle.module.request.latency",
            "gimle.module.request.count",
            "gimle.module.request.errors",
            true);
    this.clientMetrics =
        new TaggedRequestMetrics(
            registry,
            "gimle.fabric.client.request.latency",
            "gimle.fabric.client.request.count",
            "gimle.fabric.client.request.errors",
            true);
  }

  public MeterRegistry registry() {
    return registry;
  }

  public void recordRequest(ModuleId id, Duration latency, boolean error) {
    metrics.record(tagsFor(id), latency, error);
  }

  /**
   * The cumulative request count {@link #recordRequest} has ever incremented for {@code id} --
   * {@code 0} if no request has been recorded yet, matching an absent counter rather than throwing.
   * A caller wanting a rate (e.g. {@code WorkerMain}'s periodic metrics report) takes two readings
   * a known interval apart and divides the delta, the same pattern {@code recordThreadCount}'s own
   * gauge-vs-counter split already implies: this class exposes cumulative totals, not rates.
   */
  public double requestCount(ModuleId id) {
    return metrics.count(tagsFor(id));
  }

  /**
   * Same shape as {@link #requestCount}, for the error-only counter {@link #recordRequest} feeds.
   */
  public double errorCount(ModuleId id) {
    return metrics.errorCount(tagsFor(id));
  }

  /**
   * The outbound-call counterpart of {@link #recordRequest}: a worker whose hosted module only ever
   * *calls out* through the fabric (never receives an inbound call itself, so {@link
   * #recordRequest} never fires for it) still produces real request-rate/latency/error telemetry
   * this way, tagged by the callee interface name rather than a {@link ModuleId} -- the caller-side
   * registry ({@code FabricServiceRegistry}) has no reliable calling-module identity to tag by,
   * only the interface it dialed.
   */
  public void recordClientRequest(String interfaceName, Duration latency, boolean error) {
    clientMetrics.record(clientTagsFor(interfaceName), latency, error);
  }

  /** Same "cumulative total, zero if never recorded" contract {@link #requestCount} documents. */
  public double clientRequestCount(String interfaceName) {
    return clientMetrics.count(clientTagsFor(interfaceName));
  }

  /** Same shape as {@link #clientRequestCount}, for the error-only counter. */
  public double clientErrorCount(String interfaceName) {
    return clientMetrics.errorCount(clientTagsFor(interfaceName));
  }

  private static Tags clientTagsFor(String interfaceName) {
    return Tags.of("interface", interfaceName);
  }

  /**
   * Publishes the current state of one fabric circuit breaker as a {@code
   * gimle.fabric.circuitbreaker.state} gauge tagged by the callee interface and the endpoint (a
   * {@code nodeId/workerId} pair) it guards. {@code stateLevel} is the caller's own numeric
   * encoding of that state -- this class deliberately doesn't depend on {@code gimle-fabric}, so
   * the encoding stays with the enum it encodes rather than being duplicated here.
   *
   * <p>An endpoint with no gauge yet reads the same as a closed one, which is exactly right: "no
   * breaker has ever tripped for this endpoint" and "its breaker is closed" mean the same thing to
   * an operator asking whether a breaker is why traffic isn't reaching an instance.
   */
  public void recordCircuitBreakerState(String interfaceName, String endpoint, long stateLevel) {
    Tags tags = breakerTagsFor(interfaceName, endpoint);
    circuitBreakerStates
        .computeIfAbsent(
            tags,
            key -> registry.gauge(CIRCUIT_BREAKER_STATE, key, new AtomicLong(), AtomicLong::get))
        .set(stateLevel);
  }

  /**
   * {@link #recordCircuitBreakerState} plus a {@code gimle.fabric.circuitbreaker.transitions}
   * counter increment tagged by the state entered -- the gauge answers "what is this breaker doing
   * now", the counter answers "how often has it been flapping", and a breaker that opens and closes
   * repeatedly between two metric snapshots is only visible in the second.
   */
  public void recordCircuitBreakerTransition(
      String interfaceName, String endpoint, String stateName, long stateLevel) {
    recordCircuitBreakerState(interfaceName, endpoint, stateLevel);
    Counter.builder(CIRCUIT_BREAKER_TRANSITIONS)
        .tags(breakerTagsFor(interfaceName, endpoint).and("state", stateName))
        .register(registry)
        .increment();
  }

  /** The last state published for this endpoint's breaker, {@code 0} if none ever was. */
  public double circuitBreakerState(String interfaceName, String endpoint) {
    AtomicLong holder = circuitBreakerStates.get(breakerTagsFor(interfaceName, endpoint));
    return holder == null ? 0.0 : holder.get();
  }

  /** Same "cumulative total, zero if never recorded" contract {@link #requestCount} documents. */
  public double circuitBreakerTransitionCount(
      String interfaceName, String endpoint, String stateName) {
    Counter counter =
        registry
            .find(CIRCUIT_BREAKER_TRANSITIONS)
            .tags(breakerTagsFor(interfaceName, endpoint).and("state", stateName))
            .counter();
    return counter == null ? 0.0 : counter.count();
  }

  /**
   * Drops both breaker meters for one endpoint -- called when the registry stops tracking that
   * endpoint's breaker at all, so a long-lived worker watching endpoints churn (worker respawns
   * minting fresh ports) doesn't accumulate one permanent meter pair per address it ever dialed.
   */
  public void evictCircuitBreaker(String interfaceName, String endpoint) {
    Tags tags = breakerTagsFor(interfaceName, endpoint);
    if (circuitBreakerStates.remove(tags) != null) {
      registry.find(CIRCUIT_BREAKER_STATE).tags(tags).gauges().forEach(registry::remove);
    }
    registry.find(CIRCUIT_BREAKER_TRANSITIONS).tags(tags).counters().forEach(registry::remove);
  }

  private static Tags breakerTagsFor(String interfaceName, String endpoint) {
    return Tags.of("interface", interfaceName, "endpoint", endpoint);
  }

  public void recordThreadCount(ModuleId id, long count) {
    gaugeHolder(threadCounts, "gimle.module.threads", id).set(count);
  }

  public void recordMetaspaceBytes(ModuleId id, long bytes) {
    gaugeHolder(metaspaceBytes, "gimle.module.metaspace.bytes", id).set(bytes);
  }

  /**
   * Removes every meter this class ever registered for {@code id} -- the request/error counters,
   * the latency timer, and both gauges -- so a worker that redeploys the same module name across
   * many versions (or the same version repeatedly) doesn't accumulate one permanent meter set per
   * {@code (module, version)} forever. Called once {@code id} is uninstalled, never on a mere stop:
   * a stopped-but-still-installed module can restart and pick its counters back up, but an
   * uninstalled one is gone for good and so is its ModuleId. {@link #clientMetrics} isn't touched
   * here -- it's tagged by callee interface name, not {@link ModuleId}, since the caller side has
   * no reliable calling-module identity to evict by (see {@link #recordClientRequest}'s own
   * javadoc).
   */
  public void evict(ModuleId id) {
    Tags tags = tagsFor(id);
    metrics.evict(tags);
    evictGauge(threadCounts, "gimle.module.threads", id, tags);
    evictGauge(metaspaceBytes, "gimle.module.metaspace.bytes", id, tags);
  }

  private AtomicLong gaugeHolder(Map<ModuleId, AtomicLong> holders, String name, ModuleId id) {
    return holders.computeIfAbsent(
        id, key -> registry.gauge(name, tagsFor(id), new AtomicLong(), AtomicLong::get));
  }

  private void evictGauge(Map<ModuleId, AtomicLong> holders, String name, ModuleId id, Tags tags) {
    if (holders.remove(id) == null) {
      return;
    }
    registry.find(name).tags(tags).gauges().forEach(registry::remove);
  }

  private static Tags tagsFor(ModuleId id) {
    return Tags.of("module", id.name(), "version", id.version().toString());
  }
}
