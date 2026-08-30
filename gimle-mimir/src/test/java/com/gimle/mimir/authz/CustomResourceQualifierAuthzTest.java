package com.gimle.mimir.authz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.authz.Permission;
import com.gimle.core.authz.Principal;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Role;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.authz.Verb;
import com.gimle.mimir.store.StateStore;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The {@link Permission} qualifier semantics for {@link ResourceKind#CUSTOM_RESOURCE}: absent
 * covers every custom kind's specs, a kind name covers only that kind's specs, a {@code
 * {kind}/status} qualifier covers only that kind's status writes -- and the spec/status split never
 * leaks in either direction.
 */
class CustomResourceQualifierAuthzTest {

  private static final String KIND = "custom.Greeting";
  private static final String OTHER_KIND = "acme.FeatureFlag";
  private static final Optional<String> TENANT = Optional.of("team-a");

  private static final Optional<String> SPEC_Q = Optional.of(KIND);
  private static final Optional<String> STATUS_Q = Optional.of(KIND + "/status");

  private final StateStore store = new StateStore();

  private Principal bind(String subjectName, Permission... permissions) {
    Role role = new Role("role-" + subjectName, Set.of(permissions));
    store.putRole(role);
    store.putRoleBinding(
        new RoleBinding(
            "binding-" + subjectName, RoleBinding.userSubject(subjectName), role.name()));
    return new Principal(subjectName, Set.of());
  }

  private boolean allowed(Principal principal, Verb verb, Optional<String> qualifier) {
    return new Authorizer(store)
        .authorize(
            principal, ResourceKind.CUSTOM_RESOURCE, verb, TENANT, Optional.empty(), qualifier);
  }

  @Test
  void an_unqualified_grant_covers_every_kinds_specs_but_never_a_status_write() {
    Principal editor =
        bind("editor", Permission.scoped(ResourceKind.CUSTOM_RESOURCE, Verb.WRITE, "team-a"));

    assertTrue(allowed(editor, Verb.WRITE, SPEC_Q));
    assertTrue(allowed(editor, Verb.WRITE, Optional.of(OTHER_KIND)));
    assertFalse(
        allowed(editor, Verb.WRITE, STATUS_Q),
        "a human editor's unqualified grant must never stomp an operator-reported status");
  }

  @Test
  void a_kind_qualified_grant_covers_only_that_kinds_specs() {
    Principal scoped =
        bind(
            "scoped",
            new Permission(ResourceKind.CUSTOM_RESOURCE, Verb.WRITE, TENANT, SPEC_Q),
            new Permission(ResourceKind.CUSTOM_RESOURCE, Verb.READ, TENANT, SPEC_Q));

    assertTrue(allowed(scoped, Verb.WRITE, SPEC_Q));
    assertTrue(allowed(scoped, Verb.READ, SPEC_Q));
    assertFalse(allowed(scoped, Verb.WRITE, Optional.of(OTHER_KIND)));
    assertFalse(allowed(scoped, Verb.READ, Optional.of(OTHER_KIND)));
    assertFalse(allowed(scoped, Verb.WRITE, STATUS_Q), "spec-WRITE never implies status-WRITE");
  }

  @Test
  void a_status_qualified_grant_covers_only_that_kinds_status_writes() {
    Principal reporter =
        bind(
            "reporter", new Permission(ResourceKind.CUSTOM_RESOURCE, Verb.WRITE, TENANT, STATUS_Q));

    assertTrue(allowed(reporter, Verb.WRITE, STATUS_Q));
    assertFalse(allowed(reporter, Verb.WRITE, SPEC_Q), "status-WRITE never implies spec-WRITE");
    assertFalse(allowed(reporter, Verb.WRITE, Optional.of(OTHER_KIND + "/status")));
  }

  @Test
  void the_typical_operator_role_is_exactly_read_plus_status_write_for_its_own_kind() {
    // The walkthrough's svc: workload principal, authorized purely by its bindings -- no group
    // shortcut, no node self-service, just an ordinary user-subject RoleBinding.
    Principal operator =
        bind(
            "svc:team-a:greeting-operator",
            new Permission(ResourceKind.CUSTOM_RESOURCE, Verb.READ, TENANT, SPEC_Q),
            new Permission(ResourceKind.CUSTOM_RESOURCE, Verb.WRITE, TENANT, STATUS_Q));

    assertTrue(allowed(operator, Verb.READ, SPEC_Q));
    assertTrue(allowed(operator, Verb.WRITE, STATUS_Q));
    assertFalse(
        allowed(operator, Verb.WRITE, SPEC_Q),
        "an operator granted only READ + {kind}/status cannot alter desired state");
    assertFalse(allowed(operator, Verb.DELETE, SPEC_Q));
    assertFalse(allowed(operator, Verb.READ, Optional.of(OTHER_KIND)));
  }

  @Test
  void a_tenant_scoped_qualified_grant_never_reaches_another_tenant() {
    Principal scoped =
        bind("cross", new Permission(ResourceKind.CUSTOM_RESOURCE, Verb.READ, TENANT, SPEC_Q));

    assertFalse(
        new Authorizer(store)
            .authorize(
                scoped,
                ResourceKind.CUSTOM_RESOURCE,
                Verb.READ,
                Optional.of("team-b"),
                Optional.empty(),
                SPEC_Q));
  }

  @Test
  void the_tenant_edit_template_covers_custom_resource_specs_with_zero_migration() {
    store.putRoleBinding(
        new RoleBinding("tpl", RoleBinding.userSubject("dev"), "tenant-edit:team-a"));
    Principal dev = new Principal("dev", Set.of());

    assertTrue(allowed(dev, Verb.WRITE, SPEC_Q));
    assertTrue(allowed(dev, Verb.READ, Optional.of(OTHER_KIND)));
    assertFalse(
        allowed(dev, Verb.WRITE, STATUS_Q),
        "the template's unqualified grant deliberately excludes status writes");
  }

  @Test
  void a_kind_definition_write_needs_its_own_grant_not_a_custom_resource_one() {
    Principal editor =
        bind("no-vocab", Permission.scoped(ResourceKind.CUSTOM_RESOURCE, Verb.WRITE, "team-a"));

    assertFalse(
        new Authorizer(store)
            .authorize(
                editor,
                ResourceKind.KIND_DEFINITION,
                Verb.WRITE,
                Optional.empty(),
                Optional.empty()),
        "teaching the cluster a new kind is its own independently-withholdable grant");
  }
}
