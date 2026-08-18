package com.gimle.controlplane.networkpolicy;

import com.gimle.mimir.manifest.NetworkPolicySpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This {@code ApiServer} replica's own view of every declared {@link NetworkPolicySpec}, keyed by
 * name -- in-memory only, the same non-durable, per-replica posture {@code
 * com.gimle.controlplane.service.ServiceRegistry} already documents for {@code ServiceSpec}, and
 * for the identical reason: a {@code NetworkPolicySpec} has no persisted home in {@code
 * gimle-mimir} yet, so one declared against one control-plane replica is visible only to that
 * replica until it gets one -- a real limitation for a multi-replica control plane, unaffected by
 * every single-replica deployment (including every test in this module).
 */
public final class NetworkPolicyRegistry {

  private final Map<String, NetworkPolicySpec> policies = new ConcurrentHashMap<>();

  public void put(NetworkPolicySpec spec) {
    policies.put(spec.name(), spec);
  }

  public Optional<NetworkPolicySpec> get(String name) {
    return Optional.ofNullable(policies.get(name));
  }

  public List<NetworkPolicySpec> list() {
    return List.copyOf(policies.values());
  }

  public void remove(String name) {
    policies.remove(name);
  }
}
