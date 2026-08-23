package com.gimle.holmgang.topology;

import com.gimle.core.module.ResourceSpec;
import java.util.Optional;

/**
 * A seeded tenant's LimitRange, mirroring the control plane's {@code /limitranges/{tenantId}} body
 * -- a scenario-scoped counterpart to {@link QuotaSpec}, but a plain data holder rather than
 * reusing the production {@code com.gimle.mimir.manifest.LimitRangeSpec} type directly, the same
 * "own package, own type" split {@code QuotaSpec} already establishes relative to {@code
 * com.gimle.core.tenant.ResourceQuota}.
 */
public record LimitRangeSpec(
    Optional<ResourceSpec> minRequest,
    Optional<ResourceSpec> maxRequest,
    Optional<ResourceSpec> minLimit,
    Optional<ResourceSpec> maxLimit) {

  /** The one bound shape every scenario in {@code limitrange.feature} needs today. */
  public static LimitRangeSpec maxRequest(final String memory, final String cpu) {
    return new LimitRangeSpec(
        Optional.empty(),
        Optional.of(new ResourceSpec(memory, cpu)),
        Optional.empty(),
        Optional.empty());
  }
}
