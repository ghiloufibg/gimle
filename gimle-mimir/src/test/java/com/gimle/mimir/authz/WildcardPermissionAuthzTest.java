package com.gimle.mimir.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.authz.Permission;
import com.gimle.core.authz.Principal;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Role;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.authz.Verb;
import com.gimle.mimir.codec.DomainCodec;
import com.gimle.mimir.store.StateStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A wildcard grant is stored as a wildcard and widened only here, at authorize time -- which is
 * what lets an operator compose a role broader than the per-tenant templates without
 * hand-enumerating (and then re-editing) every resource-by-verb combination the platform happens to
 * have today.
 */
class WildcardPermissionAuthzTest {

  private static final Permission EVERY_RESOURCE_READ =
      new Permission(Optional.empty(), Optional.of(Verb.READ), Optional.empty(), Optional.empty());

  private static Authorizer authorizerGranting(Permission... permissions) {
    StateStore store = new StateStore();
    store.putRole(new Role("composed", Set.of(permissions)));
    store.putRoleBinding(new RoleBinding("b1", RoleBinding.userSubject("bob"), "composed"));
    return new Authorizer(store);
  }

  private static Principal bob() {
    return new Principal("bob", Set.of());
  }

  @Test
  void a_wildcard_resource_grant_covers_every_resource_kind_the_enum_currently_holds() {
    Authorizer authorizer = authorizerGranting(EVERY_RESOURCE_READ);

    for (ResourceKind resource : ResourceKind.values()) {
      assertTrue(
          authorizer.authorize(bob(), resource, Verb.READ, Optional.of("acme"), Optional.empty()),
          resource.name());
    }
  }

  @Test
  void a_wildcard_resource_grant_stays_one_stored_permission_rather_than_an_expanded_set() {
    // The whole point of keeping the sentinel a sentinel: nothing is enumerated at write time, so a
    // ResourceKind added to the enum later is covered by this same untouched stored role. The
    // stored shape is what proves it -- an expanded set would have frozen today's enum into it.
    StateStore store = new StateStore();
    store.putRole(new Role("composed", Set.of(EVERY_RESOURCE_READ)));

    Role stored = store.getRole("composed").orElseThrow();
    assertEquals(1, stored.permissions().size());
    Permission permission = stored.permissions().iterator().next();
    assertEquals(Optional.empty(), permission.resource());
    assertEquals(Permission.ALL, permission.resourceToken());
  }

  @Test
  void a_wildcard_permission_survives_the_store_codec_as_a_wildcard() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      DomainCodec.writePermission(
          out,
          new Permission(
              Optional.empty(), Optional.empty(), Optional.of("acme"), Optional.empty()));
    }

    Permission decoded;
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      decoded = DomainCodec.readPermission(in);
    }

    assertEquals(Optional.empty(), decoded.resource());
    assertEquals(Optional.empty(), decoded.verb());
    assertEquals(Optional.of("acme"), decoded.tenantScope());
    assertTrue(decoded.covers(ResourceKind.LOGS, Verb.DELETE, Optional.of("acme")));
    assertFalse(decoded.covers(ResourceKind.LOGS, Verb.DELETE, Optional.of("other")));
  }

  @Test
  void a_wildcard_verb_grant_covers_every_verb_on_only_the_kind_it_names() {
    Authorizer authorizer =
        authorizerGranting(
            new Permission(
                Optional.of(ResourceKind.DEPLOYMENT),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));

    for (Verb verb : Verb.values()) {
      assertTrue(
          authorizer.authorize(
              bob(), ResourceKind.DEPLOYMENT, verb, Optional.empty(), Optional.empty()),
          verb.name());
      assertFalse(
          authorizer.authorize(
              bob(), ResourceKind.SECRET, verb, Optional.empty(), Optional.empty()),
          verb.name());
    }
  }

  @Test
  void a_wildcard_tenant_scope_is_the_same_grant_an_omitted_scope_already_was() {
    Authorizer authorizer =
        authorizerGranting(
            new Permission(
                Optional.of(ResourceKind.CONFIG),
                Optional.of(Verb.READ),
                Permission.parseTenantScope(Permission.ALL),
                Optional.empty()));

    assertTrue(
        authorizer.authorize(
            bob(), ResourceKind.CONFIG, Verb.READ, Optional.of("acme"), Optional.empty()));
    assertTrue(
        authorizer.authorize(
            bob(), ResourceKind.CONFIG, Verb.READ, Optional.of("other"), Optional.empty()));
    assertTrue(
        authorizer.authorize(
            bob(), ResourceKind.CONFIG, Verb.READ, Optional.empty(), Optional.empty()));
  }

  @Test
  void a_tenant_scoped_wildcard_grant_never_leaks_into_another_tenant() {
    Authorizer authorizer =
        authorizerGranting(
            new Permission(
                Optional.empty(), Optional.empty(), Optional.of("acme"), Optional.empty()));

    assertTrue(
        authorizer.authorize(
            bob(), ResourceKind.DEPLOYMENT, Verb.DELETE, Optional.of("acme"), Optional.empty()));
    assertFalse(
        authorizer.authorize(
            bob(), ResourceKind.DEPLOYMENT, Verb.DELETE, Optional.of("other"), Optional.empty()));
    // A cluster-wide (untenanted) request is not "some tenant" -- a scoped grant never covers it.
    assertFalse(
        authorizer.authorize(
            bob(), ResourceKind.DEPLOYMENT, Verb.DELETE, Optional.empty(), Optional.empty()));
  }

  @Test
  void a_narrower_grant_alongside_a_wildcard_read_grant_adds_to_it_without_widening_it() {
    // The operator's real case: read everything, but write only within one tenant. RBAC here is
    // additive-only, so the two grants union -- the narrow one must not silently widen the broad
    // one, and the broad READ must not confer the WRITE the narrow one deliberately confines.
    Authorizer authorizer =
        authorizerGranting(
            EVERY_RESOURCE_READ, Permission.scoped(ResourceKind.DEPLOYMENT, Verb.WRITE, "acme"));

    assertTrue(
        authorizer.authorize(
            bob(), ResourceKind.SECRET, Verb.READ, Optional.of("other"), Optional.empty()));
    assertTrue(
        authorizer.authorize(
            bob(), ResourceKind.DEPLOYMENT, Verb.WRITE, Optional.of("acme"), Optional.empty()));
    assertFalse(
        authorizer.authorize(
            bob(), ResourceKind.DEPLOYMENT, Verb.WRITE, Optional.of("other"), Optional.empty()));
    assertFalse(
        authorizer.authorize(
            bob(), ResourceKind.SECRET, Verb.DELETE, Optional.of("acme"), Optional.empty()));
  }

  @Test
  void a_wildcard_resource_grant_admits_the_collection_list_gate_for_every_kind() {
    Authorizer authorizer = authorizerGranting(EVERY_RESOURCE_READ);

    for (ResourceKind resource : ResourceKind.values()) {
      assertTrue(authorizer.hasAnyReadGrant(bob(), resource), resource.name());
    }
  }

  @Test
  void a_wildcard_write_only_grant_never_admits_the_read_collection_gate() {
    Authorizer authorizer =
        authorizerGranting(
            new Permission(
                Optional.empty(), Optional.of(Verb.WRITE), Optional.empty(), Optional.empty()));

    assertFalse(authorizer.hasAnyReadGrant(bob(), ResourceKind.DEPLOYMENT));
    assertFalse(
        authorizer.authorize(
            bob(), ResourceKind.DEPLOYMENT, Verb.READ, Optional.empty(), Optional.empty()));
  }

  @Test
  void a_wildcard_grant_never_reaches_a_custom_kinds_status_writes() {
    Authorizer authorizer =
        authorizerGranting(
            new Permission(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));

    assertTrue(
        authorizer.authorize(
            bob(),
            ResourceKind.CUSTOM_RESOURCE,
            Verb.WRITE,
            Optional.of("acme"),
            Optional.empty(),
            Optional.of("custom.Greeting")));
    assertFalse(
        authorizer.authorize(
            bob(),
            ResourceKind.CUSTOM_RESOURCE,
            Verb.WRITE,
            Optional.of("acme"),
            Optional.empty(),
            Optional.of("custom.Greeting/status")));
  }

  @Test
  void a_principal_with_no_binding_is_unaffected_by_someone_elses_wildcard_role() {
    Authorizer authorizer = authorizerGranting(EVERY_RESOURCE_READ);
    Principal stranger = new Principal("mallory", Set.of());

    assertFalse(
        authorizer.authorize(
            stranger, ResourceKind.DEPLOYMENT, Verb.READ, Optional.empty(), Optional.empty()));
    assertFalse(authorizer.hasAnyReadGrant(stranger, ResourceKind.DEPLOYMENT));
  }
}
