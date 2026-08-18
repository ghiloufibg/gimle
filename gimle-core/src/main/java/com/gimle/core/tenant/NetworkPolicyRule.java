package com.gimle.core.tenant;

import java.util.Optional;
import java.util.Set;

/**
 * The wire/runtime projection of {@code gimle-mimir}'s own {@code NetworkPolicySpec} -- a separate,
 * deliberately narrower type rather than a shared one because {@code gimle-core} has no dependency
 * on {@code gimle-mimir} (the dependency runs the other way: {@code gimle-mimir} depends on {@code
 * gimle-core}), so a {@link com.gimle.core.protocol.ControlMessage} carried over the
 * agent&harr;worker control channel can't reference the manifest type directly.
 *
 * <p>{@code deploymentNames} mirrors {@code NetworkPolicySpec}'s own field exactly: {@code
 * Optional.empty()} means the whole tenant, a present set scopes the rule to just those deployment
 * names. See {@link #appliesToDeployment(Optional)}.
 */
public record NetworkPolicyRule(
    String name,
    String tenantId,
    Optional<Set<String>> deploymentNames,
    Set<String> allowedCallerTenantIds) {

  public NetworkPolicyRule {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("network policy name must not be blank");
    }
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("tenantId must not be blank");
    }
    if (deploymentNames == null) {
      throw new IllegalArgumentException("deploymentNames must be Optional.empty(), not null");
    }
    if (allowedCallerTenantIds == null) {
      throw new IllegalArgumentException("allowedCallerTenantIds must not be null");
    }
    deploymentNames = deploymentNames.map(Set::copyOf);
    allowedCallerTenantIds = Set.copyOf(allowedCallerTenantIds);
  }

  /** Back-compat: a tenant-wide rule, the only shape this type used to be able to express. */
  public NetworkPolicyRule(String name, String tenantId, Set<String> allowedCallerTenantIds) {
    this(name, tenantId, Optional.empty(), allowedCallerTenantIds);
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

  /**
   * Whether this rule covers a target instance belonging to {@code targetDeploymentName} -- a
   * tenant-wide rule ({@link #deploymentNames} absent) covers every deployment unconditionally; a
   * deployment-scoped rule covers only a named deployment, and deliberately does <em>not</em> match
   * when {@code targetDeploymentName} itself is absent (an instance the platform never assigned a
   * deployment identity to, e.g. in a test) -- a scoped rule can only ever be proven to apply, not
   * assumed to, so an unidentified target is treated as out of its scope rather than caught by it.
   */
  public boolean appliesToDeployment(Optional<String> targetDeploymentName) {
    return deploymentNames.isEmpty()
        || (targetDeploymentName.isPresent()
            && deploymentNames.get().contains(targetDeploymentName.get()));
  }
}
