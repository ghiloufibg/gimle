package com.gimle.core.authz;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Roles that always exist, are never stored as a {@link Role} object, and are not editable via
 * {@code /roles} -- the built-in escape hatch that makes RBAC additive-only-from-empty-state safe
 * to turn on without locking every existing operator out on day one.
 *
 * <p>{@link #CLUSTER_ADMIN} is bound implicitly to {@link #GROUP_OPERATORS} (an {@code Authorizer}
 * constant check, never a stored {@link RoleBinding}): today, any operator certificate already has
 * unconditional full access, so defaulting the operator group to {@code cluster-admin} changes
 * nothing for a cluster with no custom {@link RoleBinding}s yet. No role is bound to {@link
 * #GROUP_NODES} beyond the node self-service short-circuit in {@code Authorizer} itself -- a node
 * gets exactly its own subresources and nothing else, by construction, with no {@link Role} object
 * involved at all.
 */
public final class BuiltinRoles {

  /** Stamped into an issued certificate's {@code O=} for a {@code CsrPurpose.OPERATOR_CLIENT}. */
  public static final String GROUP_OPERATORS = "gimle:operators";

  /** Stamped into an issued certificate's {@code O=} for a {@code CsrPurpose.NODE_CLIENT}. */
  public static final String GROUP_NODES = "gimle:nodes";

  public static final Role CLUSTER_ADMIN = new Role("cluster-admin", everyPermission());

  private BuiltinRoles() {}

  private static Set<Permission> everyPermission() {
    Set<Permission> permissions = new LinkedHashSet<>();
    for (ResourceKind resource : ResourceKind.values()) {
      for (Verb verb : EnumSet.allOf(Verb.class)) {
        permissions.add(Permission.unscoped(resource, verb));
      }
    }
    return permissions;
  }
}
