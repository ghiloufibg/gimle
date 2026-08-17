package com.gimle.controlplane.admission;

import com.gimle.controlplane.andvari.ArtifactResolver;
import com.gimle.controlplane.tenant.TenantUsage;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.manifest.DeploymentSpec;
import java.util.Optional;

/**
 * Admission-time quota check, extracted unchanged from {@code ApiServer}'s former {@code
 * checkTenantQuota}: absent {@code tenantId} means nothing to check; an unknown tenant, an
 * unreadable artifact, or a submission that would push the tenant past its {@link
 * com.gimle.core.tenant.ResourceQuota} all reject outright. An unreadable artifact rejects the
 * submission for a *tenanted* deployment specifically (unlike {@code DeploymentReconciler}, which
 * just retries next tick with nothing yet at stake), since admission can't safely let a submission
 * through it has no way to verify against the tenant's quota. {@code artifactResolver} is the same
 * shared instance every reconciler resolves through, so an existing tenant deployment resolved from
 * an Andvari registry coordinate is summed correctly here too, not silently read as zero.
 */
public final class TenantQuotaPlugin implements AdmissionPlugin<DeploymentSpec> {

  private final ArtifactResolver artifactResolver;

  /** Local-artifact-only resolution -- the pre-registry behavior every existing test exercises. */
  public TenantQuotaPlugin() {
    this(ArtifactResolver.localOnly());
  }

  public TenantQuotaPlugin(ArtifactResolver artifactResolver) {
    this.artifactResolver = artifactResolver;
  }

  @Override
  public AdmissionDecision<DeploymentSpec> review(AdmissionRequest<DeploymentSpec> request) {
    DeploymentSpec spec = request.spec();
    if (spec.tenantId().isEmpty()) {
      return AdmissionDecision.allow(spec);
    }
    String tenantId = spec.tenantId().get();
    Optional<Tenant> tenant = request.store().getTenant(tenantId);
    if (tenant.isEmpty()) {
      return AdmissionDecision.reject("unknown tenantId: " + tenantId);
    }
    if (request.artifact().isEmpty()) {
      return AdmissionDecision.reject(
          "cannot verify tenant quota: artifact unreadable at " + spec.artifactPath());
    }
    ModuleDescriptor descriptor = request.artifact().get().descriptor();
    // Sums against maxCommittedInstances() (replicas + maxSurge), not replicas alone -- a rollout
    // that surges may transiently run more instances than replicas declares, and admission has to
    // charge the tenant's quota for the peak it could actually reach, not just the steady state.
    // DeploymentManifestParser accepts a nonzero maxSurge today, so this genuinely changes the
    // admission outcome once a submission's disruption budget surges -- not a no-op.
    int committed = spec.maxCommittedInstances();
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
          "deployment "
              + spec.name()
              + " would push tenant "
              + tenantId
              + " past its resource quota");
    }
    return AdmissionDecision.allow(spec);
  }
}
