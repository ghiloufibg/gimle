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
    return authorize(principal, resource, verb, tenant, targetId, Optional.empty());
  }

  /**
   * The qualifier-carrying variant for {@link ResourceKind#CUSTOM_RESOURCE} requests: {@code
   * qualifier} is the request's own sub-scope -- the target kind name for spec operations, or
   * {@code {kind}/status} for a status write -- matched against each {@link Permission}'s own
   * optional qualifier per {@link Permission#covers}. Every other resource kind passes no qualifier
   * and behaves exactly as before. The implicit {@code gimle:operators} grant remains unconditional
   * -- the bootstrap-level operator credential is this cluster's root, above the spec/status split
   * an explicitly-bound role is subject to.
   */
  public boolean authorize(
      Principal principal,
      ResourceKind resource,
      Verb verb,
      Optional<String> tenant,
      Optional<String> targetId,
      Optional<String> qualifier) {
    if (isNodeSelfService(principal, resource, verb, targetId)) {
      return true;
    }
    if (isNodeTenantScopedConfigRead(principal, resource, verb, tenant)) {
      return true;
    }
    if (isControlPlaneArtifactRead(principal, resource, verb)) {
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
        if (permission.covers(resource, verb, tenant, qualifier)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Whether {@code principal} holds any {@link Verb#READ} grant at all for {@code resource} --
   * unscoped, scoped to any tenant, or reaching it through a wildcard resource/verb position. The
   * widening is delegated to {@link Permission#coversResource}/{@link Permission#coversVerb} rather
   * than comparing the permission's own positions here, so this gate can never admit a narrower set
   * of callers than {@link #authorize} itself would. The collection-list gate: a caller with only
   * tenant-scoped read grants is entitled to a *filtered* listing (each surviving item re-checked
   * through {@link #authorize} with its own tenant), while a caller with no read grant for the kind
   * whatsoever gets the same 403 a single-resource read would -- and this answers which of those
   * two a caller is without having to enumerate every tenant in the cluster.
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
        if (permission.coversResource(resource) && permission.coversVerb(Verb.READ)) {
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
   * NetworkPolicy itself. Not granted for any other resource here: a node has no unscoped access to
   * deployments, tenants, or any other node's endpoints -- CONFIG/CONFIGMAP get their own
   * tenant-scoped grant, {@link #isNodeTenantScopedConfigRead}, since unlike Service/NetworkPolicy
   * a node has no legitimate reason to read a tenant's config it currently has no assignment for.
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
   * A {@code gimle:nodes} principal may {@link Verb#READ} a tenant's {@link ResourceKind#CONFIG}/
   * {@link ResourceKind#CONFIGMAP} the same way {@code gimle-fafnir}'s own {@code FafnirServer}'s
   * {@code decideAllowed} already lets it read that tenant's {@code SECRET}/{@code SECRETMAP} --
   * only if {@link #isTenantAssignedToNode} says this node currently has an active instance
   * assignment for {@code tenant}. Without this, a hosted module needing config or secrets could
   * never start under real mTLS: {@code gimle-controlplane}'s own {@code /config/*}/{@code
   * /configmaps/*} routed every {@code gimle:nodes} read through the ordinary {@link RoleBinding}
   * walk with nothing there to ever match, unlike Fafnir's surfaces, which already had this grant
   * -- confirmed against a real mTLS cluster, where no hosted module for any tenant could ever
   * receive its own config.
   */
  private boolean isNodeTenantScopedConfigRead(
      Principal principal, ResourceKind resource, Verb verb, Optional<String> tenant) {
    if (!principal.groups().contains(BuiltinRoles.GROUP_NODES) || verb != Verb.READ) {
      return false;
    }
    if (resource != ResourceKind.CONFIG && resource != ResourceKind.CONFIGMAP) {
      return false;
    }
    return tenant.isPresent() && isTenantAssignedToNode(principal.name(), tenant.get());
  }

  /**
   * The control plane's own leaf certificate ({@code group:gimle:controlplane}, stamped by {@code
   * PkiBootstrapMain}) may always {@link Verb#READ} the artifact registry, unscoped by tenant or
   * moduleId -- the same unconditional-but-verb-limited shape {@code gimle:nodes} already gets for
   * Service/NetworkPolicy above, not a per-tenant self-service check the way config/secret reads
   * are: {@code DaemonSetReconciler}'s own scheduling-time artifact pull needs to resolve whatever
   * coordinate any tenant's manifest references, before it can even compute eligible nodes, so
   * scoping this to "only tenants this process currently has an assignment for" (a notion that
   * doesn't even apply to the control plane itself) would just break scheduling for the next tenant
   * to onboard. Write/delete stay denied -- the control plane never pushes or deletes an artifact
   * itself, only ever reads one to schedule against it. Without this, a fresh mTLS cluster's own
   * control plane could never pull an artifact it didn't already have cached, and a DaemonSet
   * needing a registry pull to schedule (e.g. a coordinate-only deploy) stalled indefinitely on a
   * repeating 403 with no default RoleBinding to close the gap.
   */
  private static boolean isControlPlaneArtifactRead(
      Principal principal, ResourceKind resource, Verb verb) {
    return principal.groups().contains(BuiltinRoles.GROUP_CONTROLPLANE)
        && resource == ResourceKind.ARTIFACT
        && verb == Verb.READ;
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
   * every assignment across every workload kind ({@link StoreReader#listAssignments()} for
   * Deployment, {@link StoreReader#listJobRuns()} for Job, {@link
   * StoreReader#listDaemonSetAssignments()}, {@link StoreReader#listStatefulSetAssignments()}),
   * filters each to this node, and joins each surviving assignment's own workload name back to its
   * spec to read that workload's own {@code tenantId} -- checking only Deployment originally left a
   * node hosting any tenanted Job/DaemonSet/StatefulSet instance (e.g. {@code gimle-gateway}'s own
   * DaemonSet) permanently unable to read that tenant's data through this check, no matter how long
   * the assignment had existed.
   *
   * <p>Honest limitation, stated rather than hidden: this is tenant-scoped, not per-resource-scoped
   * -- a node with any assignment for a tenant can see every resource that tenant owns under this
   * check, not just the ones its own deployed modules actually declared a dependency on.
   */
  public boolean isTenantAssignedToNode(String nodeId, String tenantId) {
    return deploymentTenantAssignedToNode(nodeId, tenantId)
        || jobTenantAssignedToNode(nodeId, tenantId)
        || daemonSetTenantAssignedToNode(nodeId, tenantId)
        || statefulSetTenantAssignedToNode(nodeId, tenantId);
  }

  private boolean deploymentTenantAssignedToNode(String nodeId, String tenantId) {
    return store.listAssignments().stream()
        .filter(a -> a.nodeId().equals(nodeId))
        .map(a -> store.getDeployment(a.tenantId(), a.deploymentName()))
        .flatMap(Optional::stream)
        .anyMatch(spec -> spec.tenantId().filter(tenantId::equals).isPresent());
  }

  private boolean jobTenantAssignedToNode(String nodeId, String tenantId) {
    return store.listJobRuns().stream()
        .filter(run -> run.nodeId().equals(nodeId))
        .map(run -> store.getJobSpec(run.tenantId(), run.jobName()))
        .flatMap(Optional::stream)
        .anyMatch(spec -> spec.tenantId().filter(tenantId::equals).isPresent());
  }

  private boolean daemonSetTenantAssignedToNode(String nodeId, String tenantId) {
    return store.listDaemonSetAssignments().stream()
        .filter(a -> a.nodeId().equals(nodeId))
        .map(a -> store.getDaemonSetSpec(a.tenantId(), a.daemonSetName()))
        .flatMap(Optional::stream)
        .anyMatch(spec -> spec.tenantId().filter(tenantId::equals).isPresent());
  }

  private boolean statefulSetTenantAssignedToNode(String nodeId, String tenantId) {
    return store.listStatefulSetAssignments().stream()
        .filter(a -> a.nodeId().equals(nodeId))
        .map(a -> store.getStatefulSetSpec(a.tenantId(), a.statefulSetName()))
        .flatMap(Optional::stream)
        .anyMatch(spec -> spec.tenantId().filter(tenantId::equals).isPresent());
  }
}
