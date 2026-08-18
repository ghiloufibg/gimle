package com.gimle.core.tenant;

import java.util.Optional;
import java.util.Set;

/**
 * The tenant-wide-only wire/runtime projection of {@code gimle-mimir}'s own {@code
 * NetworkPolicySpec} -- a separate, deliberately narrower type rather than a shared one because
 * {@code gimle-core} has no dependency on {@code gimle-mimir} (the dependency runs the other way:
 * {@code gimle-mimir} depends on {@code gimle-core}), so a {@link com.gimle.core.protocol.
 * ControlMessage} carried over the agent&harr;worker control channel can't reference the manifest
 * type directly. Deliberately drops {@code deploymentNames} entirely rather than carrying an
 * always-empty {@code Optional} -- only a tenant-wide {@code NetworkPolicySpec} (one whose {@code
 * deploymentNames} is absent) is ever converted into one of these; a per-deployment-scoped policy
 * is filtered out before it ever reaches this shape, since enforcing it would need this worker to
 * know which deployment(s) it hosts, plumbing the first delivery slice of tenant-wide-only
 * enforcement deliberately doesn't add.
 */
public record NetworkPolicyRule(String name, String tenantId, Set<String> allowedCallerTenantIds) {

  public NetworkPolicyRule {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("network policy name must not be blank");
    }
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("tenantId must not be blank");
    }
    if (allowedCallerTenantIds == null) {
      throw new IllegalArgumentException("allowedCallerTenantIds must not be null");
    }
    allowedCallerTenantIds = Set.copyOf(allowedCallerTenantIds);
  }

  /**
   * Whether {@code callerTenantId} may call into a Service this rule's own {@code tenantId} owns.
   * Identical semantics to {@code NetworkPolicySpec#permitsCallerTenant} -- same-tenant traffic is
   * always allowed, an untenanted caller is never permitted once a rule exists.
   */
  public boolean permitsCallerTenant(Optional<String> callerTenantId) {
    return callerTenantId.isPresent()
        && (callerTenantId.get().equals(tenantId)
            || allowedCallerTenantIds.contains(callerTenantId.get()));
  }
}
