package com.gimle.core.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

  @Test
  void a_wildcard_resource_covers_every_kind_for_the_named_verb_only() {
    Permission p =
        new Permission(
            Optional.empty(), Optional.of(Verb.READ), Optional.empty(), Optional.empty());

    for (ResourceKind resource : ResourceKind.values()) {
      assertTrue(p.covers(resource, Verb.READ, Optional.of("acme")), resource.name());
      assertFalse(p.covers(resource, Verb.WRITE, Optional.of("acme")), resource.name());
    }
  }

  @Test
  void a_wildcard_verb_covers_every_verb_for_the_named_resource_only() {
    Permission p =
        new Permission(
            Optional.of(ResourceKind.DEPLOYMENT),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    for (Verb verb : Verb.values()) {
      assertTrue(p.covers(ResourceKind.DEPLOYMENT, verb, Optional.empty()), verb.name());
      assertFalse(p.covers(ResourceKind.SECRET, verb, Optional.empty()), verb.name());
    }
  }

  @Test
  void a_wildcard_in_every_position_covers_every_kind_verb_and_tenant() {
    Permission p =
        new Permission(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    for (ResourceKind resource : ResourceKind.values()) {
      for (Verb verb : Verb.values()) {
        assertTrue(p.covers(resource, verb, Optional.empty()));
        assertTrue(p.covers(resource, verb, Optional.of("acme")));
      }
    }
  }

  @Test
  void a_wildcard_resource_and_verb_still_honours_a_named_tenant_scope() {
    Permission p =
        new Permission(Optional.empty(), Optional.empty(), Optional.of("acme"), Optional.empty());

    assertTrue(p.covers(ResourceKind.DEPLOYMENT, Verb.DELETE, Optional.of("acme")));
    assertFalse(p.covers(ResourceKind.DEPLOYMENT, Verb.DELETE, Optional.of("other")));
    assertFalse(p.covers(ResourceKind.DEPLOYMENT, Verb.DELETE, Optional.empty()));
  }

  @Test
  void a_wildcard_resource_grant_never_reaches_a_custom_kinds_status_writes() {
    // Breadth over resource kinds is not a licence to overwrite what an operator reported: a status
    // write still takes the explicit {kind}/status qualifier no wildcard can stand in for.
    Permission p =
        new Permission(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    assertTrue(
        p.covers(
            ResourceKind.CUSTOM_RESOURCE,
            Verb.WRITE,
            Optional.of("acme"),
            Optional.of("custom.Greeting")));
    assertFalse(
        p.covers(
            ResourceKind.CUSTOM_RESOURCE,
            Verb.WRITE,
            Optional.of("acme"),
            Optional.of("custom.Greeting/status")));
  }

  @Test
  void covers_resource_and_covers_verb_widen_each_position_independently() {
    Permission wildcardResource =
        new Permission(
            Optional.empty(), Optional.of(Verb.READ), Optional.empty(), Optional.empty());
    assertTrue(wildcardResource.coversResource(ResourceKind.LOGS));
    assertTrue(wildcardResource.coversVerb(Verb.READ));
    assertFalse(wildcardResource.coversVerb(Verb.DELETE));

    Permission named = Permission.unscoped(ResourceKind.LOGS, Verb.READ);
    assertTrue(named.coversResource(ResourceKind.LOGS));
    assertFalse(named.coversResource(ResourceKind.SECRET));
  }

  @Test
  void wildcard_positions_round_trip_through_their_written_tokens() {
    Permission p =
        new Permission(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    assertEquals(Permission.ALL, p.resourceToken());
    assertEquals(Permission.ALL, p.verbToken());
    assertEquals(Optional.empty(), Permission.parseResource(p.resourceToken()));
    assertEquals(Optional.empty(), Permission.parseVerb(p.verbToken()));

    Permission named = Permission.scoped(ResourceKind.CONFIG, Verb.WRITE, "acme");
    assertEquals("CONFIG", named.resourceToken());
    assertEquals("WRITE", named.verbToken());
    assertEquals(Optional.of(ResourceKind.CONFIG), Permission.parseResource(named.resourceToken()));
    assertEquals(Optional.of(Verb.WRITE), Permission.parseVerb(named.verbToken()));
  }

  @Test
  void parsing_accepts_either_case_and_treats_an_absent_or_wildcard_tenant_as_every_tenant() {
    assertEquals(Optional.of(ResourceKind.DEPLOYMENT), Permission.parseResource("deployment"));
    assertEquals(Optional.of(Verb.READ), Permission.parseVerb("read"));
    assertEquals(Optional.empty(), Permission.parseTenantScope(null));
    assertEquals(Optional.empty(), Permission.parseTenantScope(""));
    assertEquals(Optional.empty(), Permission.parseTenantScope(Permission.ALL));
    assertEquals(Optional.of("acme"), Permission.parseTenantScope("acme"));
  }

  @Test
  void parsing_rejects_an_unknown_or_blank_resource_or_verb() {
    assertThrows(IllegalArgumentException.class, () -> Permission.parseResource("nonesuch"));
    assertThrows(IllegalArgumentException.class, () -> Permission.parseResource(""));
    assertThrows(IllegalArgumentException.class, () -> Permission.parseResource(null));
    assertThrows(IllegalArgumentException.class, () -> Permission.parseVerb("peek"));
    assertThrows(IllegalArgumentException.class, () -> Permission.parseVerb(" "));
  }

  @Test
  void parsing_rejects_a_wildcard_qualifier_rather_than_storing_a_grant_that_matches_nothing() {
    assertEquals(Optional.empty(), Permission.parseQualifier(null));
    assertEquals(Optional.of("custom.Greeting"), Permission.parseQualifier("custom.Greeting"));
    assertThrows(IllegalArgumentException.class, () -> Permission.parseQualifier(Permission.ALL));
  }
}
