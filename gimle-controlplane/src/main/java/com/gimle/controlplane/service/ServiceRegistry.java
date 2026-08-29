package com.gimle.controlplane.service;

import com.gimle.mimir.manifest.ServiceSpec;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StoreReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every declared {@link ServiceSpec}, persisted through {@code gimle-mimir}'s {@link StoreReader}/
 * {@link MutationSink} the same way {@code DeploymentSpec} and its siblings round-trip through
 * {@code StoreClient} into the Raft-replicated store -- a Service created against one control-plane
 * replica is visible to every other replica reading the same store cluster, not just the replica it
 * was submitted to. This class itself holds no spec state of its own; it's a thin, {@code
 * ApiServer}-route-shaped facade over {@code store}/{@code mutations}, kept so every {@code
 * /services*} handler's call shape ({@code put}/{@code get}/{@code list}/{@code remove}) stays
 * unchanged from before this class delegated instead of stored.
 *
 * <p>{@code endpoints} is the one piece of state that stays genuinely in-memory/per-replica,
 * exactly like {@code ApiServer}'s own {@code LoginThrottle} field: it's a reconciler-tick cache,
 * not desired state, populated by {@code ServiceReconciler}, one full replacement per {@code name}
 * per tick, and never trusted as the read path for {@code GET /services/{name}/endpoints} -- that
 * route recomputes live via {@link ServiceEndpointResolver} instead, exactly like {@code GET
 * /endpoints/{name}} already does for every other workload kind, so a caller never sees a result
 * stale by up to one reconcile interval. The stored copy exists for {@code ServiceReconciler}'s own
 * convergence tests to assert against directly, and as the eventual read path for an in-process
 * future consumer that has no HTTP round trip to spare.
 */
public final class ServiceRegistry {

  private final StoreReader store;
  private final MutationSink mutations;
  private final Map<String, List<ServiceEndpoint>> endpoints = new ConcurrentHashMap<>();

  /** Test-only convenience: applies mutations directly, bypassing Raft replication entirely. */
  public ServiceRegistry(StateStore store) {
    this(store, mutation -> mutation.applyTo(store));
  }

  public ServiceRegistry(StoreReader store, MutationSink mutations) {
    this.store = store;
    this.mutations = mutations;
  }

  public void put(ServiceSpec spec) {
    mutations.propose(new StateMutation.PutService(spec));
  }

  public Optional<ServiceSpec> get(Optional<String> tenantId, String name) {
    return store.getService(tenantId, name);
  }

  public List<ServiceSpec> list() {
    return store.listServices();
  }

  /** Removes both the spec and whatever endpoint set was last computed for it. */
  public void remove(Optional<String> tenantId, String name) {
    mutations.propose(new StateMutation.RemoveService(tenantId, name));
    endpoints.remove(endpointsKey(tenantId, name));
  }

  public void putEndpoints(Optional<String> tenantId, String name, List<ServiceEndpoint> value) {
    endpoints.put(endpointsKey(tenantId, name), List.copyOf(value));
  }

  /** Empty for a name never reconciled yet -- not distinguished from "reconciled, found none". */
  public List<ServiceEndpoint> getEndpoints(Optional<String> tenantId, String name) {
    return endpoints.getOrDefault(endpointsKey(tenantId, name), List.of());
  }

  /** Tenant-scoped the same way {@code StateStore}'s own internal keys are -- see its javadoc. */
  private static String endpointsKey(Optional<String> tenantId, String name) {
    return tenantId.orElse("") + '\0' + name;
  }
}
