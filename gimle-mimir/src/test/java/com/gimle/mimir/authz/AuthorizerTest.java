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
    return new Authorizer(new StateStore(tempDir.resolve(name)));
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

  @Test
  void a_custom_role_bound_to_a_user_grants_exactly_its_declared_permissions() {
    StateStore store = new StateStore(tempDir.resolve("custom-role"));
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
    StateStore store = new StateStore(tempDir.resolve("tenant-scoped"));
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
    StateStore store = new StateStore(tempDir.resolve("group-binding"));
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
  void a_binding_referencing_a_role_that_no_longer_exists_grants_nothing() {
    StateStore store = new StateStore(tempDir.resolve("dangling-binding"));
    store.putRoleBinding(new RoleBinding("b1", RoleBinding.userSubject("frank"), "deleted-role"));
    Authorizer authorizer = new Authorizer(store);
    Principal frank = new Principal("frank", Set.of());

    assertFalse(
        authorizer.authorize(
            frank, ResourceKind.DEPLOYMENT, Verb.READ, Optional.empty(), Optional.empty()));
  }

  @Test
  void a_node_with_an_active_assignment_for_the_tenant_is_assigned() {
    StateStore store = new StateStore(tempDir.resolve("tenant-assigned"));
    assignDeploymentToNode(store, "node-1", "acme");
    Authorizer authorizer = new Authorizer(store);

    assertTrue(authorizer.isTenantAssignedToNode("node-1", "acme"));
  }

  @Test
  void a_node_with_no_assignment_for_the_tenant_is_not_assigned() {
    StateStore store = new StateStore(tempDir.resolve("tenant-unassigned"));
    // "node-1" is assigned to a deployment for a different tenant -- proves this is a genuine
    // per-tenant check, not merely "does this node have any assignment at all."
    assignDeploymentToNode(store, "node-1", "other-tenant");
    Authorizer authorizer = new Authorizer(store);

    assertFalse(authorizer.isTenantAssignedToNode("node-1", "acme"));
  }

  @Test
  void a_node_with_no_assignments_at_all_is_not_assigned() {
    StateStore store = new StateStore(tempDir.resolve("tenant-no-assignments"));
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
