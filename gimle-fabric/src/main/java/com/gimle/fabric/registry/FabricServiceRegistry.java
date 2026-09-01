package com.gimle.fabric.registry;

import com.gimle.core.exception.GimleClusterException;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ServiceExport;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.ControlMessage;
import com.gimle.fabric.balance.LeastOutstandingRequestsSelector;
import com.gimle.fabric.breaker.CircuitBreaker;
import com.gimle.fabric.catalog.ServiceCatalog;
import com.gimle.fabric.catalog.ServiceEndpoint;
import com.gimle.fabric.cluster.MemberId;
import com.gimle.fabric.trace.TraceContext;
import com.gimle.fabric.transport.FabricClient;
import com.gimle.fabric.transport.FabricConnectException;
import com.gimle.fabric.transport.FabricFrame;
import com.gimle.fabric.transport.ObjectMarshalling;
import com.gimle.fabric.transport.ReflectiveDispatch;
import com.gimle.module.lifecycle.Idempotent;
import com.gimle.module.lifecycle.ServiceRegistry;
import com.gimle.observability.WorkerMetrics;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageEntry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.SocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
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
  private final Optional<WorkerMetrics> metrics;

  /**
   * Cap on distinct tracked endpoints in {@link #breakers}, so long-lived-process endpoint churn
   * (worker respawns minting new ports) can't grow it without bound. Not a real LRU -- just
   * arbitrary-entry eviction once the cap is exceeded via {@link #breakerFor}, cheap and good
   * enough since a mistakenly evicted still-live endpoint simply gets a fresh circuit breaker on
   * its next lookup.
   */
  private static final int MAX_BREAKER_ENTRIES = 2000;

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
   *
   * <p>Back-compat: defaults {@code metrics} to {@link Optional#empty()} -- see the 14-arg
   * constructor below.
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
        defaultDenyCrossTenant,
        Optional.empty());
  }

  /**
   * {@code metrics} lets this registry record the client-side half of a cross-worker fabric call's
   * request rate/latency/error counters -- the {@link WorkerMetrics} counterpart of what {@code
   * FabricServer} already records for the server (inbound-dispatch) side, tagged by interface name
   * rather than {@link ModuleId} since a lookup caller's own module identity isn't threaded through
   * {@link #lookup}/{@link #invokeByName}. Absent (every other constructor) means a
   * same-worker-only test or a worker not wired with a real {@code WorkerMetrics} instance records
   * nothing, exactly today's unchanged behavior.
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
      boolean defaultDenyCrossTenant,
      Optional<WorkerMetrics> metrics) {
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
    this.metrics = metrics;
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
  public <T> Optional<T> lookup(Class<T> iface) {
    Optional<T> local = localRegistry.lookup(iface);
    if (local.isPresent()) {
      return local;
    }

    if (catalog.endpointsForInterface(iface.getName()).isEmpty()) {
      throw GimleClusterException.noExportingMember(iface.getName());
    }

    EndpointChooser chooser = chooserByInterface(iface.getName());
    ServiceEndpoint chosen = chooser.choose(Set.of());
    if (chosen == null) {
      return Optional.empty();
    }
    return Optional.of(castProxy(createProxy(iface, chosen, chooser)));
  }

  /**
   * Picks an endpoint for one attempt of a call, given the endpoints earlier attempts already tried
   * and failed against. Every attempt re-reads the catalog and re-runs the full
   * tier/version/breaker selection rather than working from a list captured at lookup time -- a
   * failover is only worth making against whatever is actually reachable now, and the endpoint that
   * just failed has by then scored a breaker failure that selection should account for.
   */
  @FunctionalInterface
  private interface EndpointChooser {
    ServiceEndpoint choose(Set<ServiceEndpoint> alreadyTried);
  }

  private EndpointChooser chooserByInterface(String interfaceName) {
    return tried ->
        selectAllowedCandidate(
            tieredCandidates(catalog.endpointsForInterface(interfaceName), true, tried));
  }

  private EndpointChooser chooserByNameAndVersion(String interfaceName, int majorVersion) {
    return tried ->
        selectAllowedCandidate(
            tieredCandidates(
                endpointsForNameAndVersion(interfaceName, majorVersion), false, tried));
  }

  /**
   * Splits {@code allKnown} into the same-machine and remote tiers, dropping this worker's own
   * entry, anything this tenant may not consume, and anything {@code alreadyTried} names, then
   * applies the locality preference. {@code applyVersionCutover} narrows to a single export version
   * first -- wanted for the {@code Class<T>} path, which has no version to filter by up front, and
   * redundant for the name-keyed path, which already filtered to one major version.
   */
  private List<ServiceEndpoint> tieredCandidates(
      List<ServiceEndpoint> allKnown,
      boolean applyVersionCutover,
      Set<ServiceEndpoint> alreadyTried) {
    List<ServiceEndpoint> sameMachine = new ArrayList<>();
    List<ServiceEndpoint> remote = new ArrayList<>();
    for (ServiceEndpoint endpoint : allKnown) {
      if (alreadyTried.contains(endpoint)) {
        continue; // an earlier attempt of this same call already failed against it
      }
      boolean isSameMachine = endpoint.node().nodeId().equals(selfNode.nodeId());
      if (isSameMachine && endpoint.workerId().equals(workerId)) {
        continue; // this worker's own entry: already covered by the local-registry tier
      }
      if (!permitsUnderTenantPolicy(endpoint.export())) {
        continue; // this tenant isn't on the export's allow-list
      }
      // Breaker state is deliberately not filtered here -- selectAllowedCandidate applies it
      // against this tier's own candidate count, so the panic-mode floor below has an accurate
      // denominator instead of endpoints having already vanished before it can see them.
      (isSameMachine ? sameMachine : remote).add(endpoint);
    }
    if (applyVersionCutover) {
      Version cutoverVersion = highestVersionWithAnAvailableCandidate(sameMachine, remote);
      if (cutoverVersion != null) {
        sameMachine = filterByVersion(sameMachine, cutoverVersion);
        remote = filterByVersion(remote, cutoverVersion);
      }
    }
    return localityAwareCandidates(sameMachine, remote);
  }

  /**
   * The cross-worker counterpart to {@code SimpleServiceRegistry.selectEntry}'s same-worker
   * cutover: during a hot redeploy, {@code sameMachine}/{@code remote} can carry endpoints for both
   * the old and new version of one interface at once, deliberately. A blended selection across both
   * would route a fraction of fresh lookups to the version being drained, so cutover here must be
   * atomic per lookup too -- prefer the highest {@link Version} that currently has at least one
   * candidate its own {@link CircuitBreaker} doesn't exclude, falling back to the next highest
   * version only when the top one has none (e.g. every endpoint at that version has an open
   * breaker). "Available" is deliberately not "breaker-excluded candidates removed for good" --
   * {@link #selectAllowedCandidate}'s own panic-mode ejection floor still gets the final say once
   * one version's pool is chosen, so a correlated failure across every candidate at every version
   * still lets that floor admit the newest version's endpoints back in rather than silently routing
   * to a stale one.
   *
   * <p>Returns {@code null} when {@code sameMachine} and {@code remote} are both empty (nothing to
   * narrow), or the single highest version present when no version has an available candidate --
   * narrowing to that version still lets {@link #selectAllowedCandidate}'s panic-mode floor pick a
   * candidate rather than leaving every version's endpoints in the pool at once.
   */
  private Version highestVersionWithAnAvailableCandidate(
      List<ServiceEndpoint> sameMachine, List<ServiceEndpoint> remote) {
    List<ServiceEndpoint> combined = new ArrayList<>(sameMachine.size() + remote.size());
    combined.addAll(sameMachine);
    combined.addAll(remote);
    if (combined.isEmpty()) {
      return null;
    }
    List<Version> versionsDescending =
        combined.stream()
            .map(endpoint -> endpoint.export().version())
            .distinct()
            .sorted(Comparator.reverseOrder())
            .toList();
    for (Version version : versionsDescending) {
      boolean anyAvailable =
          combined.stream()
              .filter(endpoint -> endpoint.export().version().equals(version))
              .anyMatch(endpoint -> !breakerFor(endpoint).isExcluded());
      if (anyAvailable) {
        return version;
      }
    }
    return versionsDescending.get(0);
  }

  private static List<ServiceEndpoint> filterByVersion(
      List<ServiceEndpoint> endpoints, Version version) {
    return endpoints.stream().filter(e -> e.export().version().equals(version)).toList();
  }

  /**
   * The one unchecked cast behind {@link #lookup}'s dynamic {@link Proxy} return: {@code
   * createProxy} builds a {@code Proxy} instance for exactly the interface {@code T} the caller
   * asked for, but the proxy machinery itself only ever hands back a plain {@code Object} -- a
   * typesafe heterogeneous container pattern with no way to encode the witness at the call site
   * itself, so the cast is isolated here rather than spanning the surrounding method.
   */
  @SuppressWarnings("unchecked")
  private static <T> T castProxy(Object proxy) {
    return (T) proxy;
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

  /**
   * The cross-tier counterpart to {@link #lookup(Class)} for a caller that only has a service's
   * identity as plain runtime strings -- an interface name, its major version, and a method
   * signature -- not a compile-time {@code Class<T>} a dynamic {@link Proxy} could reflect against.
   * A route-config-driven caller (the gateway module) is the motivating case: its routes name a
   * target service at runtime, never at compile time.
   *
   * <p>Tries the same tiers {@link #lookup(Class)} does, in the same order: (1) same-worker, served
   * by a direct reflective invoke against the already-in-hand instance, no wire hop at all; (2)/(3)
   * same-machine and remote, selected exactly the way {@link #lookup(Class)} selects them (locality
   * preference with load-aware spillover, least-outstanding-requests, circuit breaking, the same
   * {@link #permitsUnderTenantPolicy} tenant check) and dispatched over the identical wire
   * mechanics {@link #invokeRemote} uses, just keyed by name instead of a resolved {@link Method}.
   * Unlike {@link #lookup(Class)} -- which has no export version to filter by, since a {@code
   * Class} carries only an interface name -- this can and does narrow catalog candidates to
   * exporters whose {@code ServiceExport}'s major version matches {@code majorVersion}; the
   * same-worker tier is not narrowed this way, since {@code localRegistry} tracks no export-version
   * metadata of its own.
   *
   * <p>Returns {@link Optional#empty()} both when nothing anywhere exports {@code interfaceName} at
   * {@code majorVersion} (consistent with {@link #lookupByInterfaceName}'s own "nothing registered"
   * convention -- a bad route name is reported the same way as "not registered yet," not as an
   * exception) and when a found method's real return value is legitimately {@code null} or {@code
   * void} -- this signature can't distinguish those two outcomes from the return value alone. An
   * unresolvable method name or parameter-type list, by contrast, is a genuine failure: {@code
   * NoSuchMethodException}/{@code ClassNotFoundException} propagate rather than being swallowed
   * into an empty result or matched against the wrong overload.
   */
  @Override
  public Optional<Object> invokeByName(
      String interfaceName,
      int majorVersion,
      String methodName,
      String[] paramTypeNames,
      Object[] args)
      throws Throwable {
    Optional<Object> localInstance = localRegistry.lookupByInterfaceName(interfaceName);
    if (localInstance.isPresent()) {
      return invokeLocalByName(
          localInstance.get(), interfaceName, methodName, paramTypeNames, args);
    }

    if (endpointsForNameAndVersion(interfaceName, majorVersion).isEmpty()) {
      return Optional.empty();
    }

    EndpointChooser chooser = chooserByNameAndVersion(interfaceName, majorVersion);
    ServiceEndpoint chosen = chooser.choose(Set.of());
    if (chosen == null) {
      return Optional.empty();
    }
    // Never retried after the request has been sent: a name-keyed call has no resolved Method to
    // read an @Idempotent declaration off, and "unknown" has to mean "not safe to repeat". A
    // connect-time failure still fails over, since nothing ran for a retry to repeat.
    RemoteCall call =
        new RemoteCall(
            interfaceName, methodName, paramTypeNames, args, interfaceLoader, false, chooser);
    return Optional.ofNullable(invokeOverWire(call, chosen));
  }

  private List<ServiceEndpoint> endpointsForNameAndVersion(String interfaceName, int majorVersion) {
    return catalog.endpointsForInterface(interfaceName).stream()
        .filter(endpoint -> endpoint.export().version().major() == majorVersion)
        .toList();
  }

  /**
   * Same-worker half of {@link #invokeByName}: {@code instance} is already the real, live object
   * {@code localRegistry} handed back, so this is a plain reflective invoke -- no serialization, no
   * wire hop -- mirroring what {@code FabricServer#invokeLocally} does for an *inbound* call, just
   * from the caller's side instead of the dispatcher's.
   */
  private Optional<Object> invokeLocalByName(
      Object instance,
      String interfaceName,
      String methodName,
      String[] paramTypeNames,
      Object[] args)
      throws Throwable {
    Class<?> iface = ReflectiveDispatch.findInterface(instance.getClass(), interfaceName);
    Class<?>[] paramTypes =
        ReflectiveDispatch.resolveParamTypes(paramTypeNames, iface.getClassLoader());
    Method method = iface.getMethod(methodName, paramTypes);
    try {
      return Optional.ofNullable(method.invoke(instance, args == null ? new Object[0] : args));
    } catch (InvocationTargetException e) {
      throw e.getCause() != null ? e.getCause() : e;
    }
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
    CircuitBreaker breaker = breakers.computeIfAbsent(endpoint, this::newBreaker);
    if (breakers.size() > MAX_BREAKER_ENTRIES) {
      Iterator<ServiceEndpoint> it = breakers.keySet().iterator();
      if (it.hasNext()) {
        ServiceEndpoint evicted = it.next();
        it.remove();
        metrics.ifPresent(
            m -> m.evictCircuitBreaker(evicted.export().interfaceName(), endpointTag(evicted)));
      }
    }
    return breaker;
  }

  private CircuitBreaker newBreaker(ServiceEndpoint endpoint) {
    // Published immediately, not only once something transitions: an operator asking "is a breaker
    // why traffic isn't reaching this endpoint" needs to see a closed breaker's own gauge, not an
    // absent meter they have to interpret.
    metrics.ifPresent(
        m ->
            m.recordCircuitBreakerState(
                endpoint.export().interfaceName(),
                endpointTag(endpoint),
                stateLevel(CircuitBreaker.State.CLOSED)));
    return new CircuitBreaker(
        breakerWindowSize,
        breakerErrorRateThreshold,
        breakerCooldown,
        Clock.systemUTC(),
        (from, to) -> onBreakerTransition(endpoint, from, to));
  }

  /**
   * Every per-endpoint breaker transition, logged and metered. Before this, the only externally
   * visible trace a breaker ever left was the cluster-wide panic-mode warning in {@link
   * #selectAllowedCandidate} -- one endpoint's breaker opening produced no log line, no meter, and
   * no queryable state, so "traffic isn't reaching instance X" was indistinguishable from a catalog
   * that never learned about X or an instance that never became ready.
   */
  private void onBreakerTransition(
      ServiceEndpoint endpoint, CircuitBreaker.State from, CircuitBreaker.State to) {
    String interfaceName = endpoint.export().interfaceName();
    String target = endpointTag(endpoint);
    switch (to) {
      case OPEN ->
          log.warn(
              "circuit breaker for {} at {} opened ({} -> OPEN): ejecting it from candidate"
                  + " selection until its cooldown elapses",
              interfaceName,
              target,
              from);
      case HALF_OPEN ->
          log.info(
              "circuit breaker for {} at {} half-opened after its cooldown: admitting a single"
                  + " trial call",
              interfaceName,
              target);
      case CLOSED ->
          log.info(
              "circuit breaker for {} at {} closed ({} -> CLOSED): routing to it normally again",
              interfaceName,
              target,
              from);
    }
    metrics.ifPresent(
        m -> m.recordCircuitBreakerTransition(interfaceName, target, to.name(), stateLevel(to)));
  }

  /**
   * The numeric encoding behind the {@code gimle.fabric.circuitbreaker.state} gauge, ordered by how
   * bad the state is so a max-over-endpoints query answers "is anything ejected right now".
   */
  private static long stateLevel(CircuitBreaker.State state) {
    return switch (state) {
      case CLOSED -> 0L;
      case HALF_OPEN -> 1L;
      case OPEN -> 2L;
    };
  }

  private static String endpointTag(ServiceEndpoint endpoint) {
    return endpoint.node().nodeId() + "/" + endpoint.workerId();
  }

  private <T> T createProxy(Class<T> iface, ServiceEndpoint endpoint, EndpointChooser chooser) {
    InvocationHandler handler =
        (proxy, method, args) -> invokeRemote(iface, endpoint, chooser, method, args);
    // iface's own classloader, not the fixed worker-wide interfaceLoader: iface may be a type
    // private to one hosted module's own layer (the common case for a module-defined service
    // contract, since gimle-api doesn't exist yet to host such contracts on a shared platform
    // layer) -- Proxy.newProxyInstance's defining loader must be able to see every interface it's
    // handed, and only the interface's own loader is guaranteed to.
    Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface}, handler);
    return iface.cast(proxy);
  }

  private Object invokeRemote(
      Class<?> iface,
      ServiceEndpoint endpoint,
      EndpointChooser chooser,
      Method method,
      Object[] args)
      throws Throwable {
    String[] paramTypeNames =
        Arrays.stream(method.getParameterTypes()).map(Class::getName).toArray(String[]::new);
    // iface's own classloader, the same one createProxy already trusts to see iface itself: a
    // return value or a thrown exception is just as likely to be a type private to the provider
    // module's own layer as the interface is (see ObjectMarshalling.deserialize's own rationale),
    // and iface's loader -- not the fixed worker-wide interfaceLoader -- is the one guaranteed to
    // resolve it, since the caller module bundles its own literal copy of that contract's types.
    RemoteCall call =
        new RemoteCall(
            iface.getName(),
            method.getName(),
            paramTypeNames,
            args,
            iface.getClassLoader(),
            declaresIdempotent(method),
            chooser);
    return invokeOverWire(call, endpoint);
  }

  /**
   * Matched by annotation type <em>name</em> rather than by {@code isAnnotationPresent}: a hosted
   * module can carry its own copy of the platform's annotation classes inside its own layer, in
   * which case the {@code Idempotent} on its interface is a different {@code Class} object from the
   * one this code holds and an identity-based check would silently miss every declaration.
   */
  private static boolean declaresIdempotent(Method method) {
    for (Annotation annotation : method.getAnnotations()) {
      if (annotation.annotationType().getName().equals(Idempotent.class.getName())) {
        return true;
      }
    }
    return false;
  }

  /**
   * One logical cross-hop call, independent of which endpoint any individual attempt of it goes to.
   * {@code returnClassLoader} resolves the response payload's classes: {@link #invokeRemote} passes
   * the calling interface's own loader (the one guaranteed to see its contract's types); {@link
   * #invokeByName} has no {@link Class} to draw one from, so it falls back to the fixed worker-wide
   * {@code interfaceLoader}. {@code idempotent} is the method author's own declaration that
   * repeating this call is safe; {@code chooser} supplies a different endpoint when an attempt
   * fails in a way that permits another one.
   */
  private record RemoteCall(
      String interfaceName,
      String methodName,
      String[] paramTypeNames,
      Object[] args,
      ClassLoader returnClassLoader,
      boolean idempotent,
      EndpointChooser chooser) {}

  /**
   * Bound on how many endpoints one logical call may be attempted against. Three is enough for the
   * case that motivates retrying at all -- the chosen endpoint's worker died between the catalog
   * entry being gossiped and the call being placed, and one or two other replicas are live -- while
   * keeping a caller's worst-case latency a small multiple of {@link FabricClient#DEFAULT_TIMEOUT}
   * rather than proportional to how many stale endpoints the catalog happens to hold.
   */
  private static final int MAX_CALL_ATTEMPTS = 3;

  /**
   * The actual wire mechanics behind both {@link #invokeRemote} (a {@link Method}-driven caller,
   * reduced to plain strings) and {@link #invokeByName}'s own same-machine/remote tiers (which
   * never had a {@link Method} to begin with) -- sends one {@code FabricFrame.InvokeRequest},
   * retrying against a different endpoint where that is safe, and applies the identical
   * error-unwrapping rules to whatever comes back regardless of which caller shape produced the
   * request.
   *
   * <p>Wraps the whole call in a fresh {@link SpanKind#CLIENT} span -- the counterpart of {@code
   * FabricServer#startChildSpanContext}'s {@code SERVER} span on the receiving end -- made current
   * <em>before</em> {@link #captureTrace} runs, so the trace/span ids that travel over the wire
   * identify this client span, not whatever ambient span (if any) was already active. Without this,
   * a caller with no ambient span captures the all-zero "no active span" marker and the callee
   * always starts a fresh root, even though a real call just happened; with it, the callee's {@code
   * SERVER} span is always parented under this one, real caller activity or not. Retries stay
   * inside that one span, since they are attempts at one logical call, not separate calls.
   */
  private Object invokeOverWire(RemoteCall call, ServiceEndpoint firstEndpoint) throws Throwable {
    Span span =
        GlobalOpenTelemetry.getTracer("com.gimle.fabric")
            .spanBuilder(call.interfaceName() + "#" + call.methodName())
            .setSpanKind(SpanKind.CLIENT)
            .startSpan();
    long startNanos = System.nanoTime();
    boolean error = false;
    try (Scope scope = span.makeCurrent()) {
      Object result = attemptUntilExhausted(call, firstEndpoint);
      span.setStatus(StatusCode.OK);
      return result;
    } catch (Throwable t) {
      error = true;
      span.recordException(t);
      span.setStatus(StatusCode.ERROR);
      throw t;
    } finally {
      span.end();
      long elapsedNanos = System.nanoTime() - startNanos;
      boolean recordedError = error;
      metrics.ifPresent(
          m ->
              m.recordClientRequest(
                  call.interfaceName(), Duration.ofNanos(elapsedNanos), recordedError));
    }
  }

  /**
   * The retry boundary, and the one place the difference between the two kinds of transport failure
   * is acted on.
   *
   * <p>A {@link FabricConnectException} means the connection was never established, so the target
   * provably never saw this request: retrying it against a different endpoint cannot duplicate
   * anything, whatever the method does. Any other {@link IOException} means the request was written
   * and its outcome is unknown -- the target may have executed it and the answer been lost -- so
   * only a method whose author declared it {@link Idempotent} is retried. Both kinds fail over to a
   * <em>different</em> endpoint rather than re-dialing the one that just failed, which is also why
   * the whole loop shares one {@code correlationId}: a target that did execute an earlier attempt
   * answers the retry from its own duplicate-suppression window instead of running it twice.
   */
  private Object attemptUntilExhausted(RemoteCall call, ServiceEndpoint firstEndpoint)
      throws Throwable {
    FabricFrame.InvokeRequest request =
        new FabricFrame.InvokeRequest(
            ThreadLocalRandom.current().nextLong(),
            captureTrace(),
            call.interfaceName(),
            call.methodName(),
            call.paramTypeNames(),
            ObjectMarshalling.serialize(call.args() == null ? new Object[0] : call.args()),
            selfTenantId);

    Set<ServiceEndpoint> tried = new LinkedHashSet<>();
    ServiceEndpoint endpoint = firstEndpoint;
    ServiceEndpoint lastEndpoint = firstEndpoint;
    IOException lastFailure = null;
    for (int attempt = 1; attempt <= MAX_CALL_ATTEMPTS && endpoint != null; attempt++) {
      tried.add(endpoint);
      lastEndpoint = endpoint;
      FabricFrame response;
      try {
        response = attemptOnce(endpoint, request);
      } catch (FabricConnectException e) {
        lastFailure = e;
        endpoint = call.chooser().choose(tried);
        continue;
      } catch (IOException e) {
        lastFailure = e;
        if (!call.idempotent()) {
          break;
        }
        endpoint = call.chooser().choose(tried);
        continue;
      }
      // Decoded outside the catch blocks above on purpose: the callee's own exception is rethrown
      // from here and can perfectly well be an IOException itself, which must never be mistaken for
      // a transport failure and retried.
      return decodeResponse(response, call.returnClassLoader());
    }
    throw new UncheckedIOException(
        "fabric call to " + endpointTag(lastEndpoint) + " failed", lastFailure);
  }

  /**
   * One attempt against one endpoint: load accounting, breaker scoring, and the wire round trip.
   */
  private FabricFrame attemptOnce(ServiceEndpoint endpoint, FabricFrame.InvokeRequest request)
      throws IOException {
    CircuitBreaker breaker = breakerFor(endpoint);
    selector.begin(endpoint);
    try {
      FabricFrame response = FabricClient.call(resolveAddress(endpoint), request);
      // Any answered frame counts as a success, an InvokeError included: the remote method throwing
      // is proof the endpoint was reachable and dispatched, not a transport failure. Scoring it
      // against the breaker would open it on a validation exception exactly as readily as on a dead
      // socket.
      breaker.recordSuccess();
      return response;
    } catch (IOException e) {
      breaker.recordFailure();
      throw e;
    } finally {
      selector.end(endpoint);
    }
  }

  private static Object decodeResponse(FabricFrame response, ClassLoader returnClassLoader)
      throws Throwable {
    return switch (response) {
      case FabricFrame.InvokeResponse resp ->
          ObjectMarshalling.deserialize(resp.serializedReturn(), returnClassLoader);
      case FabricFrame.InvokeError err -> {
        Object deserialized =
            ObjectMarshalling.deserialize(err.serializedThrowable(), returnClassLoader);
        if (deserialized instanceof Throwable throwable) {
          throw throwable;
        }
        throw new IllegalStateException(
            "fabric endpoint returned a non-Throwable error payload: " + deserialized);
      }
      case FabricFrame.InvokeRequest ignored ->
          throw new IllegalStateException("fabric endpoint echoed back a request frame");
    };
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
