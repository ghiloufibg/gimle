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
 * <p>{@code deploymentNames} and {@code serviceInterfaceNames} mirror {@code NetworkPolicySpec}'s
 * own fields exactly: {@code Optional.empty()} means unscoped in that dimension, a present set
 * scopes the rule to just those names. {@code allowedCallerTenantIds} (ingress) and {@code
 * allowedCalleeTenantIds} (egress) are each independently optional -- an absent direction imposes
 * no restriction in that direction, an empty set denies every cross-tenant peer in it. See {@link
 * #appliesToDeployment(Optional)} and {@link #appliesToServiceInterface(String)}.
 */
public record NetworkPolicyRule(
    String name,
    String tenantId,
    Optional<Set<String>> deploymentNames,
    Optional<Set<String>> serviceInterfaceNames,
    Optional<Set<String>> allowedCallerTenantIds,
    Optional<Set<String>> allowedCalleeTenantIds) {

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
    deploymentNames = deploymentNames.map(Set::copyOf);
    serviceInterfaceNames = serviceInterfaceNames.map(Set::copyOf);
    allowedCallerTenantIds = allowedCallerTenantIds.map(Set::copyOf);
    allowedCalleeTenantIds = allowedCalleeTenantIds.map(Set::copyOf);
  }

  /** Convenience: a tenant-wide, ingress-only rule. */
  public NetworkPolicyRule(String name, String tenantId, Set<String> allowedCallerTenantIds) {
    this(
        name,
        tenantId,
        Optional.empty(),
        Optional.empty(),
        Optional.of(allowedCallerTenantIds),
        Optional.empty());
  }

  /** Convenience: a deployment-scoped, ingress-only rule. */
  public NetworkPolicyRule(
      String name,
      String tenantId,
      Optional<Set<String>> deploymentNames,
      Set<String> allowedCallerTenantIds) {
    this(
        name,
        tenantId,
        deploymentNames,
        Optional.empty(),
        Optional.of(allowedCallerTenantIds),
        Optional.empty());
  }

  /** Whether this rule restricts inbound (ingress) fabric traffic at all. */
  public boolean restrictsIngress() {
    return allowedCallerTenantIds.isPresent();
  }

  /** Whether this rule restricts outbound (egress) fabric traffic at all. */
  public boolean restrictsEgress() {
    return allowedCalleeTenantIds.isPresent();
  }

  /**
   * Whether {@code callerTenantId} may call into a workload this rule's own {@code tenantId} owns.
   * Identical semantics to {@code NetworkPolicySpec#permitsCallerTenant} -- same-tenant traffic is
   * always allowed, an untenanted caller is never permitted once an ingress restriction exists, and
   * a rule with no ingress restriction permits every caller.
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
   * Whether a workload this rule covers may call out to {@code calleeTenantId} -- {@link
   * #permitsCallerTenant} mirrored in the outbound direction: same-tenant callees always permitted,
   * an untenanted callee never permitted once an egress restriction exists, no egress restriction
   * permits every callee.
   */
  public boolean permitsCalleeTenant(Optional<String> calleeTenantId) {
    if (allowedCalleeTenantIds.isEmpty()) {
      return true;
    }
    return calleeTenantId.isPresent()
        && (calleeTenantId.get().equals(tenantId)
            || allowedCalleeTenantIds.get().contains(calleeTenantId.get()));
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

  /**
   * Whether this rule covers a fabric call targeting {@code interfaceName} -- an interface-unscoped
   * rule covers every call; a scoped rule covers only calls to the named exported service
   * interfaces.
   */
  public boolean appliesToServiceInterface(String interfaceName) {
    return serviceInterfaceNames.isEmpty() || serviceInterfaceNames.get().contains(interfaceName);
  }
}
