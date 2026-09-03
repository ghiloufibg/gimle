package com.gimle.controlplane.alert;

import com.gimle.mimir.manifest.AlertRuleSpec;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StoreReader;
import java.util.List;
import java.util.Optional;

/**
 * Every declared {@link AlertRuleSpec}, persisted through {@code gimle-mimir}'s {@link
 * StoreReader}/{@link MutationSink} the same way {@code com.gimle.controlplane.service
 * .ServiceRegistry} persists {@code ServiceSpec} -- a rule created against one control-plane
 * replica is visible to every other replica reading the same store cluster, not just the replica it
 * was submitted to. This class itself holds no rule state of its own; it's a thin, {@code
 * ApiServer}-route-shaped facade over {@code store}/{@code mutations}, kept so every {@code
 * /alertrules*} handler's call shape ({@code put}/{@code get}/{@code list}/{@code remove}) stays
 * unchanged from before this class delegated instead of stored.
 */
public final class AlertRuleRegistry {

  private final StoreReader store;
  private final MutationSink mutations;

  /** Test-only convenience: applies mutations directly, bypassing Raft replication entirely. */
  public AlertRuleRegistry(StateStore store) {
    this(store, mutation -> mutation.applyTo(store));
  }

  public AlertRuleRegistry(StoreReader store, MutationSink mutations) {
    this.store = store;
    this.mutations = mutations;
  }

  public void put(AlertRuleSpec spec) {
    mutations.propose(new StateMutation.PutAlertRule(spec));
  }

  public Optional<AlertRuleSpec> get(Optional<String> tenantId, String name) {
    return store.getAlertRule(tenantId, name);
  }

  public List<AlertRuleSpec> list() {
    return store.listAlertRules();
  }

  public void remove(Optional<String> tenantId, String name) {
    mutations.propose(new StateMutation.RemoveAlertRule(tenantId, name));
  }

  /**
   * Empty means the rule has never crossed or resolved since it (or a same-named predecessor) was
   * created -- see {@code StateStore#putAlertFiringState}'s own javadoc for the full three-state
   * meaning this durable read carries.
   */
  public Optional<Boolean> getFiringState(Optional<String> tenantId, String name) {
    return store.getAlertFiringState(tenantId, name);
  }

  /** Proposed by {@code AlertReconciler} only on an actual crossed/resolved transition. */
  public void putFiringState(Optional<String> tenantId, String name, boolean firing) {
    mutations.propose(new StateMutation.PutAlertFiringState(tenantId, name, firing));
  }
}
