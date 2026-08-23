package com.gimle.mimir.manifest;

import com.gimle.core.module.ResourceSpec;
import java.util.Optional;

/**
 * The LimitRange analogue named in the platform's own Kubernetes-Kind-coverage review: a per-tenant
 * min/max bound on what a single Deployment's own {@code resources.request}/{@code resources.limit}
 * may declare, distinct from {@code Tenant}'s own {@code ResourceQuota}, which only bounds the
 * aggregate sum across every one of a tenant's deployments -- absent a LimitRange, one deployment
 * can consume most of a tenant's quota by itself as long as the sum still fits.
 *
 * <p>One LimitRange per tenant, keyed by {@code tenantId} directly -- the same "identity is the
 * tenant scope, no separate name" shape {@code Tenant} itself already establishes, not {@code
 * NetworkPolicySpec}'s (which needs a separate {@code name} because one tenant can have several
 * distinct policies).
 *
 * <p>Every bound is {@code Optional} -- a tenant may constrain only requests, only limits, or only
 * one side of either pair. There is deliberately no {@code default} bound: {@code
 * resources.request}/{@code resources.limit} are always-required on a module's own manifest, so
 * there is no omitted-value case for a default to inject.
 */
public record LimitRangeSpec(
    String tenantId,
    Optional<ResourceSpec> minRequest,
    Optional<ResourceSpec> maxRequest,
    Optional<ResourceSpec> minLimit,
    Optional<ResourceSpec> maxLimit) {

  public LimitRangeSpec {
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("tenantId must not be blank");
    }
    if (minRequest == null || maxRequest == null || minLimit == null || maxLimit == null) {
      throw new IllegalArgumentException("bound fields must be Optional.empty(), not null");
    }
    requireMinNotAboveMax(minRequest, maxRequest, "request");
    requireMinNotAboveMax(minLimit, maxLimit, "limit");
    requireRequestFloorNotAboveLimitCeiling(minRequest, maxLimit);
  }

  // Each of the two pairs (request, limit) is checked independently here -- see
  // requireRequestFloorNotAboveLimitCeiling below for the one cross-pair relationship that *is*
  // meaningful.
  private static void requireMinNotAboveMax(
      Optional<ResourceSpec> min, Optional<ResourceSpec> max, String pairName) {
    if (min.isEmpty() || max.isEmpty()) {
      return;
    }
    ResourceSpec minSpec = min.get();
    ResourceSpec maxSpec = max.get();
    if (minSpec.memoryBytes() > maxSpec.memoryBytes()) {
      throw new IllegalArgumentException(
          "min "
              + pairName
              + " memory exceeds max "
              + pairName
              + ": "
              + minSpec.memory()
              + " > "
              + maxSpec.memory());
    }
    if (minSpec.cpuMillicores() > maxSpec.cpuMillicores()) {
      throw new IllegalArgumentException(
          "min "
              + pairName
              + " cpu exceeds max "
              + pairName
              + ": "
              + minSpec.cpu()
              + " > "
              + maxSpec.cpu());
    }
  }

  // ModuleDescriptor's own compact constructor already requires resourceRequest <= resourceLimit
  // on every manifest, unconditionally -- so a minRequest above maxLimit isn't just a stricter
  // range, it's one no manifest could ever satisfy (its request would have to be >= minRequest >
  // maxLimit >= its own limit, contradicting that invariant). Unlike the two same-pair checks
  // above, this is the one cross-pair relationship worth rejecting at construction time, before it
  // silently locks a tenant out of ever deploying anything.
  private static void requireRequestFloorNotAboveLimitCeiling(
      Optional<ResourceSpec> minRequest, Optional<ResourceSpec> maxLimit) {
    if (minRequest.isEmpty() || maxLimit.isEmpty()) {
      return;
    }
    ResourceSpec min = minRequest.get();
    ResourceSpec max = maxLimit.get();
    if (min.memoryBytes() > max.memoryBytes()) {
      throw new IllegalArgumentException(
          "min request memory exceeds max limit memory, so no manifest could ever comply: "
              + min.memory()
              + " > "
              + max.memory());
    }
    if (min.cpuMillicores() > max.cpuMillicores()) {
      throw new IllegalArgumentException(
          "min request cpu exceeds max limit cpu, so no manifest could ever comply: "
              + min.cpu()
              + " > "
              + max.cpu());
    }
  }

  /**
   * The single per-workload compliance check shared by the admission plugin ({@code
   * LimitRangePlugin}) and the continuous reconciler ({@code LimitRangeReconciler}): a description
   * of the first bound {@code request}/{@code limit} violates, or empty if both satisfy every bound
   * this range declares. Inclusive at both ends -- a value exactly at min or max satisfies the
   * bound.
   */
  public Optional<String> violation(ResourceSpec request, ResourceSpec limit) {
    return boundViolation("request", request, minRequest, maxRequest)
        .or(() -> boundViolation("limit", limit, minLimit, maxLimit));
  }

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
