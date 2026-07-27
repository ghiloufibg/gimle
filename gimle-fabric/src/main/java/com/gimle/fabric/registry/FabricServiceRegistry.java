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
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
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
      if (!endpoint.export().permitsTenant(selfTenantId)) {
        continue; // this tenant isn't on the export's allow-list
      }
      if (breakerFor(endpoint).isExcluded()) {
        continue;
      }
      (isSameMachine ? sameMachine : remote).add(endpoint);
    }

    List<ServiceEndpoint> candidates = !sameMachine.isEmpty() ? sameMachine : remote;
    ServiceEndpoint chosen = selectAllowedCandidate(candidates);
    if (chosen == null) {
      return Optional.empty();
    }
    return Optional.of((T) createProxy(iface, chosen));
  }

  @Override
  public void markUnready(ModuleId owner) {
    localRegistry.markUnready(owner);
    // Deliberately no catalog/wire effect: a same-worker readiness demotion is tolerated as
    // ordinary staleness the receiving endpoint's circuit breaker already handles, not a special
    // case requiring cluster-wide propagation.
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

  private Optional<ServiceExport> exportFor(ModuleId owner, Class<?> iface) {
    return exportsOf.apply(owner).stream()
        .filter(export -> export.interfaceName().equals(iface.getName()))
        .findFirst();
  }

  private ServiceEndpoint selectAllowedCandidate(List<ServiceEndpoint> candidates) {
    List<ServiceEndpoint> remaining = new ArrayList<>(candidates);
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
    Object proxy = Proxy.newProxyInstance(interfaceLoader, new Class<?>[] {iface}, handler);
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
          breaker.recordFailure();
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
      return new TraceContext(0L, 0L, 0L, (byte) 0);
    }
    String traceId = context.getTraceId();
    long high = Long.parseUnsignedLong(traceId.substring(0, 16), 16);
    long low = Long.parseUnsignedLong(traceId.substring(16, 32), 16);
    long spanId = Long.parseUnsignedLong(context.getSpanId(), 16);
    byte flags = context.isSampled() ? (byte) 1 : (byte) 0;
    return new TraceContext(high, low, spanId, flags);
  }
}
