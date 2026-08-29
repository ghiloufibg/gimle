package com.gimle.controlplane.tenant;

import com.gimle.controlplane.admission.WorkloadResourceProfile;
import com.gimle.controlplane.admission.WorkloadResourceProfile.Profile;
import com.gimle.controlplane.andvari.ArtifactResolver;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.manifest.StatefulSetSpec;
import com.gimle.mimir.manifest.WorkloadSpec;
import com.gimle.mimir.store.StoreReader;
import java.util.Optional;

/**
 * Shared quota-summation logic, used both at admission (the API server, before a workload is
 * durably stored) and continuously ({@code QuotaReconciler}, every tick) -- one calculation, not
 * two copies that could drift. Reads each tenant workload's module descriptor from its artifact
 * through the same {@link ArtifactResolver} every reconciler already uses (local path or
 * registry-coordinate alike -- a blank {@code artifactPath} resolved through Andvari is a
 * documented, fully-wired deployment shape, not a corner case); an unreadable/unresolvable artifact
 * is skipped (not a resource this calculation can charge against a tenant) the same way {@code
 * DeploymentReconciler} itself tolerates one and simply retries next tick.
 *
 * <p>Sums across every placeable workload kind (Deployment, Job, DaemonSet, StatefulSet) sharing a
 * tenant, not Deployment alone -- a tenant's real resource footprint is the sum of all four, and
 * charging only Deployment against its quota left every other kind free to consume unlimited
 * resources under the same tenant. CronJob is excluded: it is never itself placed (see {@link
 * WorkloadResourceProfile}'s own javadoc), only the ordinary JobSpecs it generates are, and those
 * are counted here as Jobs like any other.
 */
public final class TenantUsage {

  private TenantUsage() {}

  public record Usage(long memoryBytes, long cpuMillicores, int instances) {

    public Usage plus(long moreMemoryBytes, long moreCpuMillicores, int moreInstances) {
      return new Usage(
          memoryBytes + moreMemoryBytes,
          cpuMillicores + moreCpuMillicores,
          instances + moreInstances);
    }

    public boolean exceeds(ResourceQuota quota) {
      return memoryBytes > quota.maxMemoryBytes()
          || cpuMillicores > quota.maxCpuMillicores()
          || instances > quota.maxInstances();
    }
  }

  /**
   * Local-artifact-only resolution -- the pre-registry behavior every existing test exercises. See
   * {@link #currentlyAssigned(StoreReader, ArtifactResolver, String, Optional)} for real wiring
   * through a control plane's shared resolver.
   */
  public static Usage currentlyAssigned(
      StoreReader store, String tenantId, Optional<String> excludingWorkloadName) {
    return currentlyAssigned(store, ArtifactResolver.localOnly(), tenantId, excludingWorkloadName);
  }

  /**
   * Currently-assigned usage for {@code tenantId}, summed across every Deployment/Job/DaemonSet/
   * StatefulSet sharing it *except* {@code excludingWorkloadName} (pass {@code Optional.empty()} to
   * include everything) -- the exclusion lets admission compute "what would usage be after this PUT
   * replaces its own prior spec" without double-counting the workload being submitted. Matched by
   * bare name only, not kind -- harmless in practice since a kind/name pair is what a submission's
   * own URL path already pins down.
   */
  public static Usage currentlyAssigned(
      StoreReader store,
      ArtifactResolver artifactResolver,
      String tenantId,
      Optional<String> excludingWorkloadName) {
    Usage total = new Usage(0, 0, 0);
    for (DeploymentSpec spec : store.listDeployments()) {
      total = accumulate(total, store, artifactResolver, tenantId, excludingWorkloadName, spec);
    }
    for (JobSpec spec : store.listJobSpecs()) {
      total = accumulate(total, store, artifactResolver, tenantId, excludingWorkloadName, spec);
    }
    for (DaemonSetSpec spec : store.listDaemonSetSpecs()) {
      total = accumulate(total, store, artifactResolver, tenantId, excludingWorkloadName, spec);
    }
    for (StatefulSetSpec spec : store.listStatefulSetSpecs()) {
      total = accumulate(total, store, artifactResolver, tenantId, excludingWorkloadName, spec);
    }
    return total;
  }

  private static Usage accumulate(
      Usage total,
      StoreReader store,
      ArtifactResolver artifactResolver,
      String tenantId,
      Optional<String> excludingWorkloadName,
      WorkloadSpec spec) {
    if (excludingWorkloadName.filter(spec.name()::equals).isPresent()) {
      return total;
    }
    if (spec.tenantId().filter(tenantId::equals).isEmpty()) {
      return total;
    }
    Usage contribution = contributionOf(store, artifactResolver, spec);
    return total.plus(
        contribution.memoryBytes(), contribution.cpuMillicores(), contribution.instances());
  }

  /**
   * Local-artifact-only resolution -- the pre-registry behavior every existing test exercises. See
   * {@link #contributionOf(StoreReader, ArtifactResolver, WorkloadSpec)} for real wiring through a
   * control plane's shared resolver.
   */
  public static Usage contributionOf(StoreReader store, DeploymentSpec spec) {
    return contributionOf(store, ArtifactResolver.localOnly(), spec);
  }

  /**
   * One workload's own contribution: {@code resourceRequest * committedInstances}, {@code
   * committedInstances} meaning different things per kind -- see {@link WorkloadResourceProfile}'s
   * own javadoc for why. Empty for a kind {@link WorkloadResourceProfile#of} has nothing to size
   * (a CronJobSpec) -- it is not itself a resource consumer.
   */
  public static Usage contributionOf(
      StoreReader store, ArtifactResolver artifactResolver, WorkloadSpec spec) {
    Optional<Profile> profile = WorkloadResourceProfile.of(spec, store);
    if (profile.isEmpty()) {
      return new Usage(0, 0, 0);
    }
    Profile p = profile.get();
    ModuleDescriptor descriptor;
    try {
      // A registry-coordinate workload (blank artifactPath, resolved through Andvari) must be
      // charged the same resource request its own reconciler actually schedules against, not
      // silently read as zero usage because a direct ModuleArtifactReader/VesselArtifacts read
      // can't resolve it -- see this class's own javadoc.
      descriptor = artifactResolver.resolve(p.artifactPath(), p.moduleId(), p.vessel()).descriptor();
    } catch (RuntimeException e) {
      return new Usage(0, 0, 0);
    }
    return new Usage(
        descriptor.resourceRequest().memoryBytes() * p.committedInstances(),
        descriptor.resourceRequest().cpuMillicores() * p.committedInstances(),
        p.committedInstances());
  }
}
