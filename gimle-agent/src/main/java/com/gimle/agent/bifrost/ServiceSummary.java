package com.gimle.agent.bifrost;

import java.util.Optional;
import java.util.Set;

/**
 * A Service's own identity fields beyond its name -- {@code tenantId}/{@code deploymentNames}, the
 * same shape {@code GET /services} already returns per entry ({@code ApiServer#serviceToJson}) --
 * that {@link BifrostProxy} needs to decide whether a {@code NetworkPolicySpec} currently restricts
 * it (see {@link ServiceListener#setRestricted}). Endpoints/port are fetched separately via {@link
 * ServiceSource#fetchEndpoints}, unchanged.
 */
public record ServiceSummary(String name, Optional<String> tenantId, Set<String> deploymentNames) {

  public ServiceSummary {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("service name must not be blank");
    }
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must be Optional.empty(), not null");
    }
    if (deploymentNames == null) {
      throw new IllegalArgumentException("deploymentNames must not be null");
    }
    deploymentNames = Set.copyOf(deploymentNames);
  }

  /** Convenience: an untenanted service fronting no particular named deployment. */
  public ServiceSummary(String name) {
    this(name, Optional.empty(), Set.of());
  }
}
