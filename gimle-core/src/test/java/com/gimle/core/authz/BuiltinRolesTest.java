package com.gimle.core.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
