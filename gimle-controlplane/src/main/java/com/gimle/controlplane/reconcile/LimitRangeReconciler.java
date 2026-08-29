package com.gimle.controlplane.reconcile;

import com.gimle.controlplane.admission.WorkloadResourceProfile;
import com.gimle.controlplane.admission.WorkloadResourceProfile.Profile;
import com.gimle.controlplane.andvari.ArtifactResolver;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.manifest.LimitRangeSpec;
import com.gimle.mimir.manifest.WorkloadSpec;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StoreReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Continuously re-checks each tenanted workload (Deployment, Job, DaemonSet, StatefulSet -- see
 * {@link WorkloadResourceProfile}'s own javadoc for why all four) against its tenant's {@link
 * LimitRangeSpec}, if one exists, and marks it limit-range-violating if a range was retroactively
 * tightened below what's already running -- the per-workload counterpart to {@link
 * QuotaReconciler}, level-triggered the same way but single-pass: unlike quota, a workload's own
 * bound violation needs no cross-workload summation to evaluate, only its own {@link
 * ModuleDescriptor#resourceRequest()}/{@link ModuleDescriptor#resourceLimit()} against the tenant's
 * current range. The bound check itself is {@link LimitRangeSpec#violation}, shared with {@code
 * LimitRangePlugin} so admission and reconciliation never drift on what counts as a violation.
 *
 * <p>Deliberately does <b>not</b> evict instances to force compliance -- same posture {@link
 * QuotaReconciler} already establishes; a human operator resolves an over-range workload
 * explicitly, this reconciler only surfaces it (with a reason describing which bound is failing)
 * via {@link StateStore#putLimitRangeViolation}, read by the API server's deployment status
 * surface.
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
    List<WorkloadSpec> workloads = new ArrayList<>();
    workloads.addAll(store.listDeployments());
    workloads.addAll(store.listJobSpecs());
    workloads.addAll(store.listDaemonSetSpecs());
    workloads.addAll(store.listStatefulSetSpecs());
    for (WorkloadSpec spec : workloads) {
      try {
        reconcileOne(spec);
      } catch (RuntimeException e) {
        // One workload's check failing must never abort the rest of this tick -- the next tick
        // retries from the same full snapshot, matching QuotaReconciler's own per-workload
        // isolation.
        log.warn("limit range reconcile for {} failed: {}", spec.name(), e.getMessage(), e);
      }
    }
  }

  private void reconcileOne(WorkloadSpec spec) {
    Optional<String> reason =
        Tenant.isEnforceable(spec.tenantId())
            ? violationReasonFor(spec, spec.tenantId().get())
            : Optional.empty();
    // Level-triggered means recomputing from scratch every tick, not re-proposing every tick --
    // see QuotaReconciler's own identical reasoning. Comparing the full reason (not just
    // present/absent) also re-proposes when the range changes which bound is failing, so the
    // persisted reason never goes stale while a workload stays violating.
    if (!store.limitRangeViolationReason(spec.tenantId(), spec.name()).equals(reason)) {
      if (reason.isPresent()) {
        log.warn(
            "{} violates tenant {}'s limit range: {}",
            spec.name(),
            spec.tenantId().orElse("-"),
            reason.get());
      }
      mutations.propose(
          new StateMutation.PutLimitRangeViolation(
              spec.tenantId(), spec.name(), reason.orElse("")));
    }
  }

  private Optional<String> violationReasonFor(WorkloadSpec spec, String tenantId) {
    Optional<LimitRangeSpec> range = store.getLimitRange(tenantId);
    if (range.isEmpty()) {
      return Optional.empty();
    }
    Optional<Profile> profile = WorkloadResourceProfile.of(spec, store);
    if (profile.isEmpty()) {
      // A CronJobSpec (or any future non-placeable kind) is not itself a resource consumer -- see
      // WorkloadResourceProfile's own javadoc.
      return Optional.empty();
    }
    Optional<ModuleArtifact> artifact =
        artifactResolver.resolveIfPossible(
            profile.get().artifactPath(), profile.get().moduleId(), profile.get().vessel());
    if (artifact.isEmpty()) {
      // An unresolvable artifact for an already-admitted workload is a transient drift concern,
      // not this reconciler's to flag -- nothing to check, so it just stays non-violating, same
      // "not this reconciler's problem" posture QuotaReconciler takes for an unregistered tenant.
      return Optional.empty();
    }
    ModuleDescriptor descriptor = artifact.get().descriptor();
    return range.get().violation(descriptor.resourceRequest(), descriptor.resourceLimit());
  }
}
