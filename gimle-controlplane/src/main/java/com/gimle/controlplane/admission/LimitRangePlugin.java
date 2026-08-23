package com.gimle.controlplane.admission;

import com.gimle.core.module.ModuleDescriptor;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.LimitRangeSpec;
import java.util.Optional;

/**
 * Admission-time check against the submitting tenant's {@link LimitRangeSpec}, if one exists: the
 * per-workload counterpart to {@link TenantQuotaPlugin}'s aggregate check, run first in the
 * deployment admission chain since it's the cheaper single-artifact comparison, with no
 * cross-deployment summation to compute. Absent {@code tenantId} or an absent LimitRange for the
 * tenant are both a no-op allow -- a LimitRange is opt-in per tenant, not a default every
 * deployment must satisfy. The bound check itself is {@link LimitRangeSpec#violation}, shared with
 * {@code LimitRangeReconciler} so the two never drift on what counts as a violation.
 */
public final class LimitRangePlugin implements AdmissionPlugin<DeploymentSpec> {

  @Override
  public AdmissionDecision<DeploymentSpec> review(AdmissionRequest<DeploymentSpec> request) {
    DeploymentSpec spec = request.spec();
    if (spec.tenantId().isEmpty()) {
      return AdmissionDecision.allow(spec);
    }
    String tenantId = spec.tenantId().get();
    Optional<LimitRangeSpec> limitRange = request.store().getLimitRange(tenantId);
    if (limitRange.isEmpty()) {
      return AdmissionDecision.allow(spec);
    }
    if (request.artifact().isEmpty()) {
      return AdmissionDecision.reject(
          "cannot verify limit range: artifact unreadable at " + spec.artifactPath());
    }
    ModuleDescriptor descriptor = request.artifact().get().descriptor();
    Optional<String> violation =
        limitRange.get().violation(descriptor.resourceRequest(), descriptor.resourceLimit());
    return violation
        .<AdmissionDecision<DeploymentSpec>>map(
            reason ->
                AdmissionDecision.reject(
                    "deployment "
                        + spec.name()
                        + " violates tenant "
                        + tenantId
                        + "'s limit range: "
                        + reason))
        .orElseGet(() -> AdmissionDecision.allow(spec));
  }
}
