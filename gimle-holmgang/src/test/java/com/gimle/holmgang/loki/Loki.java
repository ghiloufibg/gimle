package com.gimle.holmgang.loki;

import com.gimle.holmgang.HolmgangException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fault injector over a proxied topology's interposed links. Interposition happens at boot --
 * every control plane is handed per-replica proxy endpoints instead of the real store addresses,
 * and every store is handed proxy endpoints for its own peers instead of their real raft ports --
 * which is why cutting one replica's view cannot disturb another's. Faults are applied and removed
 * live; every cut hands back a {@link Partition} whose {@code heal()} (or try-with-resources close)
 * restores the link.
 */
public final class Loki implements AutoCloseable {

  /** A live network cut; {@link #heal()} (or closing) restores the link. */
  public interface Partition extends AutoCloseable {
    void heal();

    @Override
    default void close() {
      heal();
    }
  }

  private final Map<Integer, List<LokiProxy>> controlPlaneToStoreProxies = new LinkedHashMap<>();
  private final Map<Integer, Map<Integer, LokiProxy>> storeToStoreProxies = new LinkedHashMap<>();

  /** Registers the interposed proxies carrying one control-plane replica's store traffic. */
  public List<Integer> interposeControlPlaneToStores(
      final int controlPlaneIndex, final String storeHost, final List<Integer> storeClientPorts) {
    final List<LokiProxy> proxies = new ArrayList<>();
    final List<Integer> proxyPorts = new ArrayList<>();
    for (final int storePort : storeClientPorts) {
      final LokiProxy proxy = LokiProxy.start(storeHost, storePort);
      proxies.add(proxy);
      proxyPorts.add(proxy.port());
    }
    controlPlaneToStoreProxies.put(controlPlaneIndex, proxies);
    return proxyPorts;
  }

  /** Severs one control-plane replica's every link to the store cluster. */
  public Partition cutControlPlaneFromStores(final int controlPlaneIndex) {
    final List<LokiProxy> proxies = controlPlaneToStoreProxies.get(controlPlaneIndex);
    if (proxies == null) {
      throw new HolmgangException(
          "control plane " + controlPlaneIndex + " has no interposed store links");
    }
    proxies.forEach(LokiProxy::cut);
    return () -> proxies.forEach(LokiProxy::heal);
  }

  /**
   * Registers the interposed proxies carrying one store replica's own *outbound* raft traffic to
   * every other replica -- the store-to-store counterpart to {@link
   * #interposeControlPlaneToStores}, but every store is both a client and a server to every other,
   * unlike the control-plane case where only the control plane ever dials out. That doubling is why
   * {@link #cutStoreFromPeers} must cut two sets of proxies, not one: check-quorum tracks responses
   * to a *leader's own* outbound replication calls, so a single proxy shared by every caller (which
   * could only ever block *inbound* traffic to one target) would leave a partitioned leader still
   * reaching its followers outbound and never see the silence it's supposed to self-demote on.
   * Called once per replica index with that replica's own real view of every other numbered peer's
   * raft port; the returned map (peer index -> proxy port) replaces the real raft ports in that
   * replica's own {@code --peers} argument.
   */
  public Map<Integer, Integer> interposeStoreToStores(
      final int storeIndex,
      final String storeHost,
      final Map<Integer, Integer> peerRaftPortsByIndex) {
    final Map<Integer, LokiProxy> proxies = new LinkedHashMap<>();
    final Map<Integer, Integer> proxyPorts = new LinkedHashMap<>();
    for (final Map.Entry<Integer, Integer> entry : peerRaftPortsByIndex.entrySet()) {
      final LokiProxy proxy = LokiProxy.start(storeHost, entry.getValue());
      proxies.put(entry.getKey(), proxy);
      proxyPorts.put(entry.getKey(), proxy.port());
    }
    storeToStoreProxies.put(storeIndex, proxies);
    return proxyPorts;
  }

  /**
   * Silently drops one store replica's raft peer-to-peer traffic in both directions: that replica's
   * own outbound view of every peer, plus every other replica's own outbound view of it -- see
   * {@link #interposeStoreToStores} for why both halves are required. Uses {@link
   * LokiProxy#blackhole()}, not {@link LokiProxy#cut()} -- unlike {@link
   * #cutControlPlaneFromStores}'s clean, immediate reset, already-open connections and new ones
   * alike go silent instead of erroring out, the realistic shape of a genuine network partition
   * rather than a refused or reset one.
   */
  public Partition cutStoreFromPeers(final int storeIndex) {
    final Map<Integer, LokiProxy> outbound = storeToStoreProxies.get(storeIndex);
    if (outbound == null) {
      throw new HolmgangException("store " + storeIndex + " has no interposed peer links");
    }
    final List<LokiProxy> affected = new ArrayList<>(outbound.values());
    for (final Map.Entry<Integer, Map<Integer, LokiProxy>> entry : storeToStoreProxies.entrySet()) {
      if (entry.getKey().equals(storeIndex)) {
        continue;
      }
      final LokiProxy inbound = entry.getValue().get(storeIndex);
      if (inbound != null) {
        affected.add(inbound);
      }
    }
    affected.forEach(LokiProxy::blackhole);
    return () -> affected.forEach(LokiProxy::heal);
  }

  @Override
  public void close() {
    for (final List<LokiProxy> proxies : controlPlaneToStoreProxies.values()) {
      proxies.forEach(LokiProxy::close);
    }
    controlPlaneToStoreProxies.clear();
    for (final Map<Integer, LokiProxy> proxies : storeToStoreProxies.values()) {
      proxies.values().forEach(LokiProxy::close);
    }
    storeToStoreProxies.clear();
  }
}
