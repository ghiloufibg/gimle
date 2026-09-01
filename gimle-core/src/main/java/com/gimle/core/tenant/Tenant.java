package com.gimle.core.tenant;

import java.util.Optional;

/**
 * A tenant's identity and resource quota. Named {@code Tenant} rather than "namespace" because this
 * codebase already uses "namespace" for two other, unrelated concepts (JPMS {@code ModuleLayer}
 * namespacing, Linux namespace isolation); reusing the word here would invite exactly the ambiguity
 * this project's naming conventions elsewhere try to avoid.
 *
 * <p>{@code isolationPosture} is the tenant's baseline stance on cross-tenant fabric traffic when
 * no {@code NetworkPolicySpec} covers a call -- see {@link TenantIsolationPosture}. It lives on the
 * tenant rather than on a policy because it has to mean something before the tenant's first policy
 * exists, which is exactly the window a policy-shaped answer cannot cover.
 */
public record Tenant(String id, ResourceQuota quota, TenantIsolationPosture isolationPosture) {

  /**
   * The platform's own reserved tenant, the {@code kube-system} equivalent -- where self-hosted
   * platform extensions run. Reserved at the HTTP layer (see {@code ApiServer}'s own tenant and
   * workload-admission guards) rather than here: a plain record constructor has no way to know who
   * is calling, only whether the arguments it was given are individually well-formed.
   */
  public static final String RESERVED_SYSTEM_TENANT_ID = "gimle-system";

  /**
   * The {@code default} namespace equivalent: every workload manifest that omits {@code tenantId}
   * resolves to this tenant at manifest-parse time (see {@code ManifestFields#parseTenantId}),
   * rather than leaving "untenanted" a valid-but-broken state with no addressable config/secret
   * bucket. Auto-seeded the same way {@link #RESERVED_SYSTEM_TENANT_ID} is, but otherwise an
   * ordinary, unreserved tenant -- an operator may freely adjust its quota, unlike the reserved
   * system tenant's own write/delete guard.
   */
  public static final String DEFAULT_TENANT_ID = "default";

  /**
   * True only for a tenant an operator actually created and can be held accountable to something as
   * a real tenant -- {@link #DEFAULT_TENANT_ID} counts the same as {@code Optional.empty()} here,
   * matching real Kubernetes: the {@code default} namespace carries no {@code ResourceQuota} object
   * unless an admin explicitly creates one, so nothing is enforced against it by default either.
   * Used by every admission plugin/reconciler that sums or caps resource usage per tenant ({@code
   * TenantQuotaPlugin}, {@code LimitRangePlugin}, {@code PolicyConfigPlugin}, {@code
   * QuotaReconciler}, {@code LimitRangeReconciler}), and by {@code ApiServer#admissionArtifact}'s
   * registry-coordinate ownership check (a workload that never declared a real tenant has nothing
   * for a tenant-mismatch check to protect) -- deliberately <em>not</em> used by the config
   * addressability path ({@code Authorizer#isNodeTenantScopedConfigRead}) or the scheduler's own
   * node-taint check, both of which are meant to treat {@code default} as a real, ordinary tenant
   * now that it exists.
   */
  public static boolean isEnforceable(Optional<String> tenantId) {
    return tenantId.isPresent() && !tenantId.get().equals(DEFAULT_TENANT_ID);
  }

  public Tenant {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("tenant id must not be blank");
    }
    if (quota == null) {
      throw new IllegalArgumentException("quota must not be null");
    }
    if (isolationPosture == null) {
      throw new IllegalArgumentException("isolationPosture must not be null");
    }
  }

  /**
   * A tenant with the default {@link TenantIsolationPosture#OPEN} posture -- the shape every caller
   * that has no opinion about cross-tenant traffic uses, so an operator has to ask for a closed
   * tenant explicitly rather than inherit one by accident.
   */
  public Tenant(String id, ResourceQuota quota) {
    this(id, quota, TenantIsolationPosture.OPEN);
  }

  /** Whether an uncovered cross-tenant call into or out of this tenant is denied by default. */
  public boolean deniesByDefault() {
    return isolationPosture == TenantIsolationPosture.DENY_BY_DEFAULT;
  }
}
