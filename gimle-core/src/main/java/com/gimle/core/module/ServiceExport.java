package com.gimle.core.module;

import java.util.Optional;
import java.util.Set;

/**
 * {@code allowedTenantIds} (Phase 5 design §5.3) restricts which tenants may consume this export:
 * {@code Optional.empty()} means "any tenant may consume this" -- today's implicit behavior,
 * preserved by the two-argument constructor below so every pre-Phase-5 call site (module manifests
 * with no {@code allowedTenants} section, every existing wire message) keeps compiling and behaving
 * unchanged. Enforced in exactly one place: {@code FabricServiceRegistry.lookup} (Phase 4 §8),
 * never at registration or export-declaration time.
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
