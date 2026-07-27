package com.gimle.core.module;

import java.util.Optional;
import java.util.Set;

/**
 * A service interface a module publishes, at a specific version, optionally restricted to a set of
 * tenants. {@code allowedTenantIds} of {@code Optional.empty()} means any tenant may consume this
 * export; the two-argument constructor defaults to that for callers that don't need tenant scoping.
 * Tenant restrictions are enforced by the service registry's lookup path, never at registration or
 * export-declaration time.
 */
public record ServiceExport(
    String interfaceName, Version version, Optional<Set<String>> allowedTenantIds) {

  public ServiceExport {
    if (interfaceName == null || interfaceName.isBlank()) {
      throw new IllegalArgumentException("interface name must not be blank");
    }
    if (version == null) {
      throw new IllegalArgumentException("version must not be null");
    }
    if (allowedTenantIds == null) {
      throw new IllegalArgumentException("allowedTenantIds must be Optional.empty(), not null");
    }
    allowedTenantIds = allowedTenantIds.map(Set::copyOf);
  }

  public ServiceExport(String interfaceName, Version version) {
    this(interfaceName, version, Optional.empty());
  }

  /**
   * Whether a caller belonging to {@code tenantId} (absent for an untenanted caller) may consume
   * this export.
   */
  public boolean permitsTenant(Optional<String> tenantId) {
    return allowedTenantIds.isEmpty()
        || (tenantId.isPresent() && allowedTenantIds.get().contains(tenantId.get()));
  }
}
