package com.gimle.controlplane.reconcile;

import com.gimle.controlplane.andvari.ArtifactResolver;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ResourceSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.LimitRangeSpec;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StoreReader;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Continuously re-checks each tenanted deployment against its tenant's {@link LimitRangeSpec}, if
 * one exists, and marks it limit-range-violating if a range was retroactively tightened below
 * what's already running -- the per-workload counterpart to {@link QuotaReconciler}, level-
 * triggered the same way but single-pass: unlike quota, a workload's own bound violation needs no
 * cross-deployment summation to evaluate, only its own {@link ModuleDescriptor#resourceRequest()}/
 * {@link ModuleDescriptor#resourceLimit()} against the tenant's current range.
 *
 * <p>Deliberately does <b>not</b> evict instances to force compliance -- same posture {@link
 * QuotaReconciler} already establishes; a human operator resolves an over-range deployment
 * explicitly, this reconciler only surfaces it via {@link StateStore#putLimitRangeViolation}, read
 * by the API server's deployment status surface.
 */
public final class LimitRangeReconciler {

  private static final Logger log = LoggerFactory.getLogger(LimitRangeReconciler.class);

  private final StoreReader store;
  private final MutationSink mutations;
  private final ArtifactResolver artifactResolver;

  /** Test-only convenience: applies mutations directly, bypassing Raft replication entirely. */
  public LimitRangeReconciler(StateStore store) {
    this(store, mutation -> mutation.applyTo(store));
  }

  /** Local-artifact-only resolution -- the pre-registry behavior every existing test exercises. */
  public LimitRangeReconciler(StoreReader store, MutationSink mutations) {
    this(store, mutations, ArtifactResolver.localOnly());
  }

  public LimitRangeReconciler(
      StoreReader store, MutationSink mutations, ArtifactResolver artifactResolver) {
    this.store = store;
    this.mutations = mutations;
    this.artifactResolver = artifactResolver;
  }

  public void reconcileOnce() {
    for (DeploymentSpec spec : store.listDeployments()) {
      try {
        reconcileOne(spec);
      } catch (RuntimeException e) {
        // One deployment's check failing must never abort the rest of this tick -- the next tick
        // retries from the same full snapshot, matching QuotaReconciler's own per-deployment
        // isolation.
        log.warn(
            "limit range reconcile for deployment {} failed: {}", spec.name(), e.getMessage(), e);
      }
    }
  }

  private void reconcileOne(DeploymentSpec spec) {
    boolean violating =
        spec.tenantId().map(tenantId -> violatesRange(spec, tenantId)).orElse(false);
    // Level-triggered means recomputing from scratch every tick, not re-proposing every tick --
    // see QuotaReconciler's own identical reasoning.
    if (store.isLimitRangeViolating(spec.name()) != violating) {
      mutations.propose(new StateMutation.PutLimitRangeViolation(spec.name(), violating));
    }
  }

  private boolean violatesRange(DeploymentSpec spec, String tenantId) {
    Optional<LimitRangeSpec> range = store.getLimitRange(tenantId);
    if (range.isEmpty()) {
      return false;
    }
    Optional<ModuleArtifact> artifact =
        artifactResolver.resolveIfPossible(spec.artifactPath(), spec.moduleId(), spec.vessel());
    if (artifact.isEmpty()) {
      // An unresolvable artifact for an already-admitted deployment is a transient drift concern,
      // not this reconciler's to flag -- nothing to check, so it just stays non-violating, same
      // "not this reconciler's problem" posture QuotaReconciler takes for an unregistered tenant.
      return false;
    }
    ModuleDescriptor descriptor = artifact.get().descriptor();
    LimitRangeSpec limitRange = range.get();
    boolean violating =
        outOfBounds(descriptor.resourceRequest(), limitRange.minRequest(), limitRange.maxRequest())
            || outOfBounds(
                descriptor.resourceLimit(), limitRange.minLimit(), limitRange.maxLimit());
    if (violating) {
      log.warn(
          "deployment {} violates tenant {}'s limit range; marked limit-range-violating",
          spec.name(),
          tenantId);
    }
    return violating;
  }

  /** Inclusive at both ends -- a value exactly at min or max satisfies the bound. */
  private static boolean outOfBounds(
      ResourceSpec actual, Optional<ResourceSpec> min, Optional<ResourceSpec> max) {
    if (min.isPresent()
        && (actual.memoryBytes() < min.get().memoryBytes()
            || actual.cpuMillicores() < min.get().cpuMillicores())) {
      return true;
    }
    return max.isPresent()
        && (actual.memoryBytes() > max.get().memoryBytes()
            || actual.cpuMillicores() > max.get().cpuMillicores());
  }
}
