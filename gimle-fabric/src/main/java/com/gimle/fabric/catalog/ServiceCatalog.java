package com.gimle.fabric.catalog;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ServiceExport;
import com.gimle.fabric.cluster.MemberId;
import com.gimle.fabric.cluster.PiggybackExtension;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The cluster-wide service catalog: keyed by {@link ServiceExport}, merged from gossip piggyback
 * deltas, last-writer-wins by {@code (node, workerId, moduleId)} within one export's bucket using
 * each entry's own incarnation-style version counter -- the same conflict-resolution shape SWIM
 * already uses for membership. One instance runs per node agent, attached to that node's {@code
 * GossipMember} via {@link PiggybackExtension} so catalog data rides the exact same infection-style
 * dissemination as membership, never a second mechanism and never routed through the control plane.
 */
public final class ServiceCatalog implements PiggybackExtension {

  private static final Logger log = LoggerFactory.getLogger(ServiceCatalog.class);
  private static final int DEFAULT_MAX_PIGGYBACK_DELTAS = 8;
  private static final int MAX_RETAINED_RECENT_DELTAS = 256;
  private static final java.util.Comparator<ServiceEndpoint> ENDPOINT_ORDER =
      java.util.Comparator.<ServiceEndpoint, String>comparing(e -> e.node().nodeId())
          .thenComparing(ServiceEndpoint::workerId);

  private final int maxPiggybackDeltas;
  private final Map<ServiceExport, Map<InstanceKey, VersionedEntry>> byExport =
      new ConcurrentHashMap<>();
  private final Deque<CatalogDelta> recentDeltas = new ArrayDeque<>();
  private final AtomicLong localVersionCounter = new AtomicLong();
  private final List<Consumer<CatalogDelta>> listeners = new CopyOnWriteArrayList<>();

  public ServiceCatalog() {
    this(DEFAULT_MAX_PIGGYBACK_DELTAS);
  }

  public ServiceCatalog(int maxPiggybackDeltas) {
    if (maxPiggybackDeltas < 1) {
      throw new IllegalArgumentException("maxPiggybackDeltas must be at least 1");
    }
    this.maxPiggybackDeltas = maxPiggybackDeltas;
  }

  /**
   * Called the moment a locally-supervised worker reports a new export, via {@code
   * ControlMessage.ServiceRegistered}.
   */
  public void localRegister(
      MemberId node,
      String workerId,
      ModuleId moduleId,
      ServiceExport export,
      Optional<String> udsPath,
      InetSocketAddress tcpAddress) {
    apply(
        new CatalogDelta(
            export,
            node.nodeId(),
            workerId,
            moduleId,
            localVersionCounter.incrementAndGet(),
            true,
            node,
            udsPath,
            tcpAddress));
  }

  /** Called on {@code ControlMessage.ServiceUnregistered}. */
  public void localUnregister(
      MemberId node, String workerId, ModuleId moduleId, ServiceExport export) {
    apply(
        new CatalogDelta(
            export,
            node.nodeId(),
            workerId,
            moduleId,
            localVersionCounter.incrementAndGet(),
            false,
            node,
            Optional.empty(),
            new InetSocketAddress(0)));
  }

  /**
   * Every currently-present (non-tombstoned) endpoint advertising {@code export}, across every
   * node/worker in the cluster this node has learned about. Ordered deterministically by {@code
   * (nodeId, workerId)} -- callers doing least-outstanding-requests selection over this list rely
   * on stable ordering for round-robin tie-breaking; the backing map itself is a {@code
   * ConcurrentHashMap} with no ordering guarantee of its own.
   */
  public List<ServiceEndpoint> endpointsFor(ServiceExport export) {
    Map<InstanceKey, VersionedEntry> inner = byExport.get(export);
    if (inner == null) {
      return List.of();
    }
    return inner.values().stream()
        .filter(VersionedEntry::present)
        .map(VersionedEntry::endpoint)
        .sorted(ENDPOINT_ORDER)
        .toList();
  }

  public boolean hasAnyKnownExporter(ServiceExport export) {
    return !endpointsFor(export).isEmpty();
  }

  /**
   * Registers a callback invoked once for every delta that actually changes this catalog's state (a
   * fresh registration/unregistration or a genuinely newer version merged from gossip) -- never for
   * a stale/duplicate delta the merge rule ignored. A node agent uses this to relay a newly-learned
   * delta down to its own supervised workers, regardless of whether the delta originated from a
   * locally-registered worker or from gossip about a remote one.
   */
  public void onDelta(Consumer<CatalogDelta> listener) {
    listeners.add(listener);
  }

  /**
   * Applies a delta relayed from this node's agent over the control channel ({@code
   * ControlMessage.CatalogUpdate}, in the agent-to-worker direction) into a worker's own locally
   * cached view. {@code nodeId} is wrapped in a placeholder {@link MemberId} whose gossip address
   * is never dialed by anything downstream of this cache -- routing always uses {@code udsPath}/
   * {@code tcpAddress} directly, never the member id's own address, so a real gossip address isn't
   * needed here.
   */
  public void applyExternalUpdate(
      String nodeId,
      String workerId,
      ModuleId moduleId,
      ServiceExport export,
      long version,
      boolean present,
      Optional<String> udsPath,
      InetSocketAddress tcpAddress) {
    apply(
        new CatalogDelta(
            export,
            nodeId,
            workerId,
            moduleId,
            version,
            present,
            new MemberId(nodeId, new InetSocketAddress(0)),
            udsPath,
            tcpAddress));
  }

  /**
   * Every currently-present endpoint whose export's interface name matches, regardless of version
   * -- needed because {@code ServiceRegistry#lookup(Class)} never carries a version to narrow by,
   * so a cross-tier fabric lookup can't disambiguate by version at the call site either.
   */
  public List<ServiceEndpoint> endpointsForInterface(String interfaceName) {
    List<ServiceEndpoint> result = new ArrayList<>();
    for (Map.Entry<ServiceExport, Map<InstanceKey, VersionedEntry>> entry : byExport.entrySet()) {
      if (entry.getKey().interfaceName().equals(interfaceName)) {
        for (VersionedEntry versioned : entry.getValue().values()) {
          if (versioned.present()) {
            result.add(versioned.endpoint());
          }
        }
      }
    }
    return result.stream().sorted(ENDPOINT_ORDER).toList();
  }

  /**
   * Every currently-present entry across every export, as a {@link CatalogDelta} carrying its own
   * real version -- used to sync a worker that's only just connected (Hello) with everything this
   * agent already knows, since the gossip-driven {@link #onDelta} relay only fires for a delta
   * applied *after* the listener was registered: a delta this agent learned before that worker ever
   * connected would otherwise never reach it (nothing was listening yet when it happened).
   */
  public List<CatalogDelta> allPresentDeltas() {
    List<CatalogDelta> result = new ArrayList<>();
    for (Map.Entry<ServiceExport, Map<InstanceKey, VersionedEntry>> exportEntry :
        byExport.entrySet()) {
      for (Map.Entry<InstanceKey, VersionedEntry> instanceEntry :
          exportEntry.getValue().entrySet()) {
        VersionedEntry versioned = instanceEntry.getValue();
        if (!versioned.present()) {
          continue;
        }
        InstanceKey key = instanceEntry.getKey();
        ServiceEndpoint endpoint = versioned.endpoint();
        result.add(
            new CatalogDelta(
                exportEntry.getKey(),
                key.nodeId(),
                key.workerId(),
                key.moduleId(),
                versioned.version(),
                true,
                endpoint.node(),
                endpoint.udsPath(),
                endpoint.tcpAddress()));
      }
    }
    return result;
  }

  // ---- PiggybackExtension: this catalog's slot on the gossip channel ----

  @Override
  public byte[] currentPayload() {
    List<CatalogDelta> snapshot;
    synchronized (recentDeltas) {
      snapshot = recentDeltas.stream().limit(maxPiggybackDeltas).toList();
    }
    return ServiceCatalogCodec.encode(snapshot);
  }

  @Override
  public void onReceived(byte[] payload) {
    for (CatalogDelta delta : ServiceCatalogCodec.decode(payload)) {
      apply(delta);
    }
  }

  // ---- merge ----

  private void apply(CatalogDelta delta) {
    Map<InstanceKey, VersionedEntry> inner =
        byExport.computeIfAbsent(delta.export(), export -> new ConcurrentHashMap<>());
    InstanceKey key = new InstanceKey(delta.nodeId(), delta.workerId(), delta.moduleId());
    boolean[] applied = {false};
    inner.compute(
        key,
        (k, existing) -> {
          if (existing != null && existing.version() >= delta.version()) {
            return existing; // stale or duplicate delta; last-writer-wins keeps the current entry
          }
          applied[0] = true;
          ServiceEndpoint endpoint =
              new ServiceEndpoint(
                  delta.node(),
                  delta.workerId(),
                  delta.moduleId(),
                  delta.export(),
                  delta.present(),
                  delta.udsPath(),
                  delta.tcpAddress());
          return new VersionedEntry(endpoint, delta.version(), delta.present());
        });
    if (applied[0]) {
      log.debug(
          "catalog applied a delta: export={} node={} worker={} version={} present={}",
          delta.export(),
          delta.nodeId(),
          delta.workerId(),
          delta.version(),
          delta.present());
      recordRecent(delta);
      listeners.forEach(listener -> listener.accept(delta));
    }
  }

  private void recordRecent(CatalogDelta delta) {
    synchronized (recentDeltas) {
      recentDeltas.removeIf(
          d ->
              d.export().equals(delta.export())
                  && d.nodeId().equals(delta.nodeId())
                  && d.workerId().equals(delta.workerId())
                  && d.moduleId().equals(delta.moduleId()));
      recentDeltas.addFirst(delta);
      while (recentDeltas.size() > MAX_RETAINED_RECENT_DELTAS) {
        recentDeltas.removeLast();
      }
    }
  }

  private record InstanceKey(String nodeId, String workerId, ModuleId moduleId) {}

  private record VersionedEntry(ServiceEndpoint endpoint, long version, boolean present) {}
}
