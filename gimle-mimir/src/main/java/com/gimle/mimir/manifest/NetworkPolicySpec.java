package com.gimle.mimir.manifest;

import java.util.Optional;
import java.util.Set;

/**
 * The NetworkPolicy analogue named in the platform's own network-model design: a declared,
 * deny-by-default-capable restriction on which other tenants may call into {@code tenantId}'s own
 * Services, enforced independently at the listener side rather than trusting whatever caller-side
 * filtering happened to run first -- the same "forwarded claim, independently re-checked" posture
 * {@code gimle-fafnir}/{@code gimle-muninn}/{@code gimle-andvari} already apply to identity,
 * applied here to cross-tenant fabric traffic.
 *
 * <p>Unlike {@code ServiceExport#allowedTenantIds}, which is {@code Optional} because an export's
 * own absence of a restriction is a meaningful state distinct from "no export exists," a {@code
 * NetworkPolicySpec}'s mere existence already means "a restriction now applies" -- there is no
 * "policy exists but restricts nothing" state worth representing, so {@code allowedCallerTenantIds}
 * is a plain (possibly empty) set: empty means deny every cross-tenant caller.
 *
 * <p>{@code deploymentNames} scopes which of {@code tenantId}'s Services/Deployments this policy
 * covers -- {@code Optional.empty()} means the whole tenant, matching {@code
 * PlacementConstraints#requiredNodeLabels}'s own "absent means unconstrained" convention.
 */
public record NetworkPolicySpec(
    String name,
    String tenantId,
    Optional<Set<String>> deploymentNames,
    Set<String> allowedCallerTenantIds) {

  public NetworkPolicySpec {
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

  /** Convenience: a policy scoped to the whole tenant, not just specific deployments. */
  public NetworkPolicySpec(String name, String tenantId, Set<String> allowedCallerTenantIds) {
    this(name, tenantId, Optional.empty(), allowedCallerTenantIds);
  }

  /**
   * Whether {@code callerTenantId} may call into a Service this policy covers. A caller from {@code
   * tenantId} itself is always permitted -- this restricts cross-tenant traffic, never same-tenant
   * traffic. An absent {@code callerTenantId} (an untenanted caller) is never permitted once a
   * policy exists, the same safe-by-default posture {@code ServiceExport#permitsTenant} already
   * establishes: an untenanted caller can prove membership in no tenant, including this one.
   */
  public boolean permitsCallerTenant(Optional<String> callerTenantId) {
    return callerTenantId.isPresent()
        && (callerTenantId.get().equals(tenantId)
            || allowedCallerTenantIds.contains(callerTenantId.get()));
  }
}
