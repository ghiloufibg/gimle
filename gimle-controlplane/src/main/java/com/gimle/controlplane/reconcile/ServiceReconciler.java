package com.gimle.controlplane.reconcile;

import com.gimle.controlplane.service.ServiceEndpoint;
import com.gimle.controlplane.service.ServiceEndpointResolver;
import com.gimle.controlplane.service.ServiceRegistry;
import com.gimle.mimir.manifest.ServiceSpec;
import com.gimle.mimir.store.StoreReader;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps {@code registry}'s stored endpoint set for every {@link ServiceSpec} current. Level-
 * triggered like every other reconciler in this codebase: every tick recomputes each Service's full
 * endpoint list from scratch off the current store snapshot via {@link ServiceEndpointResolver} and
 * replaces whatever was stored before, rather than diffing against what changed since the last tick
 * -- an empty store, a store with every backing instance already {@code ACTIVE} and ready, and
 * everything in between all converge through this exact same code path. A Service with no
 * currently-ready backing instance ends up with an empty endpoint list, which is a normal, valid
 * outcome (a rollout in progress, a scale-to-zero, a fresh deployment not yet placed), not a fault
 * -- mirroring {@link DeploymentReconciler#reconcileDeployment}'s own "leaves indices unplaced
 * without throwing" posture for the same kind of transient gap.
 */
public final class ServiceReconciler {

  private static final Logger log = LoggerFactory.getLogger(ServiceReconciler.class);

  private final ServiceRegistry registry;
  private final StoreReader store;

  public ServiceReconciler(ServiceRegistry registry, StoreReader store) {
    this.registry = registry;
    this.store = store;
  }

  public void reconcileOnce() {
    for (ServiceSpec spec : registry.list()) {
      try {
        List<ServiceEndpoint> endpoints = ServiceEndpointResolver.resolve(store, spec);
        registry.putEndpoints(spec.tenantId(), spec.name(), endpoints);
      } catch (RuntimeException e) {
        // One Service's failure must never abort the rest of this tick's Services -- the next
        // tick retries this one from the same full snapshot, the same level-triggered posture
        // every other reconciler here already relies on.
        log.warn("reconcile of service {} failed: {}", spec.name(), e.getMessage(), e);
      }
    }
  }
}
