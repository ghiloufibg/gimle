package com.gimle.holmgang.loki;

import com.gimle.holmgang.HolmgangException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fault injector over a proxied topology's interposed links. Interposition happens at boot --
 * every control plane is handed per-replica proxy endpoints instead of the real store addresses --
 * which is why cutting one replica's view of the store cannot disturb another's. Faults are applied
 * and removed live; every cut hands back a {@link Partition} whose {@code heal()} (or
 * try-with-resources close) restores the link.
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

  @Override
  public void close() {
    for (final List<LokiProxy> proxies : controlPlaneToStoreProxies.values()) {
      proxies.forEach(LokiProxy::close);
    }
    controlPlaneToStoreProxies.clear();
  }
}
