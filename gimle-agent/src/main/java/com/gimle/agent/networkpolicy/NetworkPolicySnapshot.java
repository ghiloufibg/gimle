package com.gimle.agent.networkpolicy;

import com.gimle.core.tenant.NetworkPolicyRule;
import java.util.List;
import java.util.Set;

/**
 * One poll's complete answer about cross-tenant traffic restrictions: every declared rule, plus the
 * tenants whose declared posture closes them to traffic no rule covers.
 *
 * <p>The two travel together rather than as separate fetches a consumer stitches back up, because
 * neither half is safe to act on alone: a posture applied without the rules it defers to would deny
 * calls a policy explicitly permits, and rules applied without the postures would let an uncovered
 * call into a tenant that asked to be closed.
 */
public record NetworkPolicySnapshot(
    List<NetworkPolicyRule> rules, Set<String> denyByDefaultTenantIds) {

  public NetworkPolicySnapshot {
    if (rules == null) {
      throw new IllegalArgumentException("rules must not be null");
    }
    if (denyByDefaultTenantIds == null) {
      throw new IllegalArgumentException("denyByDefaultTenantIds must not be null");
    }
    rules = List.copyOf(rules);
    denyByDefaultTenantIds = Set.copyOf(denyByDefaultTenantIds);
  }

  /** Nothing declared anywhere -- every tenant open, no rule restricting anything. */
  public static NetworkPolicySnapshot empty() {
    return new NetworkPolicySnapshot(List.of(), Set.of());
  }
}
