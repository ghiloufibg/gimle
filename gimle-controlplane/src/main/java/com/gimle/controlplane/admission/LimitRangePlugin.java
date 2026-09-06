package com.gimle.controlplane.admission;

import com.gimle.controlplane.admission.WorkloadResourceProfile.Profile;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.mimir.manifest.LimitRangeSpec;
import com.gimle.mimir.manifest.WorkloadSpec;
import java.util.Optional;

/**
 * Admission-time check against the submitting tenant's {@link LimitRangeSpec}, if one exists: the
 * per-workload counterpart to {@link TenantQuotaPlugin}'s aggregate check, run first in the
 * admission chain since it's the cheaper single-artifact comparison, with no cross-workload
 * summation to compute. An absent LimitRange for the tenant is a no-op allow -- a LimitRange is
 * opt-in per tenant, not a default every workload must satisfy. The bound check itself is {@link
 * LimitRangeSpec#violation}, shared with {@code LimitRangeReconciler} so the two never drift on
 * what counts as a violation.
 *
 * <p>Whether a LimitRange exists is the <em>only</em> thing that decides here -- the tenant's name
 * is never consulted. A range an operator created against the {@code default} tenant binds every
 * workload there exactly as one created against a named tenant does: an operator who writes a bound
 * has asked for it to be enforced, and the tenant it names is not evidence about that intent.
 *
 * <p>Generic over every placeable {@link WorkloadSpec} kind, not Deployment alone -- see {@link
 * WorkloadResourceProfile}'s own javadoc for how each kind's resource request is extracted. A
 * CronJobSpec is allowed through unconditionally: it is never itself a resource consumer.
 */
public final class LimitRangePlugin implements AdmissionPlugin<WorkloadSpec> {

  @Override
  public AdmissionDecision<WorkloadSpec> review(AdmissionRequest<WorkloadSpec> request) {
    WorkloadSpec spec = request.spec();
    if (spec.tenantId().isEmpty()) {
      return AdmissionDecision.allow(spec);
    }
    String tenantId = spec.tenantId().get();
    Optional<LimitRangeSpec> limitRange = request.store().getLimitRange(tenantId);
    if (limitRange.isEmpty()) {
      return AdmissionDecision.allow(spec);
    }
    Optional<Profile> profile = WorkloadResourceProfile.of(spec, request.store());
    if (profile.isEmpty()) {
      return AdmissionDecision.allow(spec);
    }
    if (request.artifact().isEmpty()) {
      return AdmissionDecision.reject(
          "cannot verify limit range: artifact unreadable at " + profile.get().artifactPath());
    }
    ModuleDescriptor descriptor = request.artifact().get().descriptor();
    Optional<String> violation =
        limitRange.get().violation(descriptor.resourceRequest(), descriptor.resourceLimit());
    return violation
        .<AdmissionDecision<WorkloadSpec>>map(
            reason ->
                AdmissionDecision.reject(
                    "workload "
                        + spec.name()
                        + " violates tenant "
                        + tenantId
                        + "'s limit range: "
                        + reason))
        .orElseGet(() -> AdmissionDecision.allow(spec));
  }
}
