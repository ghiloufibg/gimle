package com.gimle.core.tenant;

/**
 * A tenant's baseline stance on cross-tenant fabric traffic, applied whenever no declared {@code
 * NetworkPolicySpec} covers a given call. Without it, a brand-new tenant -- whose policy list is
 * necessarily empty -- is fully open to cross-tenant callers until an operator remembers to write
 * its first policy, and there is no shorthand for "closed, pending real policies."
 *
 * <p>{@link #OPEN} is the default for every tenant, so declaring a posture is an opt-in: an
 * operator closes a tenant deliberately, rather than discovering after the fact that traffic
 * stopped flowing.
 */
public enum TenantIsolationPosture {

  /**
   * No baseline restriction: a call not covered by any policy is permitted, and only the policies
   * that do cover it decide. The starting state of every tenant.
   */
  OPEN,

  /**
   * A call not covered by any policy is denied when it crosses a tenant boundary. Same-tenant
   * traffic is always permitted -- this is a cross-tenant posture, never a self-isolation switch --
   * and a policy that does cover a call still decides that call on its own terms, so a posture is a
   * fallback, not an override.
   */
  DENY_BY_DEFAULT
}
