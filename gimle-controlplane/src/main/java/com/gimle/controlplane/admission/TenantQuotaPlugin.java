package com.gimle.controlplane.admission;

import com.gimle.controlplane.admission.WorkloadResourceProfile.Profile;
import com.gimle.controlplane.andvari.ArtifactResolver;
import com.gimle.controlplane.tenant.TenantUsage;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.manifest.WorkloadSpec;
import java.util.Optional;

/**
 * Admission-time quota check: a workload naming no tenant at all has nothing to charge, and a
 * submission that would push its tenant past that tenant's {@link
 * com.gimle.core.tenant.ResourceQuota} is rejected outright.
 *
 * <p>Every tenant that exists is charged against the quota stored for it, the {@code default}
 * tenant included -- it is auto-seeded with a deliberately generous ceiling, and once an operator
 * narrows that ceiling the number they wrote is the one enforced. Exempting a tenant because of its
 * name would mean an operator could set a quota, watch it be accepted and reported back, and never
 * have it applied to a single submission. The two rejections that are *not* a measured overage --
 * an absent tenant row, an unreadable artifact -- stay narrower; see {@code unverifiable} below for
 * why. {@code artifactResolver} is the same shared instance every reconciler resolves through, so
 * an existing tenant workload resolved from an Andvari registry coordinate is summed correctly here
 * too, not silently read as zero.
 *
 * <p>Generic over every placeable {@link WorkloadSpec} kind (Deployment, Job, DaemonSet,
 * StatefulSet), not Deployment alone -- see {@link WorkloadResourceProfile}'s own javadoc for how
 * each kind's own {@code committedInstances} is sized, and {@link TenantUsage}'s for why the
 * aggregate this checks against must include every kind, not just the one being submitted right
 * now. A CronJobSpec (which {@link WorkloadResourceProfile#of} has nothing to size) is allowed
 * through unconditionally -- it is never itself a resource consumer.
 */
public final class TenantQuotaPlugin implements AdmissionPlugin<WorkloadSpec> {

  private final ArtifactResolver artifactResolver;

  /** Local-artifact-only resolution -- the pre-registry behavior every existing test exercises. */
  public TenantQuotaPlugin() {
    this(ArtifactResolver.localOnly());
  }

  public TenantQuotaPlugin(ArtifactResolver artifactResolver) {
    this.artifactResolver = artifactResolver;
  }

  @Override
  public AdmissionDecision<WorkloadSpec> review(AdmissionRequest<WorkloadSpec> request) {
    WorkloadSpec spec = request.spec();
    if (spec.tenantId().isEmpty()) {
      return AdmissionDecision.allow(spec);
    }
    String tenantId = spec.tenantId().get();
    Optional<Tenant> tenant = request.store().getTenant(tenantId);
    if (tenant.isEmpty()) {
      return unverifiable(spec, "unknown tenantId: " + tenantId);
    }
    Optional<Profile> profile = WorkloadResourceProfile.of(spec, request.store());
    if (profile.isEmpty()) {
      return AdmissionDecision.allow(spec);
    }
    ResourceQuota quota = tenant.get().quota();
    // Sums against committedInstances (replicas + maxSurge for a Deployment, plain replicas for a
    // StatefulSet, always 1 for a Job, currently-registered node count for a DaemonSet), not
    // "replicas" alone -- admission has to charge the tenant's quota for the peak this submission
    // could actually reach, not just its steady state. See Profile's own javadoc.
    int committed = profile.get().committedInstances();
    TenantUsage.Usage existing =
        TenantUsage.currentlyAssigned(
            request.store(), artifactResolver, tenantId, Optional.of(spec.name()));
    if (request.artifact().isEmpty()) {
      // The instance ceiling needs no descriptor, so it is checked even here -- an unreadable jar
      // is no reason to let a submission past the one dimension that is still fully computable.
      TenantUsage.Usage instancesOnly = existing.plus(0, 0, committed);
      if (instancesOnly.exceeds(quota)) {
        return overQuota(spec, tenantId, existing, instancesOnly, quota);
      }
      return unverifiable(
          spec,
          "cannot verify tenant quota: artifact unreadable at " + profile.get().artifactPath());
    }
    ModuleDescriptor descriptor = request.artifact().get().descriptor();
    TenantUsage.Usage withThisSubmission =
        existing.plus(
            descriptor.resourceRequest().memoryBytes() * committed,
            descriptor.resourceRequest().cpuMillicores() * committed,
            committed);
    if (withThisSubmission.exceeds(quota)) {
      return overQuota(spec, tenantId, existing, withThisSubmission, quota);
    }
    return AdmissionDecision.allow(spec);
  }

  private static AdmissionDecision<WorkloadSpec> overQuota(
      WorkloadSpec spec,
      String tenantId,
      TenantUsage.Usage existing,
      TenantUsage.Usage total,
      ResourceQuota quota) {
    return AdmissionDecision.reject(
        "workload "
            + spec.name()
            + " would push tenant "
            + tenantId
            + " past its resource quota: "
            + TenantUsage.describeOverage(existing, total, quota));
  }

  /**
   * A rejection for something this check could not establish either way -- an absent tenant row, an
   * unreadable jar -- as opposed to a computed overage. Reserved for a tenant an operator named
   * deliberately: a workload that named no tenant at all resolves to {@link
   * Tenant#DEFAULT_TENANT_ID} by defaulting, and refusing it because the control plane cannot read
   * a jar that only ever has to exist on the node running it would break the ordinary local {@code
   * artifactPath} deployment path for every workload that never opted into a tenant. A real overage
   * is a different kind of answer entirely and is enforced for every tenant, {@code default}
   * included -- that one is measured, not assumed.
   */
  private static AdmissionDecision<WorkloadSpec> unverifiable(WorkloadSpec spec, String reason) {
    return Tenant.isEnforceable(spec.tenantId())
        ? AdmissionDecision.reject(reason)
        : AdmissionDecision.allow(spec);
  }
}
