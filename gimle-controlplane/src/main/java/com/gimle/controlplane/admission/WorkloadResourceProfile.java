package com.gimle.controlplane.admission;

import com.gimle.core.module.ModuleId;
import com.gimle.core.vessel.VesselSpec;
import com.gimle.mimir.manifest.CronJobSpec;
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.manifest.StatefulSetSpec;
import com.gimle.mimir.manifest.WorkloadSpec;
import com.gimle.mimir.store.StoreReader;
import java.util.Optional;

/**
 * The shape {@link TenantQuotaPlugin}/{@link LimitRangePlugin} need from any {@link WorkloadSpec}
 * kind to charge it against a tenant's quota/limit range -- {@code artifactPath}/{@code moduleId}/
 * {@code vessel} (identical fields on every concrete kind except {@link CronJobSpec}, which wraps
 * them in its own {@code jobTemplate()} instead) plus {@code committedInstances}, the peak instance
 * count this one spec could legitimately have running at once. Extracted once here rather than
 * duplicated per plugin, the same "one calculation, not two copies that could drift" reasoning
 * {@link com.gimle.controlplane.tenant.TenantUsage}'s own javadoc already gives for its identical
 * concern.
 *
 * <p>{@link #of} returns {@link Optional#empty()} for a {@link CronJobSpec}: a CronJob is a policy
 * generator, never itself placed -- each firing materializes an ordinary {@link JobSpec}, which is
 * what actually gets charged (by {@code JobReconciler}'s or {@code ApiServer.handlePutJob}'s own
 * admission, whichever created it). There is nothing here to size a CronJobSpec itself against.
 *
 * <p>{@code committedInstances} for a {@link DaemonSetSpec} is a deliberate, documented
 * over-approximation: unlike every other kind, a DaemonSet's real instance count is recomputed
 * every reconcile tick from live, filtered node eligibility (tier support, cordon, taint, required
 * labels -- see {@code DaemonSetReconciler}), which admission time has no cheap way to reproduce
 * exactly. Charging against every currently-registered node instead (ignoring eligibility
 * filtering) can only ever overcount, never undercount -- the safe direction for a quota check to
 * be wrong in, since undercounting is what would let a tenant's real usage silently exceed its
 * quota.
 */
public final class WorkloadResourceProfile {

  private WorkloadResourceProfile() {}

  public record Profile(
      String artifactPath, ModuleId moduleId, Optional<VesselSpec> vessel, int committedInstances) {}

  public static Optional<Profile> of(WorkloadSpec spec, StoreReader store) {
    return switch (spec) {
      case DeploymentSpec s ->
          Optional.of(
              new Profile(s.artifactPath(), s.moduleId(), s.vessel(), s.maxCommittedInstances()));
      case StatefulSetSpec s ->
          Optional.of(new Profile(s.artifactPath(), s.moduleId(), s.vessel(), s.replicas()));
      case JobSpec s ->
          // At most one non-terminal JobRun ever exists at a time per JobSpec -- see JobReconciler's
          // own convergence invariant -- so a Job always commits exactly one instance's worth of
          // resources while it's running, regardless of backoffLimit (a retry replaces the prior
          // attempt, it never runs alongside it).
          Optional.of(new Profile(s.artifactPath(), s.moduleId(), s.vessel(), 1));
      case DaemonSetSpec s ->
          Optional.of(
              new Profile(
                  s.artifactPath(), s.moduleId(), s.vessel(), store.listNodeRegistrations().size()));
      case CronJobSpec ignored -> Optional.empty();
    };
  }
}
