package com.gimle.core.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class BuiltinRolesTest {

  @Test
  void cluster_admin_covers_every_resource_and_verb_unscoped() {
    for (ResourceKind resource : ResourceKind.values()) {
      for (Verb verb : Verb.values()) {
        boolean covered =
            BuiltinRoles.CLUSTER_ADMIN.permissions().stream()
                .anyMatch(p -> p.covers(resource, verb, Optional.of("any-tenant")));
        assertTrue(covered, "cluster-admin should cover " + resource + ":" + verb);
      }
    }
  }

  @Test
  void group_names_match_what_the_pki_layer_stamps() {
    assertEquals("gimle:operators", BuiltinRoles.GROUP_OPERATORS);
    assertEquals("gimle:nodes", BuiltinRoles.GROUP_NODES);
  }

  @Test
  void tenant_view_reads_workloads_and_config_but_never_secrets() {
    Role view = BuiltinRoles.tenantRole("tenant-view:acme").orElseThrow();

    assertTrue(covers(view, ResourceKind.DEPLOYMENT, Verb.READ, "acme"));
    assertTrue(covers(view, ResourceKind.CONFIG, Verb.READ, "acme"));
    assertTrue(covers(view, ResourceKind.LOGS, Verb.READ, "acme"));
    assertFalse(covers(view, ResourceKind.SECRET, Verb.READ, "acme"));
    assertFalse(covers(view, ResourceKind.SECRETMAP, Verb.READ, "acme"));
    assertFalse(covers(view, ResourceKind.DEPLOYMENT, Verb.WRITE, "acme"));
  }

  @Test
  void tenant_edit_adds_workload_and_secret_writes_but_not_guardrails() {
    Role edit = BuiltinRoles.tenantRole("tenant-edit:acme").orElseThrow();

    assertTrue(covers(edit, ResourceKind.DEPLOYMENT, Verb.WRITE, "acme"));
    assertTrue(covers(edit, ResourceKind.SECRET, Verb.READ, "acme"));
    assertTrue(covers(edit, ResourceKind.SECRET, Verb.WRITE, "acme"));
    assertTrue(covers(edit, ResourceKind.CONFIGMAP, Verb.DELETE, "acme"));
    assertFalse(covers(edit, ResourceKind.NETWORK_POLICY, Verb.WRITE, "acme"));
    assertFalse(covers(edit, ResourceKind.LIMIT_RANGE, Verb.WRITE, "acme"));
  }

  @Test
  void tenant_admin_additionally_manages_the_tenant_guardrails() {
    Role admin = BuiltinRoles.tenantRole("tenant-admin:acme").orElseThrow();

    assertTrue(covers(admin, ResourceKind.NETWORK_POLICY, Verb.WRITE, "acme"));
    assertTrue(covers(admin, ResourceKind.LIMIT_RANGE, Verb.DELETE, "acme"));
    assertTrue(covers(admin, ResourceKind.DEPLOYMENT, Verb.WRITE, "acme"));
  }

  @Test
  void every_tenant_template_permission_is_scoped_to_its_own_tenant_only() {
    for (String name : new String[] {"tenant-view:acme", "tenant-edit:acme", "tenant-admin:acme"}) {
      Role role = BuiltinRoles.tenantRole(name).orElseThrow();
      for (Permission permission : role.permissions()) {
        assertEquals(
            Optional.of("acme"),
            permission.tenantScope(),
            name + " must never carry an unscoped or foreign-tenant permission");
      }
      assertFalse(covers(role, ResourceKind.DEPLOYMENT, Verb.READ, "other-tenant"));
    }
  }

  @Test
  void names_that_are_not_tenant_templates_synthesize_nothing() {
    assertTrue(BuiltinRoles.tenantRole("cluster-admin").isEmpty());
    assertTrue(BuiltinRoles.tenantRole("tenant-view:").isEmpty());
    assertTrue(BuiltinRoles.tenantRole("some-stored-role").isEmpty());
  }

  private static boolean covers(Role role, ResourceKind resource, Verb verb, String tenantId) {
    return role.permissions().stream()
        .anyMatch(p -> p.covers(resource, verb, Optional.of(tenantId)));
  }
}
