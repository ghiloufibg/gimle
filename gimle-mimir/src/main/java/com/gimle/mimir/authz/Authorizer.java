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
    if (isNodeSelfService(principal, resource, verb, targetId)) {
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
      Optional<Role> role = resolveRole(binding.roleName());
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
   * Whether {@code principal} holds any {@link Verb#READ} grant at all for {@code resource} --
   * unscoped or scoped to any tenant. The collection-list gate: a caller with only tenant-scoped
   * read grants is entitled to a *filtered* listing (each surviving item re-checked through {@link
   * #authorize} with its own tenant), while a caller with no read grant for the kind whatsoever
   * gets the same 403 a single-resource read would -- and this answers which of those two a caller
   * is without having to enumerate every tenant in the cluster.
   */
  public boolean hasAnyReadGrant(Principal principal, ResourceKind resource) {
    if (isNodeSelfService(principal, resource, Verb.READ, Optional.empty())) {
      return true;
    }
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
      Optional<Role> role = resolveRole(binding.roleName());
      if (role.isEmpty()) {
        continue;
      }
      for (Permission permission : role.get().permissions()) {
        if (permission.resource() == resource && permission.verb() == Verb.READ) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * {@link BuiltinRoles#CLUSTER_ADMIN} is deliberately never a stored {@link Role} (see its own
   * javadoc), so a plain {@code store.getRole(binding.roleName())} can never resolve an explicit
   * {@link RoleBinding} naming it by name -- only the separate {@code GROUP_OPERATORS} constant
   * check above ever granted it. That leaves the exact flow {@code PkiBootstrapMain} prints as this
   * cluster's own first-login instructions ({@code gimle set rolebinding ... --role cluster-admin})
   * permanently unsatisfiable: the binding gets created, but every authorization check against it
   * silently falls through to "no matching role, no permission" forever. Recognizing the built-in's
   * name here, falling back to the store only for anything else, is what actually makes binding
   * {@code cluster-admin} to a specific user (not just the whole operator group) work.
   */
  private Optional<Role> resolveRole(String roleName) {
    if (BuiltinRoles.CLUSTER_ADMIN.name().equals(roleName)) {
      return Optional.of(BuiltinRoles.CLUSTER_ADMIN);
    }
    // The per-tenant view/edit/admin templates are synthesized from the name for the same reason
    // cluster-admin is recognized above: none of them is ever a stored Role, so only resolving
    // them here makes a binding to one actually grant anything.
    Optional<Role> tenantTemplate = BuiltinRoles.tenantRole(roleName);
    if (tenantTemplate.isPresent()) {
      return tenantTemplate;
    }
    return store.getRole(roleName);
  }

  /**
   * A {@code gimle:nodes} principal may always act on its own node/log endpoints -- {@code
   * targetId} equal to its own name -- with no {@link RoleBinding} needing to exist for it. It may
   * also always {@link Verb#READ} the cluster-wide {@link ResourceKind#SERVICE}/{@link
   * ResourceKind#NETWORK_POLICY} sets, unscoped by tenant or target: every node agent polls both
   * ({@code NetworkPolicyRelay}, to relay the full policy set down to its supervised workers; a
   * Bifrost-enabled agent's own {@code ServiceSource}, to know every Service it might need to front
   * a local proxy for) as an unavoidable part of its own job, not as a per-tenant privilege -- a
   * node cannot know in advance which tenants' Services/NetworkPolicies its future assignments will
   * need, and both relays are deliberately unfiltered by design (see {@code NetworkPolicyRelay}'s
   * own javadoc), so scoping this to only-currently-assigned tenants would just break on the next
   * reassignment. Write/delete on either stays denied -- a node never declares a Service or
   * NetworkPolicy itself. Not granted for any other resource: a node has no access to deployments,
   * tenants, config, or any other node's endpoints, under this design a real tightening versus the
   * pre-RBAC baseline where any valid certificate could hit every route.
   */
  private static boolean isNodeSelfService(
      Principal principal, ResourceKind resource, Verb verb, Optional<String> targetId) {
    if (!principal.groups().contains(BuiltinRoles.GROUP_NODES)) {
      return false;
    }
    if (resource == ResourceKind.SERVICE || resource == ResourceKind.NETWORK_POLICY) {
      return verb == Verb.READ;
    }
    if (resource != ResourceKind.NODE && resource != ResourceKind.LOGS) {
      return false;
    }
    return targetId.isPresent() && targetId.get().equals(principal.name());
  }

  /**
   * Node-authorization mode, mirroring Kubernetes' own Node authorization + NodeRestriction: a
   * {@code gimle:nodes} principal (a node agent's own certificate identity, {@code CN=nodeId}) may
   * read a tenant's data only if that node currently has at least one active instance assignment
   * for that tenant -- read-only by construction (this method answers "is this node entitled to see
   * this tenant's data at all," never distinguishes a verb), and never resolved through the
   * ordinary {@link #authorize} walk, since a node certificate has no {@link Role}/{@link
   * RoleBinding} of its own to check against. Originally private to {@code gimle-fafnir}'s own
   * {@code FafnirServer}; lifted here once {@code gimle-controlplane}'s own {@code /endpoints/*}
   * route needed the identical check and duplicating it a second time stopped being defensible.
   *
   * <p>There is no direct "assignments for this node" query on {@link StoreReader} -- {@code
   * listAssignmentsFor(String deploymentName)} is deployment-scoped, not node-scoped. So this walks
   * every assignment via {@link StoreReader#listAssignments()}, filters to this node, and joins
   * each surviving assignment's {@code deploymentName} back to its {@code DeploymentSpec} to read
   * that deployment's own {@code tenantId}.
   *
   * <p>Honest limitation, stated rather than hidden: this is tenant-scoped, not per-resource-scoped
   * -- a node with any assignment for a tenant can see every resource that tenant owns under this
   * check, not just the ones its own deployed modules actually declared a dependency on.
   */
  public boolean isTenantAssignedToNode(String nodeId, String tenantId) {
    return store.listAssignments().stream()
        .filter(a -> a.nodeId().equals(nodeId))
        .map(a -> store.getDeployment(a.deploymentName()))
        .flatMap(Optional::stream)
        .anyMatch(spec -> spec.tenantId().filter(tenantId::equals).isPresent());
  }
}
