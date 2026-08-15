package com.gimle.fabric.registry;

import com.gimle.core.exception.GimleClusterException;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ServiceExport;
import com.gimle.core.protocol.ControlMessage;
import com.gimle.fabric.balance.LeastOutstandingRequestsSelector;
import com.gimle.fabric.breaker.CircuitBreaker;
import com.gimle.fabric.catalog.ServiceCatalog;
import com.gimle.fabric.catalog.ServiceEndpoint;
import com.gimle.fabric.cluster.MemberId;
import com.gimle.fabric.trace.TraceContext;
import com.gimle.fabric.transport.FabricClient;
import com.gimle.fabric.transport.FabricFrame;
import com.gimle.fabric.transport.ObjectMarshalling;
import com.gimle.module.lifecycle.ServiceRegistry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageEntry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.SocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The worker-side {@link ServiceRegistry} implementation that replaces a bare {@code
 * SimpleServiceRegistry}, wired in by {@code WorkerMain}: wraps {@code localRegistry} unchanged for
 * the same-worker tier, and adds the same-machine and remote tiers on top via a locally-cached
 * {@link ServiceCatalog}, one {@link CircuitBreaker} per remote endpoint, and
 * least-outstanding-requests selection among the endpoints each tier currently allows.
 *
 * <p>{@code lookup(Class<T>)} tries, in order: (1) same-worker -- a direct reference, unchanged
 * behavior; (2) same-machine catalog entries; (3) remote catalog entries. Tiers (2)/(3) both
 * dispatch through a dynamic {@link Proxy} whose {@link InvocationHandler} marshals the call over
 * the fabric wire protocol -- the dispatch layer needed for least-outstanding-requests tracking to
 * become measurable in the first place.
 */
public final class FabricServiceRegistry implements ServiceRegistry {

  private static final Logger log = LoggerFactory.getLogger(FabricServiceRegistry.class);

  /**
   * Default panic-mode ejection floor: once more than this fraction of a lookup's own candidates
   * have an open circuit breaker, {@link #selectAllowedCandidate} stops excluding them -- a
   * correlated failure that happens to be transient shouldn't route to nowhere.
   */
  private static final double DEFAULT_MAX_EJECTION_PERCENT = 0.5;

  private final MemberId selfNode;
  private final String workerId;
  private final ServiceRegistry localRegistry;
  private final ServiceCatalog catalog;
  private final Function<ModuleId, List<ServiceExport>> exportsOf;
  private final Consumer<ControlMessage> controlChannel;
  private final ClassLoader interfaceLoader;
  private final int breakerWindowSize;
  private final double breakerErrorRateThreshold;
  private final Duration breakerCooldown;
  private final Optional<String> selfTenantId;
  private final double maxEjectionPercent;
  private final boolean defaultDenyCrossTenant;

  private final Map<ServiceEndpoint, CircuitBreaker> breakers = new ConcurrentHashMap<>();
  private final LeastOutstandingRequestsSelector<ServiceEndpoint> selector =
      new LeastOutstandingRequestsSelector<>();
  private final Map<ModuleId, Set<ServiceExport>> registeredExportsByOwner =
      new ConcurrentHashMap<>();

  public FabricServiceRegistry(
      MemberId selfNode,
      String workerId,
      ServiceRegistry localRegistry,
      ServiceCatalog catalog,
      Function<ModuleId, List<ServiceExport>> exportsOf,
      Consumer<ControlMessage> controlChannel,
      ClassLoader interfaceLoader,
      int breakerWindowSize,
      double breakerErrorRateThreshold,
      Duration breakerCooldown) {
    this(
        selfNode,
        workerId,
        localRegistry,
        catalog,
        exportsOf,
        controlChannel,
        interfaceLoader,
        breakerWindowSize,
        breakerErrorRateThreshold,
        breakerCooldown,
        Optional.empty());
  }

  /**
   * {@code selfTenantId} is this worker's own tenant, if any -- consulted in {@link #lookup} to
   * filter out any candidate whose export restricts {@code allowedTenantIds} to a set this tenant
   * isn't in. An untenanted worker ({@code Optional.empty()}) can never satisfy a restricted
   * export's allow-list (it can't prove membership in any tenant), matching {@link
   * ServiceExport#permitsTenant}'s own safe-by-default semantics.
   */
  public FabricServiceRegistry(
      MemberId selfNode,
      String workerId,
      ServiceRegistry localRegistry,
      ServiceCatalog catalog,
      Function<ModuleId, List<ServiceExport>> exportsOf,
      Consumer<ControlMessage> controlChannel,
      ClassLoader interfaceLoader,
      int breakerWindowSize,
      double breakerErrorRateThreshold,
      Duration breakerCooldown,
      Optional<String> selfTenantId) {
    this(
        selfNode,
        workerId,
        localRegistry,
        catalog,
        exportsOf,
        controlChannel,
        interfaceLoader,
        breakerWindowSize,
        breakerErrorRateThreshold,
        breakerCooldown,
        selfTenantId,
        DEFAULT_MAX_EJECTION_PERCENT);
  }

  /**
   * {@code maxEjectionPercent} is the panic-mode floor described on {@link
   * #DEFAULT_MAX_EJECTION_PERCENT}, exposed here for callers (tests, chiefly) that want a
   * non-default value; production wiring goes through one of the shorter overloads above and gets
   * the default.
   */
  public FabricServiceRegistry(
      MemberId selfNode,
      String workerId,
      ServiceRegistry localRegistry,
      ServiceCatalog catalog,
      Function<ModuleId, List<ServiceExport>> exportsOf,
      Consumer<ControlMessage> controlChannel,
      ClassLoader interfaceLoader,
      int breakerWindowSize,
      double breakerErrorRateThreshold,
      Duration breakerCooldown,
      Optional<String> selfTenantId,
      double maxEjectionPercent) {
    this(
        selfNode,
        workerId,
        localRegistry,
        catalog,
        exportsOf,
        controlChannel,
        interfaceLoader,
        breakerWindowSize,
        breakerErrorRateThreshold,
        breakerCooldown,
        selfTenantId,
        maxEjectionPercent,
        false);
  }

  /**
   * {@code defaultDenyCrossTenant} flips {@link ServiceExport#permitsTenant}'s own safe-by-default
   * semantics for an export that doesn't declare {@code allowedTenantIds} at all: normally that
   * means "any tenant may consume this," which silently makes an unscoped export public
   * cluster-wide the moment tenancy is turned on, whether or not the module author meant it. When
   * {@code true}, this registry additionally requires such an unscoped export's caller to be
   * untenanted too -- {@code ServiceExport}/{@code ServiceEndpoint} don't track the exporting
   * module's own tenant, so "the same tenant as the exporter" (the ideal, narrowest rule) isn't
   * representable without a wire-format change; "untenanted-only" is the narrowest rule expressible
   * with today's data, and a strictly safer default than "any tenant" either way. A tenant that
   * genuinely needs cross-tenant access to a specific export still gets it by being named in that
   * export's own {@code allowedTenantIds} -- this flag only changes what happens when a manifest is
   * silent. Default {@code false}: nothing existing changes behavior.
   */
  public FabricServiceRegistry(
      MemberId selfNode,
      String workerId,
      ServiceRegistry localRegistry,
      ServiceCatalog catalog,
      Function<ModuleId, List<ServiceExport>> exportsOf,
      Consumer<ControlMessage> controlChannel,
      ClassLoader interfaceLoader,
      int breakerWindowSize,
      double breakerErrorRateThreshold,
      Duration breakerCooldown,
      Optional<String> selfTenantId,
      double maxEjectionPercent,
      boolean defaultDenyCrossTenant) {
    this.selfNode = selfNode;
    this.workerId = workerId;
    this.localRegistry = localRegistry;
    this.catalog = catalog;
    this.exportsOf = exportsOf;
    this.controlChannel = controlChannel;
    this.interfaceLoader = interfaceLoader;
    this.breakerWindowSize = breakerWindowSize;
    this.breakerErrorRateThreshold = breakerErrorRateThreshold;
    this.breakerCooldown = breakerCooldown;
    this.selfTenantId = selfTenantId;
    this.maxEjectionPercent = maxEjectionPercent;
    this.defaultDenyCrossTenant = defaultDenyCrossTenant;
  }

  @Override
  public <T> void register(ModuleId owner, Class<T> iface, T instance) {
    localRegistry.register(owner, iface, instance);
    Optional<ServiceExport> export = exportFor(owner, iface);
    if (export.isEmpty()) {
      log.debug(
          "module {} registered {} which isn't in its own declared exports; keeping it"
              + " same-worker-only",
          owner,
          iface.getName());
      return;
    }
    registeredExportsByOwner
        .computeIfAbsent(owner, key -> ConcurrentHashMap.newKeySet())
        .add(export.get());
    controlChannel.accept(new ControlMessage.ServiceRegistered(owner, export.get()));
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> Optional<T> lookup(Class<T> iface) {
    Optional<T> local = localRegistry.lookup(iface);
    if (local.isPresent()) {
      return local;
    }

    List<ServiceEndpoint> allKnown = catalog.endpointsForInterface(iface.getName());
    if (allKnown.isEmpty()) {
      throw GimleClusterException.noExportingMember(iface.getName());
    }

    List<ServiceEndpoint> sameMachine = new ArrayList<>();
    List<ServiceEndpoint> remote = new ArrayList<>();
    for (ServiceEndpoint endpoint : allKnown) {
      boolean isSameMachine = endpoint.node().nodeId().equals(selfNode.nodeId());
      if (isSameMachine && endpoint.workerId().equals(workerId)) {
        continue; // this worker's own entry: already covered by the local-registry tier above
      }
      if (!permitsUnderTenantPolicy(endpoint.export())) {
        continue; // this tenant isn't on the export's allow-list
      }
      // Breaker state is deliberately not filtered here -- selectAllowedCandidate applies it
      // against this tier's own candidate count, so the panic-mode floor below has an accurate
      // denominator instead of endpoints having already vanished before it can see them.
      (isSameMachine ? sameMachine : remote).add(endpoint);
    }

    List<ServiceEndpoint> candidates = localityAwareCandidates(sameMachine, remote);
    ServiceEndpoint chosen = selectAllowedCandidate(candidates);
    if (chosen == null) {
      return Optional.empty();
    }
    return Optional.of((T) createProxy(iface, chosen));
  }

  /**
   * Same-worker only, by design: this is what {@code FabricServer}'s inbound dispatch consults to
   * find a provider actually hosted in this worker, never a reason to recurse back out over the
   * fabric itself.
   */
  @Override
  public Optional<Object> lookupByInterfaceName(String interfaceName) {
    return localRegistry.lookupByInterfaceName(interfaceName);
  }

  @Override
  public Optional<OwnedInstance> lookupOwnedByInterfaceName(String interfaceName) {
    return localRegistry.lookupOwnedByInterfaceName(interfaceName);
  }

  @Override
  public void markUnready(ModuleId owner) {
    localRegistry.markUnready(owner);
    // Deliberately no catalog/wire effect: a same-worker readiness demotion is tolerated as
    // ordinary staleness the receiving endpoint's circuit breaker already handles, not a special
    // case requiring cluster-wide propagation.
  }

  @Override
  public void markReady(ModuleId owner) {
    localRegistry.markReady(owner);
    // Same local-only posture as markUnready above: no catalog/wire effect needed for the
    // reciprocal transition either.
  }

  @Override
  public void remove(ModuleId owner) {
    localRegistry.remove(owner);
    Set<ServiceExport> exports = registeredExportsByOwner.remove(owner);
    if (exports == null) {
      return;
    }
    for (ServiceExport export : exports) {
      controlChannel.accept(new ControlMessage.ServiceUnregistered(owner, export));
    }
  }

  /**
   * See {@link #defaultDenyCrossTenant}'s own javadoc for the policy this layers on top of {@link
   * ServiceExport#permitsTenant}.
   */
  private boolean permitsUnderTenantPolicy(ServiceExport export) {
    if (!defaultDenyCrossTenant || export.allowedTenantIds().isPresent()) {
      return export.permitsTenant(selfTenantId);
    }
    return selfTenantId.isEmpty();
  }

  private Optional<ServiceExport> exportFor(ModuleId owner, Class<?> iface) {
    return exportsOf.apply(owner).stream()
        .filter(export -> export.interfaceName().equals(iface.getName()))
        .findFirst();
  }

  /**
   * Sentinel {@link #effectiveLoad} for an endpoint whose circuit breaker currently excludes it --
   * larger than any real outstanding-request count could ever be, so an open breaker always loses a
   * load comparison against a merely-busy endpoint, and a same-machine tier that's entirely open
   * breakers never wins the tier comparison in {@link #localityAwareCandidates} by default.
   */
  private static final int EXCLUDED_LOAD_SENTINEL = Integer.MAX_VALUE;

  /**
   * Prefers same-machine endpoints (this class's existing locality tier), but spills into the
   * remote tier once every same-machine candidate is already busier than the least-loaded remote
   * one -- otherwise a single lightly-loaded same-machine replica would absorb 100% of traffic
   * forever, even while idle remote replicas sit unused, since the tier choice below used to be a
   * hard cutoff (any same-machine candidate at all excluded every remote one outright). Envoy's own
   * locality-aware load balancing avoids exactly this failure mode via a weighted overprovisioning
   * factor across the full candidate set; this is a much smaller, single-signal version of the same
   * idea, reusing the outstanding-request counts {@link #selector} already tracks for {@link
   * LeastOutstandingRequestsSelector#select} rather than a new capacity model.
   *
   * <p>{@link #effectiveLoad}, not raw outstanding-request count, drives the comparison: an
   * endpoint whose circuit breaker is open fails fast, so its outstanding count sits near zero and
   * raw counts alone make it look like the least-loaded candidate in its tier -- exactly backwards,
   * since it's actually the least *usable* one. Scoring it at {@link #EXCLUDED_LOAD_SENTINEL}
   * instead means an all-open same-machine tier can never out-rank a remote tier that has even one
   * candidate whose breaker still allows requests, so this step routes those lookups into the
   * spilled (same-machine + remote) list, where {@link #selectAllowedCandidate} then does the real
   * per-endpoint breaker filtering. Without this, such a lookup would stay pinned to a same-machine
   * tier that never actually gets used, only reaching a remote endpoint once the panic-mode
   * ejection floor forces every candidate back in regardless of tier.
   */
  private List<ServiceEndpoint> localityAwareCandidates(
      List<ServiceEndpoint> sameMachine, List<ServiceEndpoint> remote) {
    if (sameMachine.isEmpty() || remote.isEmpty()) {
      return sameMachine.isEmpty() ? remote : sameMachine;
    }
    int leastLoadedSameMachine =
        sameMachine.stream().mapToInt(this::effectiveLoad).min().orElseThrow();
    int leastLoadedRemote = remote.stream().mapToInt(this::effectiveLoad).min().orElseThrow();
    if (leastLoadedSameMachine <= leastLoadedRemote
        && leastLoadedSameMachine < EXCLUDED_LOAD_SENTINEL) {
      return sameMachine;
    }
    List<ServiceEndpoint> spilled = new ArrayList<>(sameMachine.size() + remote.size());
    spilled.addAll(sameMachine);
    spilled.addAll(remote);
    return spilled;
  }

  private int effectiveLoad(ServiceEndpoint endpoint) {
    return breakerFor(endpoint).isExcluded()
        ? EXCLUDED_LOAD_SENTINEL
        : selector.outstandingCount(endpoint);
  }

  private ServiceEndpoint selectAllowedCandidate(List<ServiceEndpoint> candidates) {
    if (candidates.isEmpty()) {
      return null;
    }
    List<ServiceEndpoint> healthy = new ArrayList<>();
    int ejectedCount = 0;
    for (ServiceEndpoint endpoint : candidates) {
      if (breakerFor(endpoint).isExcluded()) {
        ejectedCount++;
      } else {
        healthy.add(endpoint);
      }
    }
    if (ejectedCount > 0 && (double) ejectedCount / candidates.size() > maxEjectionPercent) {
      // Panic mode: more than maxEjectionPercent of this lookup's own candidates have an open
      // circuit breaker. Excluding them all would route this lookup nowhere for what might be a
      // transient correlated failure -- admit every candidate back in, bypassing each breaker's
      // own allowRequest() gate (the single-trial HALF_OPEN contract stops mattering once nothing
      // is actually being excluded).
      log.warn(
          "{} of {} candidates for {} have an open circuit breaker, past the {}% ejection floor"
              + " -- admitting all candidates rather than routing nowhere",
          ejectedCount,
          candidates.size(),
          candidates.get(0).export().interfaceName(),
          Math.round(maxEjectionPercent * 100));
      return selector.select(candidates);
    }
    List<ServiceEndpoint> remaining = new ArrayList<>(healthy);
    while (!remaining.isEmpty()) {
      ServiceEndpoint chosen = selector.select(remaining);
      if (breakerFor(chosen).allowRequest()) {
        return chosen;
      }
      // Lost a race for the half-open trial slot (or it flipped back to OPEN concurrently);
      // try the next-best candidate instead of failing the whole lookup outright.
      remaining.remove(chosen);
    }
    return null;
  }

  private CircuitBreaker breakerFor(ServiceEndpoint endpoint) {
    return breakers.computeIfAbsent(
        endpoint,
        key -> new CircuitBreaker(breakerWindowSize, breakerErrorRateThreshold, breakerCooldown));
  }

  private <T> T createProxy(Class<T> iface, ServiceEndpoint endpoint) {
    InvocationHandler handler =
        (proxy, method, args) -> invokeRemote(iface, endpoint, method, args);
    // iface's own classloader, not the fixed worker-wide interfaceLoader: iface may be a type
    // private to one hosted module's own layer (the common case for a module-defined service
    // contract, since gimle-api doesn't exist yet to host such contracts on a shared platform
    // layer) -- Proxy.newProxyInstance's defining loader must be able to see every interface it's
    // handed, and only the interface's own loader is guaranteed to.
    Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface}, handler);
    return iface.cast(proxy);
  }

  private Object invokeRemote(
      Class<?> iface, ServiceEndpoint endpoint, Method method, Object[] args) throws Throwable {
    CircuitBreaker breaker = breakerFor(endpoint);
    selector.begin(endpoint);
    try {
      SocketAddress address = resolveAddress(endpoint);
      String[] paramTypeNames =
          Arrays.stream(method.getParameterTypes()).map(Class::getName).toArray(String[]::new);
      byte[] serializedArgs = ObjectMarshalling.serialize(args == null ? new Object[0] : args);
      FabricFrame.InvokeRequest request =
          new FabricFrame.InvokeRequest(
              ThreadLocalRandom.current().nextLong(),
              captureTrace(),
              iface.getName(),
              method.getName(),
              paramTypeNames,
              serializedArgs);
      FabricFrame response;
      try {
        response = FabricClient.call(address, request);
      } catch (IOException e) {
        breaker.recordFailure();
        throw new UncheckedIOException(
            "fabric call to " + endpoint.node().nodeId() + "/" + endpoint.workerId() + " failed",
            e);
      }
      return switch (response) {
        case FabricFrame.InvokeResponse resp -> {
          breaker.recordSuccess();
          yield ObjectMarshalling.deserialize(resp.serializedReturn());
        }
        case FabricFrame.InvokeError err -> {
          // The remote method itself threw -- proof the endpoint was reachable and answered, not a
          // transport failure. Scoring this against the breaker would open it on a validation
          // exception exactly as readily as on a dead socket; only the IOException branch above
          // (a genuine dispatch failure) should count against it.
          breaker.recordSuccess();
          Object deserialized = ObjectMarshalling.deserialize(err.serializedThrowable());
          if (deserialized instanceof Throwable throwable) {
            throw throwable;
          }
          throw new IllegalStateException(
              "fabric endpoint returned a non-Throwable error payload: " + deserialized);
        }
        case FabricFrame.InvokeRequest ignored ->
            throw new IllegalStateException("fabric endpoint echoed back a request frame");
      };
    } finally {
      selector.end(endpoint);
    }
  }

  private SocketAddress resolveAddress(ServiceEndpoint endpoint) {
    if (endpoint.node().nodeId().equals(selfNode.nodeId()) && endpoint.udsPath().isPresent()) {
      return UnixDomainSocketAddress.of(Path.of(endpoint.udsPath().get()));
    }
    return endpoint.tcpAddress();
  }

  private TraceContext captureTrace() {
    SpanContext context = Span.current().getSpanContext();
    if (!context.isValid()) {
      return new TraceContext(0L, 0L, 0L, (byte) 0, "", "");
    }
    String traceId = context.getTraceId();
    long high = Long.parseUnsignedLong(traceId.substring(0, 16), 16);
    long low = Long.parseUnsignedLong(traceId.substring(16, 32), 16);
    long spanId = Long.parseUnsignedLong(context.getSpanId(), 16);
    byte flags = context.isSampled() ? (byte) 1 : (byte) 0;
    return new TraceContext(
        high, low, spanId, flags, encodeTraceState(context.getTraceState()), encodeBaggage());
  }

  /**
   * W3C {@code tracestate}'s own {@code key1=value1,key2=value2} wire syntax -- {@link TraceState}
   * has no built-in serializer, so this reproduces it directly rather than pulling in a header-
   * parsing dependency for one line's worth of format.
   */
  private static String encodeTraceState(TraceState traceState) {
    StringBuilder sb = new StringBuilder();
    traceState
        .asMap()
        .forEach(
            (key, value) -> {
              if (!sb.isEmpty()) {
                sb.append(',');
              }
              sb.append(key).append('=').append(value);
            });
    return sb.toString();
  }

  /**
   * W3C {@code baggage}'s {@code key1=value1,key2=value2} wire syntax, capturing only each entry's
   * value -- per-entry metadata (the optional {@code ;property=...} suffix the real header allows)
   * is deliberately not modeled, matching this codec's existing "small, hand-rolled, exactly what's
   * needed" posture rather than a general-purpose baggage-header implementation.
   */
  private static String encodeBaggage() {
    Map<String, BaggageEntry> entries = Baggage.current().asMap();
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, BaggageEntry> entry : entries.entrySet()) {
      if (!sb.isEmpty()) {
        sb.append(',');
      }
      sb.append(entry.getKey()).append('=').append(entry.getValue().getValue());
    }
    return sb.toString();
  }
}
