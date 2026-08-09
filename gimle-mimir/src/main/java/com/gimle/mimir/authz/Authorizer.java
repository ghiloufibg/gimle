package com.gimle.mimir.authz;

import com.gimle.core.authz.BuiltinRoles;
import com.gimle.core.authz.Permission;
import com.gimle.core.authz.Principal;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Role;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.authz.Verb;
import com.gimle.mimir.store.StoreReader;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves whether {@code principal} may perform {@code verb} on {@code resource}. Reads the store
 * directly rather than caching: every existing reconciler in this codebase already re-derives its
 * decision from the store on every tick rather than tracking deltas (the same level-triggered
 * posture), and an authorization check happens once per request, not in a hot loop, so there is no
 * performance reason to diverge from that pattern here. Takes {@link StoreReader} rather than a
 * concrete {@code StateStore} so any store-backed process can pass its own {@code StoreClient}
 * while tests keep constructing a plain, network-free {@code StateStore}.
 *
 * <p>Lives in {@code gimle-mimir} rather than {@code gimle-core} because it depends on {@link
 * StoreReader}, which itself belongs in {@code gimle-mimir} (tightly coupled to {@code
 * StateStore}). Every process that needs its own authorization decision -- {@code
 * gimle-controlplane} today, {@code gimle-fafnir} independently re-checking a forwarded request
 * rather than trusting the proxy alone -- already depends on {@code gimle-mimir} for {@code
 * StoreClient}, so this adds no new coupling in either direction.
 */
public final class Authorizer {

  private final StoreReader store;

  public Authorizer(StoreReader store) {
    this.store = store;
  }

  /**
   * {@code targetId} is the specific resource instance being acted on when that matters for
   * self-service (a node's own {@code nodeId}) -- empty for resource kinds/actions where no single
   * target identity is relevant (e.g. listing every deployment). {@code tenant} is the request's
   * own tenant scope, matched against a {@link Permission}'s {@code tenantScope} per {@link
   * Permission#covers}.
   */
  public boolean authorize(
      Principal principal,
      ResourceKind resource,
      Verb verb,
      Optional<String> tenant,
      Optional<String> targetId) {
    if (isNodeSelfService(principal, resource, targetId)) {
      return true;
    }
    // group:gimle:operators is bound to the built-in cluster-admin role implicitly -- not a stored
    // RoleBinding, a constant check here, matching BuiltinRoles' own javadoc.
    if (principal.groups().contains(BuiltinRoles.GROUP_OPERATORS)) {
      return true;
    }
    Set<String> matchingSubjects = new LinkedHashSet<>();
    matchingSubjects.add(RoleBinding.userSubject(principal.name()));
    for (String group : principal.groups()) {
      matchingSubjects.add(RoleBinding.groupSubject(group));
    }
    for (RoleBinding binding : store.listRoleBindings()) {
      if (!matchingSubjects.contains(binding.subject())) {
        continue;
      }
      Optional<Role> role = store.getRole(binding.roleName());
      if (role.isEmpty()) {
        continue;
      }
      for (Permission permission : role.get().permissions()) {
        if (permission.covers(resource, verb, tenant)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * A {@code gimle:nodes} principal may always act on its own node/log endpoints -- {@code
   * targetId} equal to its own name -- with no {@link RoleBinding} needing to exist for it. Not
   * granted for any other resource: a node has no access to deployments, tenants, config, or any
   * other node's endpoints, under this design a real tightening versus the pre-RBAC baseline where
   * any valid certificate could hit every route.
   */
  private static boolean isNodeSelfService(
      Principal principal, ResourceKind resource, Optional<String> targetId) {
    if (!principal.groups().contains(BuiltinRoles.GROUP_NODES)) {
      return false;
    }
    if (resource != ResourceKind.NODE && resource != ResourceKind.LOGS) {
      return false;
    }
    return targetId.isPresent() && targetId.get().equals(principal.name());
  }
}
