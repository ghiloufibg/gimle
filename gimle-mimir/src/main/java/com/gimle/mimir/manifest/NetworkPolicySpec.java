package com.gimle.mimir.manifest;

import java.util.Optional;
import java.util.Set;

/**
 * The NetworkPolicy analogue named in the platform's own network-model design: a declared,
 * deny-by-default-capable restriction on cross-tenant fabric traffic touching {@code tenantId}'s
 * own workloads, enforced independently at the listener side rather than trusting whatever
 * caller-side filtering happened to run first -- the same "forwarded claim, independently
 * re-checked" posture {@code gimle-fafnir}/{@code gimle-muninn}/{@code gimle-andvari} already apply
 * to identity, applied here to cross-tenant fabric traffic.
 *
 * <p>A policy carries up to two directions, each optional but at least one required:
 *
 * <ul>
 *   <li><b>Ingress</b> ({@code allowedCallerTenantIds} present): which other tenants may call into
 *       the covered workloads. An empty set means only same-tenant callers.
 *   <li><b>Egress</b> ({@code allowedCalleeTenantIds} present): which other tenants the covered
 *       workloads may themselves call over the fabric. An empty set means only same-tenant callees.
 *       This governs fabric calls only -- raw sockets a module opens itself are out of any
 *       JVM-portable policy's reach.
 * </ul>
 *
 * <p>Within each direction, absence and emptiness are distinct states: an absent set means "this
 * policy imposes no restriction in that direction," while an empty set means "deny every
 * cross-tenant peer in that direction." A policy with both directions absent restricts nothing and
 * is rejected outright rather than stored as an inert object.
 *
 * <p>{@code deploymentNames} scopes which of {@code tenantId}'s workloads this policy covers --
 * {@code Optional.empty()} means the whole tenant, matching {@code
 * PlacementConstraints#requiredNodeLabels}'s own "absent means unconstrained" convention. {@code
 * serviceInterfaceNames} narrows the policy further to fabric calls targeting those exported
 * service interfaces (fully-qualified interface names); absent means every interface.
 */
public record NetworkPolicySpec(
    String name,
    String tenantId,
    Optional<Set<String>> deploymentNames,
    Optional<Set<String>> serviceInterfaceNames,
    Optional<Set<String>> allowedCallerTenantIds,
    Optional<Set<String>> allowedCalleeTenantIds) {

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
    if (serviceInterfaceNames == null) {
      throw new IllegalArgumentException(
          "serviceInterfaceNames must be Optional.empty(), not null");
    }
    if (allowedCallerTenantIds == null) {
      throw new IllegalArgumentException(
          "allowedCallerTenantIds must be Optional.empty(), not null");
    }
    if (allowedCalleeTenantIds == null) {
      throw new IllegalArgumentException(
          "allowedCalleeTenantIds must be Optional.empty(), not null");
    }
    if (allowedCallerTenantIds.isEmpty() && allowedCalleeTenantIds.isEmpty()) {
      throw new IllegalArgumentException(
          "a network policy must restrict at least one direction (ingress or egress)");
    }
    deploymentNames = deploymentNames.map(Set::copyOf);
    serviceInterfaceNames = serviceInterfaceNames.map(Set::copyOf);
    allowedCallerTenantIds = allowedCallerTenantIds.map(Set::copyOf);
    allowedCalleeTenantIds = allowedCalleeTenantIds.map(Set::copyOf);
  }

  /** Convenience: an ingress-only policy scoped to the whole tenant. */
  public NetworkPolicySpec(String name, String tenantId, Set<String> allowedCallerTenantIds) {
    this(
        name,
        tenantId,
        Optional.empty(),
        Optional.empty(),
        Optional.of(nonNullCallers(allowedCallerTenantIds)),
        Optional.empty());
  }

  /**
   * Convenience: an ingress-only policy, optionally deployment-scoped -- mirrors {@code
   * NetworkPolicyRule}'s own equivalent constructor.
   */
  public NetworkPolicySpec(
      String name,
      String tenantId,
      Optional<Set<String>> deploymentNames,
      Set<String> allowedCallerTenantIds) {
    this(
        name,
        tenantId,
        deploymentNames,
        Optional.empty(),
        Optional.of(nonNullCallers(allowedCallerTenantIds)),
        Optional.empty());
  }

  private static Set<String> nonNullCallers(Set<String> allowedCallerTenantIds) {
    if (allowedCallerTenantIds == null) {
      throw new IllegalArgumentException("allowedCallerTenantIds must not be null");
    }
    return allowedCallerTenantIds;
  }

  /** Whether this policy restricts inbound (ingress) fabric traffic at all. */
  public boolean restrictsIngress() {
    return allowedCallerTenantIds.isPresent();
  }

  /** Whether this policy restricts outbound (egress) fabric traffic at all. */
  public boolean restrictsEgress() {
    return allowedCalleeTenantIds.isPresent();
  }

  /**
   * Whether {@code callerTenantId} may call into a workload this policy covers. A caller from
   * {@code tenantId} itself is always permitted -- this restricts cross-tenant traffic, never
   * same-tenant traffic. An absent {@code callerTenantId} (an untenanted caller) is never permitted
   * once an ingress restriction exists, the same safe-by-default posture {@code
   * ServiceExport#permitsTenant} already establishes: an untenanted caller can prove membership in
   * no tenant, including this one. A policy with no ingress restriction permits every caller.
   */
  public boolean permitsCallerTenant(Optional<String> callerTenantId) {
    if (allowedCallerTenantIds.isEmpty()) {
      return true;
    }
    return callerTenantId.isPresent()
        && (callerTenantId.get().equals(tenantId)
            || allowedCallerTenantIds.get().contains(callerTenantId.get()));
  }

  /**
   * Whether a workload this policy covers may call out to {@code calleeTenantId}, mirroring {@link
   * #permitsCallerTenant} in the outbound direction: same-tenant callees are always permitted, an
   * untenanted callee is never permitted once an egress restriction exists, and a policy with no
   * egress restriction permits every callee.
   */
  public boolean permitsCalleeTenant(Optional<String> calleeTenantId) {
    if (allowedCalleeTenantIds.isEmpty()) {
      return true;
    }
    return calleeTenantId.isPresent()
        && (calleeTenantId.get().equals(tenantId)
            || allowedCalleeTenantIds.get().contains(calleeTenantId.get()));
  }
}
