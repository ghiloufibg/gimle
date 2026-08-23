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
  }

  // Each of the two pairs (request, limit) is checked independently -- there is no meaningful
  // relationship between e.g. minRequest and maxLimit worth enforcing here.
  private static void requireMinNotAboveMax(
      Optional<ResourceSpec> min, Optional<ResourceSpec> max, String pairName) {
    if (min.isEmpty() || max.isEmpty()) {
      return;
    }
    ResourceSpec minSpec = min.get();
    ResourceSpec maxSpec = max.get();
    if (minSpec.memoryBytes() > maxSpec.memoryBytes()) {
      throw new IllegalArgumentException(
          "min"
              + pairName
              + " memory exceeds max"
              + pairName
              + ": "
              + minSpec.memory()
              + " > "
              + maxSpec.memory());
    }
    if (minSpec.cpuMillicores() > maxSpec.cpuMillicores()) {
      throw new IllegalArgumentException(
          "min"
              + pairName
              + " cpu exceeds max"
              + pairName
              + ": "
              + minSpec.cpu()
              + " > "
              + maxSpec.cpu());
    }
  }
}
