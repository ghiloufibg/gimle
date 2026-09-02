package com.gimle.agent.bifrost;

import com.gimle.agent.networkpolicy.NetworkPolicySnapshot;
import com.gimle.agent.networkpolicy.NetworkPolicySource;
import com.gimle.core.tenant.NetworkPolicyRule;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gimlé's per-node service proxy, the kube-proxy analogue: polls a {@link ServiceSource} on a fixed
 * interval and keeps one loopback {@link ServiceRelay} bound per currently-known service (a {@link
 * ServiceListener} for a TCP Service, a {@link UdpServiceListener} for a UDP one), closing
 * listeners for services that disappeared and binding new ones for services that appeared.
 * Level-triggered like the control plane's own reconcilers -- each poll recomputes the desired
 * listener set from scratch off whatever {@link ServiceSource} reports right now, rather than
 * diffing against a remembered previous poll, so a missed or failed tick self-heals on the next one
 * instead of leaving stale state behind.
 *
 * <p>Also polls a {@link NetworkPolicySource} on the same tick and hands each listener the policy
 * rules currently applying to its service -- see {@link #applicableRules} and {@link
 * ServiceListener#setApplicableRules} for what a listener can and cannot enforce with them.
 */
public final class BifrostProxy implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(BifrostProxy.class);

  private final ServiceSource source;
  private final NetworkPolicySource networkPolicySource;
  private final BifrostSettings settings;
  private final Map<String, ServiceRelay> listeners = new ConcurrentHashMap<>();
  private volatile ScheduledExecutorService scheduler;

  /** Convenience: no {@link NetworkPolicySource} means every service is always unrestricted. */
  public BifrostProxy(ServiceSource source, Duration pollInterval) {
    this(source, NetworkPolicySnapshot::empty, new BifrostSettings(pollInterval));
  }

  public BifrostProxy(
      ServiceSource source, NetworkPolicySource networkPolicySource, Duration pollInterval) {
    this(source, networkPolicySource, new BifrostSettings(pollInterval));
  }

  /**
   * {@link BifrostSettings#exposeOnAllInterfaces} is the NodePort analogue, off by default: {@code
   * true} binds each service's listener on the wildcard address at the service's own port instead
   * of its synthesized per-service loopback ClusterIP, making the service dialable from off this
   * node at {@code <nodeHost>:<servicePort>}. The tradeoff is the same one NodePort itself carries
   * -- one port namespace for the whole node, so two services declaring the same port can't both be
   * exposed; the second bind fails and is logged, exactly like any other bind failure below.
   */
  public BifrostProxy(
      ServiceSource source, NetworkPolicySource networkPolicySource, BifrostSettings settings) {
    this.source = source;
    this.networkPolicySource = networkPolicySource;
    this.settings = settings;
  }

  /** Runs one poll immediately, then schedules subsequent polls every {@code pollInterval}. */
  public synchronized void start() {
    pollOnce();
    ScheduledExecutorService newScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-bifrost-poller").unstarted(r));
    long pollMillis = settings.pollInterval().toMillis();
    newScheduler.scheduleAtFixedRate(
        this::pollSafely, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
    this.scheduler = newScheduler;
  }

  private void pollSafely() {
    try {
      pollOnce();
    } catch (RuntimeException e) {
      log.warn("bifrost poll tick failed unexpectedly: {}", e.getMessage(), e);
    }
  }

  /**
   * One reconciliation pass: fetches the current service list and endpoint set for each, binds a
   * listener for every service not yet bound, refreshes the endpoint set and restriction state of
   * every listener that already exists, and closes listeners for services no longer present. Public
   * and callable directly (not only from {@link #start()}'s own scheduler) so tests can drive
   * reconciliation deterministically instead of racing a wall-clock timer.
   */
  public synchronized void pollOnce() {
    List<ServiceSummary> currentServices;
    try {
      currentServices = source.listServices();
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.warn("bifrost failed to list services: {}", e.getMessage());
      return;
    }
    // Fetched every tick alongside the service list, not once at construction: a policy created
    // or removed after this proxy started must take effect on the very next poll, the same
    // level-triggered posture the service list itself already has. A failed fetch skips this
    // whole tick (services included) rather than proceeding with a stale-but-unknown policy set --
    // proceeding could silently unrestrict a listener that should stay restricted.
    NetworkPolicySnapshot policies;
    try {
      policies = networkPolicySource.fetchPolicies();
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.warn("bifrost failed to poll network policies: {}", e.getMessage());
      return;
    }

    Set<String> currentNames = new LinkedHashSet<>();
    for (ServiceSummary service : currentServices) {
      currentNames.add(service.name());
    }

    for (String existingName : List.copyOf(listeners.keySet())) {
      if (!currentNames.contains(existingName)) {
        ServiceRelay removed = listeners.remove(existingName);
        if (removed != null) {
          removed.close();
          log.info("bifrost closed listener for removed service {}", existingName);
        }
      }
    }

    for (ServiceSummary service : currentServices) {
      String name = service.name();
      Optional<ServiceEndpoints> spec;
      try {
        spec = source.fetchEndpoints(name);
      } catch (IOException | InterruptedException e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        log.warn("bifrost failed to fetch endpoints for service {}: {}", name, e.getMessage());
        continue;
      }
      if (spec.isEmpty()) {
        continue;
      }
      ServiceEndpoints endpoints = spec.get();
      ServiceRelay listener = listeners.get(name);
      if (listener == null) {
        // Wildcard-bound when exposing (the NodePort analogue -- reachable from off-node, one
        // port namespace shared across every exposed service), per-service loopback ClusterIP
        // otherwise.
        InetSocketAddress bindAddress =
            settings.exposeOnAllInterfaces()
                ? new InetSocketAddress((InetAddress) null, endpoints.port())
                : new InetSocketAddress(LoopbackAddressAllocator.allocate(name), endpoints.port());
        try {
          // A UDP Service gets a datagram relay, which deliberately takes no TLS context: there is
          // no handshake to terminate, so a policy-restricted UDP Service can only ever fail
          // closed -- see UdpServiceListener's own javadoc.
          listener =
              endpoints.udp()
                  ? new UdpServiceListener(name, bindAddress, settings.localNodeId())
                  : new ServiceListener(
                      name, bindAddress, settings.localNodeId(), settings.tlsContext());
        } catch (IOException e) {
          log.warn("bifrost failed to bind listener for service {}: {}", name, e.getMessage());
          continue;
        }
        listeners.put(name, listener);
        log.info(
            "bifrost bound {} service {} at {}",
            endpoints.udp() ? "UDP" : "TCP",
            name,
            listener.boundAddress());
      }
      listener.updateEndpoints(endpoints.endpoints());
      listener.setSessionAffinity(endpoints.sessionAffinity());
      listener.setApplicableRules(applicableRules(service, policies));
    }
  }

  /**
   * The currently-held ingress-restricting {@link NetworkPolicyRule}s that apply to {@code service}
   * -- tenant matches, and the rule is either tenant-wide or its {@code deploymentNames} overlaps
   * the service's own. Interface scoping ({@code serviceInterfaceNames}) is deliberately ignored
   * for applicability: an interface-scoped rule names fabric service interfaces, which this
   * opaque-byte proxy cannot resolve, so it treats the rule as covering this traffic rather than
   * assume it doesn't. An egress-only rule never applies to a listener: it constrains what the
   * covered workloads may dial out to, not who may reach them. What {@link ServiceListener#forward}
   * does with a non-empty result depends on whether it can identify callers at all -- see {@link
   * ServiceListener#setApplicableRules}.
   *
   * <p>A tenant whose declared posture denies uncovered traffic contributes a synthesized
   * tenant-wide, allow-nobody rule when no real ingress rule already covers this service, so a
   * closed tenant behaves here exactly as if an explicit deny-all policy had been written for it --
   * rather than staying wide open through this proxy simply because nobody has written that policy
   * yet.
   */
  private static List<NetworkPolicyRule> applicableRules(
      ServiceSummary service, NetworkPolicySnapshot snapshot) {
    if (service.tenantId().isEmpty()) {
      return List.of();
    }
    String tenantId = service.tenantId().get();
    List<NetworkPolicyRule> applicable = new ArrayList<>();
    for (NetworkPolicyRule rule : snapshot.rules()) {
      if (!rule.restrictsIngress()) {
        continue;
      }
      if (!rule.tenantId().equals(tenantId)) {
        continue;
      }
      if (rule.deploymentNames().isEmpty()) {
        applicable.add(rule);
        continue;
      }
      for (String deploymentName : service.deploymentNames()) {
        if (rule.appliesToDeployment(Optional.of(deploymentName))) {
          applicable.add(rule);
          break;
        }
      }
    }
    if (applicable.isEmpty() && snapshot.denyByDefaultTenantIds().contains(tenantId)) {
      applicable.add(new NetworkPolicyRule("gimle:deny-by-default", tenantId, Set.of()));
    }
    return applicable;
  }

  /** The synthesized ClusterIP:port a caller dials for {@code serviceName}, if currently bound. */
  public Optional<InetSocketAddress> boundAddressFor(String serviceName) {
    ServiceRelay listener = listeners.get(serviceName);
    return listener == null ? Optional.empty() : Optional.of(listener.boundAddress());
  }

  @Override
  public synchronized void close() {
    ScheduledExecutorService current = scheduler;
    if (current != null) {
      current.shutdownNow();
    }
    for (ServiceRelay listener : listeners.values()) {
      listener.close();
    }
    listeners.clear();
  }
}
