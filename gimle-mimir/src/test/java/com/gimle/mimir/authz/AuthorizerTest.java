package com.gimle.mimir.authz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.authz.BuiltinRoles;
import com.gimle.core.authz.Permission;
import com.gimle.core.authz.Principal;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Role;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.authz.Verb;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.StateStore;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

class AuthorizerTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private Authorizer authorizer(String name) {
    return new Authorizer(new StateStore());
  }

  @Test
  void a_principal_with_no_binding_and_no_group_is_denied_everything() {
    Authorizer authorizer = authorizer("no-binding");
    Principal principal = new Principal("nobody", Set.of());

    assertFalse(
        authorizer.authorize(
            principal, ResourceKind.DEPLOYMENT, Verb.READ, Optional.empty(), Optional.empty()));
  }

  @Test
  void an_operator_group_member_is_allowed_everything_via_the_implicit_cluster_admin_binding() {
    Authorizer authorizer = authorizer("operator-implicit");
    Principal operator = new Principal("alice", Set.of(BuiltinRoles.GROUP_OPERATORS));

    for (ResourceKind resource : ResourceKind.values()) {
      for (Verb verb : Verb.values()) {
        assertTrue(
            authorizer.authorize(
                operator, resource, verb, Optional.of("any-tenant"), Optional.empty()),
            "cluster-admin via group:gimle:operators should cover " + resource + ":" + verb);
      }
    }
  }

  @Test
  void a_node_may_act_on_its_own_node_and_log_endpoints_with_no_role_binding_at_all() {
    Authorizer authorizer = authorizer("node-self-service");
    Principal node = new Principal("node-1", Set.of(BuiltinRoles.GROUP_NODES));

    assertTrue(
        authorizer.authorize(
            node, ResourceKind.NODE, Verb.WRITE, Optional.empty(), Optional.of("node-1")));
    assertTrue(
        authorizer.authorize(
            node, ResourceKind.LOGS, Verb.READ, Optional.empty(), Optional.of("node-1")));
  }

  @Test
  void a_node_is_denied_another_nodes_endpoints() {
    Authorizer authorizer = authorizer("node-cross-boundary");
    Principal node = new Principal("node-1", Set.of(BuiltinRoles.GROUP_NODES));

    assertFalse(
        authorizer.authorize(
            node, ResourceKind.NODE, Verb.WRITE, Optional.empty(), Optional.of("node-2")));
  }

  @Test
  void a_node_is_denied_every_non_node_non_logs_resource() {
    Authorizer authorizer = authorizer("node-scope-limit");
    Principal node = new Principal("node-1", Set.of(BuiltinRoles.GROUP_NODES));

    assertFalse(
        authorizer.authorize(
            node, ResourceKind.DEPLOYMENT, Verb.READ, Optional.empty(), Optional.empty()));
    assertFalse(
        authorizer.authorize(
            node, ResourceKind.TENANT, Verb.READ, Optional.empty(), Optional.empty()));
    assertFalse(
        authorizer.authorize(
            node, ResourceKind.ROLE, Verb.READ, Optional.empty(), Optional.empty()));
  }

  /**
   * Every node agent polls {@code GET /networkpolicies} ({@code NetworkPolicyRelay}) and, when
   * Bifrost is enabled, {@code GET /services} -- both cluster-wide, unscoped by tenant or target,
   * with no {@link RoleBinding} needing to exist for it, the same way {@link
   * BuiltinRoles#GROUP_NODES} already gets its own node/log self-service. Discovered live: a
   * freshly-bootstrapped node had no RBAC path to either at all, so its very first policy poll came
   * back 403.
   */
  @Test
  void a_node_may_read_the_cluster_wide_service_and_network_policy_sets_with_no_binding_at_all() {
    Authorizer authorizer = authorizer("node-service-networkpolicy-read");
    Principal node = new Principal("node-1", Set.of(BuiltinRoles.GROUP_NODES));

    assertTrue(
        authorizer.authorize(
            node, ResourceKind.NETWORK_POLICY, Verb.READ, Optional.empty(), Optional.empty()));
    assertTrue(
        authorizer.authorize(
            node, ResourceKind.SERVICE, Verb.READ, Optional.empty(), Optional.empty()));
  }

  /** A node may never declare or remove a Service or NetworkPolicy itself. */
  @Test
  void a_node_may_never_write_or_delete_a_service_or_network_policy() {
    Authorizer authorizer = authorizer("node-service-networkpolicy-write-denied");
    Principal node = new Principal("node-1", Set.of(BuiltinRoles.GROUP_NODES));

    assertFalse(
        authorizer.authorize(
            node, ResourceKind.NETWORK_POLICY, Verb.WRITE, Optional.empty(), Optional.empty()));
    assertFalse(
        authorizer.authorize(
            node, ResourceKind.NETWORK_POLICY, Verb.DELETE, Optional.empty(), Optional.empty()));
    assertFalse(
        authorizer.authorize(
            node, ResourceKind.SERVICE, Verb.WRITE, Optional.empty(), Optional.empty()));
    assertFalse(
        authorizer.authorize(
            node, ResourceKind.SERVICE, Verb.DELETE, Optional.empty(), Optional.empty()));
  }

  @Test
  void a_custom_role_bound_to_a_user_grants_exactly_its_declared_permissions() {
    StateStore store = new StateStore();
    store.putRole(
        new Role(
            "deployment-reader", Set.of(Permission.unscoped(ResourceKind.DEPLOYMENT, Verb.READ))));
    store.putRoleBinding(
        new RoleBinding("b1", RoleBinding.userSubject("bob"), "deployment-reader"));
    Authorizer authorizer = new Authorizer(store);
    Principal bob = new Principal("bob", Set.of());

    assertTrue(
        authorizer.authorize(
            bob, ResourceKind.DEPLOYMENT, Verb.READ, Optional.empty(), Optional.empty()));
    assertFalse(
        authorizer.authorize(
            bob, ResourceKind.DEPLOYMENT, Verb.WRITE, Optional.empty(), Optional.empty()));
    assertFalse(
        authorizer.authorize(
            bob, ResourceKind.TENANT, Verb.READ, Optional.empty(), Optional.empty()));
  }

  @Test
  void a_tenant_scoped_permission_only_matches_its_own_tenant() {
    StateStore store = new StateStore();
    store.putRole(
        new Role(
            "acme-config-reader",
            Set.of(Permission.scoped(ResourceKind.CONFIG, Verb.READ, "acme"))));
    store.putRoleBinding(
        new RoleBinding("b1", RoleBinding.userSubject("carol"), "acme-config-reader"));
    Authorizer authorizer = new Authorizer(store);
    Principal carol = new Principal("carol", Set.of());

    assertTrue(
        authorizer.authorize(
            carol, ResourceKind.CONFIG, Verb.READ, Optional.of("acme"), Optional.empty()));
    assertFalse(
        authorizer.authorize(
            carol, ResourceKind.CONFIG, Verb.READ, Optional.of("other-tenant"), Optional.empty()));
    assertFalse(
        authorizer.authorize(
            carol, ResourceKind.CONFIG, Verb.READ, Optional.empty(), Optional.empty()));
  }

  @Test
  void a_group_binding_applies_to_every_principal_carrying_that_group() {
    StateStore store = new StateStore();
    store.putRole(new Role("viewer", Set.of(Permission.unscoped(ResourceKind.TENANT, Verb.READ))));
    store.putRoleBinding(new RoleBinding("b1", RoleBinding.groupSubject("finance"), "viewer"));
    Authorizer authorizer = new Authorizer(store);
    Principal financeMember = new Principal("dave", Set.of("finance"));
    Principal outsider = new Principal("erin", Set.of());

    assertTrue(
        authorizer.authorize(
            financeMember, ResourceKind.TENANT, Verb.READ, Optional.empty(), Optional.empty()));
    assertFalse(
        authorizer.authorize(
            outsider, ResourceKind.TENANT, Verb.READ, Optional.empty(), Optional.empty()));
  }

  @Test
  void a_binding_to_a_tenant_template_grants_within_that_tenant_and_nowhere_else() {
    StateStore store = new StateStore();
    store.putRoleBinding(
        new RoleBinding("b1", RoleBinding.userSubject("grace"), "tenant-edit:acme"));
    Authorizer authorizer = new Authorizer(store);
    Principal grace = new Principal("grace", Set.of());

    assertTrue(
        authorizer.authorize(
            grace, ResourceKind.DEPLOYMENT, Verb.WRITE, Optional.of("acme"), Optional.empty()));
    assertTrue(
        authorizer.authorize(
            grace, ResourceKind.SECRET, Verb.READ, Optional.of("acme"), Optional.empty()));
    assertFalse(
        authorizer.authorize(
            grace, ResourceKind.DEPLOYMENT, Verb.WRITE, Optional.of("umbrella"), Optional.empty()));
    assertFalse(
        authorizer.authorize(
            grace, ResourceKind.NETWORK_POLICY, Verb.WRITE, Optional.of("acme"), Optional.empty()));
  }

  @Test
  void a_binding_to_a_tenant_view_template_never_reads_secrets() {
    StateStore store = new StateStore();
    store.putRoleBinding(
        new RoleBinding("b1", RoleBinding.userSubject("heidi"), "tenant-view:acme"));
    Authorizer authorizer = new Authorizer(store);
    Principal heidi = new Principal("heidi", Set.of());

    assertTrue(
        authorizer.authorize(
            heidi, ResourceKind.DEPLOYMENT, Verb.READ, Optional.of("acme"), Optional.empty()));
    assertFalse(
        authorizer.authorize(
            heidi, ResourceKind.SECRET, Verb.READ, Optional.of("acme"), Optional.empty()));
  }

  @Test
  void has_any_read_grant_distinguishes_scoped_readers_from_callers_with_nothing() {
    StateStore store = new StateStore();
    store.putRoleBinding(
        new RoleBinding("b1", RoleBinding.userSubject("heidi"), "tenant-view:acme"));
    Authorizer authorizer = new Authorizer(store);

    // A tenant-scoped grant counts -- even though the same principal's unscoped authorize is
    // denied, which is exactly the gap this method exists to bridge for collection listings.
    Principal heidi = new Principal("heidi", Set.of());
    assertTrue(authorizer.hasAnyReadGrant(heidi, ResourceKind.DEPLOYMENT));
    assertFalse(
        authorizer.authorize(
            heidi, ResourceKind.DEPLOYMENT, Verb.READ, Optional.empty(), Optional.empty()));

    // A kind the template doesn't cover, and a principal with no binding at all, both report no.
    assertFalse(authorizer.hasAnyReadGrant(heidi, ResourceKind.SECRET));
    assertFalse(
        authorizer.hasAnyReadGrant(new Principal("nobody", Set.of()), ResourceKind.DEPLOYMENT));

    // Operators and nodes keep their implicit grants here too.
    assertTrue(
        authorizer.hasAnyReadGrant(
            new Principal("op", Set.of(BuiltinRoles.GROUP_OPERATORS)), ResourceKind.DEPLOYMENT));
    assertTrue(
        authorizer.hasAnyReadGrant(
            new Principal("node-1", Set.of(BuiltinRoles.GROUP_NODES)), ResourceKind.SERVICE));
  }

  @Test
  void a_binding_referencing_a_role_that_no_longer_exists_grants_nothing() {
    StateStore store = new StateStore();
    store.putRoleBinding(new RoleBinding("b1", RoleBinding.userSubject("frank"), "deleted-role"));
    Authorizer authorizer = new Authorizer(store);
    Principal frank = new Principal("frank", Set.of());

    assertFalse(
        authorizer.authorize(
            frank, ResourceKind.DEPLOYMENT, Verb.READ, Optional.empty(), Optional.empty()));
  }

  @Test
  void a_node_with_an_active_assignment_for_the_tenant_is_assigned() {
    StateStore store = new StateStore();
    assignDeploymentToNode(store, "node-1", "acme");
    Authorizer authorizer = new Authorizer(store);

    assertTrue(authorizer.isTenantAssignedToNode("node-1", "acme"));
  }

  @Test
  void a_node_with_no_assignment_for_the_tenant_is_not_assigned() {
    StateStore store = new StateStore();
    // "node-1" is assigned to a deployment for a different tenant -- proves this is a genuine
    // per-tenant check, not merely "does this node have any assignment at all."
    assignDeploymentToNode(store, "node-1", "other-tenant");
    Authorizer authorizer = new Authorizer(store);

    assertFalse(authorizer.isTenantAssignedToNode("node-1", "acme"));
  }

  @Test
  void a_node_with_no_assignments_at_all_is_not_assigned() {
    StateStore store = new StateStore();
    Authorizer authorizer = new Authorizer(store);

    assertFalse(authorizer.isTenantAssignedToNode("node-1", "acme"));
  }

  /**
   * A single-replica deployment placed on {@code nodeId} for {@code tenantId} -- the minimal
   * scheduler-decision shape {@link Authorizer#isTenantAssignedToNode} joins against, mirroring
   * exactly what a real {@code DeploymentReconciler} placement would have written.
   */
  private static void assignDeploymentToNode(StateStore store, String nodeId, String tenantId) {
    ModuleId moduleId = new ModuleId("com.gimle.example.orders", Version.parse("1.0.0"));
    store.putDeployment(
        new DeploymentSpec(
            "dep-" + tenantId,
            moduleId,
            "/var/gimle/artifacts/orders-1.0.0.jar",
            1,
            PlacementConstraints.NONE,
            Optional.empty(),
            Optional.of(tenantId)));
    store.putAssignment(new InstanceAssignment("dep-" + tenantId, 0, nodeId));
  }
}
