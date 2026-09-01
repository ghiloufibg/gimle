package com.gimle.controlplane.admission;

import com.gimle.controlplane.admission.WorkloadResourceProfile.Profile;
import com.gimle.controlplane.andvari.ArtifactResolver;
import com.gimle.controlplane.tenant.TenantUsage;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.manifest.WorkloadSpec;
import java.util.Optional;

/**
 * Admission-time quota check, extracted unchanged from {@code ApiServer}'s former {@code
 * checkTenantQuota}: an unenforceable {@code tenantId} (see {@link Tenant#isEnforceable}) means
 * nothing to check; an unknown tenant, an unreadable artifact, or a submission that would push the
 * tenant past its {@link com.gimle.core.tenant.ResourceQuota} all reject outright. An unreadable
 * artifact rejects the submission for a *tenanted* workload specifically (unlike a reconciler,
 * which just retries next tick with nothing yet at stake), since admission can't safely let a
 * submission through it has no way to verify against the tenant's quota. {@code artifactResolver}
 * is the same shared instance every reconciler resolves through, so an existing tenant workload
 * resolved from an Andvari registry coordinate is summed correctly here too, not silently read as
 * zero.
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
    if (!Tenant.isEnforceable(spec.tenantId())) {
      return AdmissionDecision.allow(spec);
    }
    String tenantId = spec.tenantId().get();
    Optional<Tenant> tenant = request.store().getTenant(tenantId);
    if (tenant.isEmpty()) {
      return AdmissionDecision.reject("unknown tenantId: " + tenantId);
    }
    Optional<Profile> profile = WorkloadResourceProfile.of(spec, request.store());
    if (profile.isEmpty()) {
      return AdmissionDecision.allow(spec);
    }
    if (request.artifact().isEmpty()) {
      return AdmissionDecision.reject(
          "cannot verify tenant quota: artifact unreadable at " + profile.get().artifactPath());
    }
    ModuleDescriptor descriptor = request.artifact().get().descriptor();
    // Sums against committedInstances (replicas + maxSurge for a Deployment, plain replicas for a
    // StatefulSet, always 1 for a Job, currently-registered node count for a DaemonSet), not
    // "replicas" alone -- admission has to charge the tenant's quota for the peak this submission
    // could actually reach, not just its steady state. See Profile's own javadoc.
    int committed = profile.get().committedInstances();
    TenantUsage.Usage existing =
        TenantUsage.currentlyAssigned(
            request.store(), artifactResolver, tenantId, Optional.of(spec.name()));
    TenantUsage.Usage withThisSubmission =
        existing.plus(
            descriptor.resourceRequest().memoryBytes() * committed,
            descriptor.resourceRequest().cpuMillicores() * committed,
            committed);
    if (withThisSubmission.exceeds(tenant.get().quota())) {
      return AdmissionDecision.reject(
          "workload "
              + spec.name()
              + " would push tenant "
              + tenantId
              + " past its resource quota: "
              + TenantUsage.describeOverage(existing, withThisSubmission, tenant.get().quota()));
    }
    return AdmissionDecision.allow(spec);
  }
}
