package com.gimle.core.authz;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Optional;
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
 * gets exactly its own subresources, plus read-only access to the cluster-wide Service/
 * NetworkPolicy sets every node agent needs to do its job, and nothing else, by construction, with
 * no {@link Role} object involved at all.
 */
public final class BuiltinRoles {

  /** Stamped into an issued certificate's {@code O=} for a {@code CsrPurpose.OPERATOR_CLIENT}. */
  public static final String GROUP_OPERATORS = "gimle:operators";

  /** Stamped into an issued certificate's {@code O=} for a {@code CsrPurpose.NODE_CLIENT}. */
  public static final String GROUP_NODES = "gimle:nodes";

  public static final Role CLUSTER_ADMIN = new Role("cluster-admin", everyPermission());

  /**
   * Prefixes of the three per-tenant role templates, each completed by a tenant id: binding a
   * subject to {@code tenant-view:acme} grants read-only visibility into tenant {@code acme}
   * (secrets deliberately excluded, the same posture Kubernetes' own {@code view} role takes),
   * {@code tenant-edit:acme} adds create/update/delete of that tenant's workloads, config, and
   * secrets, and {@code tenant-admin:acme} additionally manages the tenant's own guardrails
   * (NetworkPolicies, LimitRanges). Synthesized on demand from the name -- never stored, never
   * editable via {@code /roles}, exactly like {@link #CLUSTER_ADMIN} -- so every cluster has them
   * for every tenant without anyone hand-authoring the same {@link Role} objects again and again.
   */
  public static final String TENANT_VIEW_PREFIX = "tenant-view:";

  public static final String TENANT_EDIT_PREFIX = "tenant-edit:";
  public static final String TENANT_ADMIN_PREFIX = "tenant-admin:";

  /**
   * Everything the view template may read: a tenant's workloads, its plain config (flat keys and
   * ConfigMaps alike), its Services/NetworkPolicies/LimitRanges, its logs, its artifacts, and the
   * tenant object itself -- but never {@code SECRET}/{@code SECRETMAP}.
   */
  private static final Set<ResourceKind> TENANT_VIEWABLE_KINDS =
      Set.of(
          ResourceKind.DEPLOYMENT,
          ResourceKind.JOB,
          ResourceKind.DAEMONSET,
          ResourceKind.STATEFULSET,
          ResourceKind.TENANT,
          ResourceKind.CONFIG,
          ResourceKind.CONFIGMAP,
          ResourceKind.SERVICE,
          ResourceKind.NETWORK_POLICY,
          ResourceKind.LIMIT_RANGE,
          ResourceKind.LOGS,
          ResourceKind.ARTIFACT);

  /** What the edit template may write and delete on top of everything view can read. */
  private static final Set<ResourceKind> TENANT_EDITABLE_KINDS =
      Set.of(
          ResourceKind.DEPLOYMENT,
          ResourceKind.JOB,
          ResourceKind.DAEMONSET,
          ResourceKind.STATEFULSET,
          ResourceKind.CONFIG,
          ResourceKind.CONFIGMAP,
          ResourceKind.SECRET,
          ResourceKind.SECRETMAP,
          ResourceKind.SERVICE,
          ResourceKind.ARTIFACT);

  /** The tenant guardrails only the admin template may write and delete. */
  private static final Set<ResourceKind> TENANT_ADMIN_ONLY_KINDS =
      Set.of(ResourceKind.NETWORK_POLICY, ResourceKind.LIMIT_RANGE);

  private BuiltinRoles() {}

  /**
   * Synthesizes the per-tenant template {@code roleName} denotes, or empty for any name that isn't
   * one (including a bare prefix with no tenant id after it). The returned {@link Role}'s every
   * {@link Permission} is scoped to exactly the named tenant, so binding one can never leak
   * authority into another tenant, let alone cluster-wide.
   */
  public static Optional<Role> tenantRole(String roleName) {
    if (roleName.startsWith(TENANT_VIEW_PREFIX)) {
      return tenantSuffix(roleName, TENANT_VIEW_PREFIX).map(t -> viewRole(roleName, t));
    }
    if (roleName.startsWith(TENANT_EDIT_PREFIX)) {
      return tenantSuffix(roleName, TENANT_EDIT_PREFIX).map(t -> editRole(roleName, t));
    }
    if (roleName.startsWith(TENANT_ADMIN_PREFIX)) {
      return tenantSuffix(roleName, TENANT_ADMIN_PREFIX).map(t -> adminRole(roleName, t));
    }
    return Optional.empty();
  }

  private static Optional<String> tenantSuffix(String roleName, String prefix) {
    String tenantId = roleName.substring(prefix.length());
    return tenantId.isBlank() ? Optional.empty() : Optional.of(tenantId);
  }

  private static Role viewRole(String roleName, String tenantId) {
    return new Role(roleName, viewPermissions(tenantId));
  }

  private static Role editRole(String roleName, String tenantId) {
    Set<Permission> permissions = viewPermissions(tenantId);
    // Edit may also read the secrets it manages -- view deliberately can't.
    permissions.add(Permission.scoped(ResourceKind.SECRET, Verb.READ, tenantId));
    permissions.add(Permission.scoped(ResourceKind.SECRETMAP, Verb.READ, tenantId));
    for (ResourceKind kind : TENANT_EDITABLE_KINDS) {
      permissions.add(Permission.scoped(kind, Verb.WRITE, tenantId));
      permissions.add(Permission.scoped(kind, Verb.DELETE, tenantId));
    }
    return new Role(roleName, permissions);
  }

  private static Role adminRole(String roleName, String tenantId) {
    Set<Permission> permissions = editRole(roleName, tenantId).permissions();
    Set<Permission> withGuardrails = new LinkedHashSet<>(permissions);
    for (ResourceKind kind : TENANT_ADMIN_ONLY_KINDS) {
      withGuardrails.add(Permission.scoped(kind, Verb.WRITE, tenantId));
      withGuardrails.add(Permission.scoped(kind, Verb.DELETE, tenantId));
    }
    return new Role(roleName, withGuardrails);
  }

  private static Set<Permission> viewPermissions(String tenantId) {
    Set<Permission> permissions = new LinkedHashSet<>();
    for (ResourceKind kind : TENANT_VIEWABLE_KINDS) {
      permissions.add(Permission.scoped(kind, Verb.READ, tenantId));
    }
    return permissions;
  }

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
