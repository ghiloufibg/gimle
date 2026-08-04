package com.gimle.core.authz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PermissionTest {

  @Test
  void unscoped_permission_covers_any_requested_tenant() {
    Permission p = Permission.unscoped(ResourceKind.TENANT, Verb.READ);
    assertTrue(p.covers(ResourceKind.TENANT, Verb.READ, Optional.empty()));
    assertTrue(p.covers(ResourceKind.TENANT, Verb.READ, Optional.of("acme")));
  }

  @Test
  void scoped_permission_only_covers_its_own_tenant() {
    Permission p = Permission.scoped(ResourceKind.CONFIG, Verb.WRITE, "acme");
    assertTrue(p.covers(ResourceKind.CONFIG, Verb.WRITE, Optional.of("acme")));
    assertFalse(p.covers(ResourceKind.CONFIG, Verb.WRITE, Optional.of("other")));
    assertFalse(p.covers(ResourceKind.CONFIG, Verb.WRITE, Optional.empty()));
  }

  @Test
  void mismatched_resource_or_verb_never_covers() {
    Permission p = Permission.unscoped(ResourceKind.NODE, Verb.READ);
    assertFalse(p.covers(ResourceKind.NODE, Verb.WRITE, Optional.empty()));
    assertFalse(p.covers(ResourceKind.DEPLOYMENT, Verb.READ, Optional.empty()));
  }
}
