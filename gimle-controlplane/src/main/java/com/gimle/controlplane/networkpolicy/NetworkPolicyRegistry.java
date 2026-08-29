package com.gimle.controlplane.networkpolicy;

import com.gimle.mimir.manifest.NetworkPolicySpec;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StoreReader;
import java.util.List;
import java.util.Optional;

/**
 * Every declared {@link NetworkPolicySpec}, persisted through {@code gimle-mimir}'s {@link
 * StoreReader}/{@link MutationSink} the same way {@code
 * com.gimle.controlplane.service.ServiceRegistry} persists {@code ServiceSpec} -- a policy created
 * against one control-plane replica is visible to every other replica reading the same store
 * cluster, not just the replica it was submitted to. This class itself holds no spec state of its
 * own; it's a thin, {@code ApiServer}-route-shaped facade over {@code store}/{@code mutations},
 * kept so every {@code /networkpolicies*} handler's call shape ({@code put}/{@code get}/{@code
 * list}/{@code remove}) stays unchanged from before this class delegated instead of stored.
 */
public final class NetworkPolicyRegistry {

  private final StoreReader store;
  private final MutationSink mutations;

  /** Test-only convenience: applies mutations directly, bypassing Raft replication entirely. */
  public NetworkPolicyRegistry(StateStore store) {
    this(store, mutation -> mutation.applyTo(store));
  }

  public NetworkPolicyRegistry(StoreReader store, MutationSink mutations) {
    this.store = store;
    this.mutations = mutations;
  }

  public void put(NetworkPolicySpec spec) {
    mutations.propose(new StateMutation.PutNetworkPolicy(spec));
  }

  public Optional<NetworkPolicySpec> get(String tenantId, String name) {
    return store.getNetworkPolicy(tenantId, name);
  }

  public List<NetworkPolicySpec> list() {
    return store.listNetworkPolicies();
  }

  public void remove(String tenantId, String name) {
    mutations.propose(new StateMutation.RemoveNetworkPolicy(tenantId, name));
  }
}
