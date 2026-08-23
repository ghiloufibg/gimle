package com.gimle.controlplane.admission;

import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ResourceSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.LimitRangeSpec;
import java.util.Optional;

/**
 * Admission-time check against the submitting tenant's {@link LimitRangeSpec}, if one exists: the
 * per-workload counterpart to {@link TenantQuotaPlugin}'s aggregate check, run first in the
 * deployment admission chain since it's the cheaper single-artifact comparison, with no
 * cross-deployment summation to compute. Absent {@code tenantId} or an absent LimitRange for the
 * tenant are both a no-op allow -- a LimitRange is opt-in per tenant, not a default every
 * deployment must satisfy.
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
    LimitRangeSpec range = limitRange.get();
    Optional<String> violation =
        boundViolation(
                "request", descriptor.resourceRequest(), range.minRequest(), range.maxRequest())
            .or(
                () ->
                    boundViolation(
                        "limit", descriptor.resourceLimit(), range.minLimit(), range.maxLimit()));
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

  /** Inclusive at both ends -- a value exactly at min or max satisfies the bound. */
  private static Optional<String> boundViolation(
      String pairName,
      ResourceSpec actual,
      Optional<ResourceSpec> min,
      Optional<ResourceSpec> max) {
    if (min.isPresent() && actual.memoryBytes() < min.get().memoryBytes()) {
      return Optional.of(
          pairName + " memory " + actual.memory() + " below minimum " + min.get().memory());
    }
    if (min.isPresent() && actual.cpuMillicores() < min.get().cpuMillicores()) {
      return Optional.of(pairName + " cpu " + actual.cpu() + " below minimum " + min.get().cpu());
    }
    if (max.isPresent() && actual.memoryBytes() > max.get().memoryBytes()) {
      return Optional.of(
          pairName + " memory " + actual.memory() + " above maximum " + max.get().memory());
    }
    if (max.isPresent() && actual.cpuMillicores() > max.get().cpuMillicores()) {
      return Optional.of(pairName + " cpu " + actual.cpu() + " above maximum " + max.get().cpu());
    }
    return Optional.empty();
  }
}
