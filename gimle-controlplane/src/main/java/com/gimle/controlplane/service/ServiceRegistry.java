package com.gimle.controlplane.service;

import com.gimle.mimir.manifest.ServiceSpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This {@code ApiServer} replica's own view of every declared {@link ServiceSpec}, keyed by name,
 * plus the most recently reconciled endpoint set for each -- in-memory only, the same non-durable,
 * per-replica posture {@code ApiServer}'s own {@code LoginThrottle} field already documents for its
 * own smaller-blast-radius state. {@code ServiceSpec} has no persisted home in {@code gimle-mimir}
 * yet (unlike {@code DeploymentSpec} and its siblings, which round-trip through {@code StoreClient}
 * into the Raft-replicated store), so a Service declared against one control-plane replica is
 * visible only to that replica until it gets one -- a real limitation for a multi-replica control
 * plane, though every single-replica deployment (including every test in this module) is
 * unaffected.
 *
 * <p>{@code endpoints} is populated by {@code ServiceReconciler}, one full replacement per {@code
 * name} per tick, and is never trusted as the read path for {@code GET /services/{name}/endpoints}
 * -- that route recomputes live via {@link ServiceEndpointResolver} instead, exactly like {@code
 * GET /endpoints/{name}} already does for every other workload kind, so a caller never sees a
 * result stale by up to one reconcile interval. The stored copy exists for {@code
 * ServiceReconciler}'s own convergence tests to assert against directly, and as the eventual read
 * path for an in-process future consumer that has no HTTP round trip to spare.
 */
public final class ServiceRegistry {

  private final Map<String, ServiceSpec> services = new ConcurrentHashMap<>();
  private final Map<String, List<ServiceEndpoint>> endpoints = new ConcurrentHashMap<>();

  public void put(ServiceSpec spec) {
    services.put(spec.name(), spec);
  }

  public Optional<ServiceSpec> get(String name) {
    return Optional.ofNullable(services.get(name));
  }

  public List<ServiceSpec> list() {
    return List.copyOf(services.values());
  }

  /** Removes both the spec and whatever endpoint set was last computed for it. */
  public void remove(String name) {
    services.remove(name);
    endpoints.remove(name);
  }

  public void putEndpoints(String name, List<ServiceEndpoint> value) {
    endpoints.put(name, List.copyOf(value));
  }

  /** Empty for a name never reconciled yet -- not distinguished from "reconciled, found none". */
  public List<ServiceEndpoint> getEndpoints(String name) {
    return endpoints.getOrDefault(name, List.of());
  }
}
