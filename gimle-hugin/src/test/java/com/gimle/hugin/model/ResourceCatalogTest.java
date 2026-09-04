package com.gimle.hugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.cli.CliException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** What {@code :} can open, and how a cluster's own registered kinds join the list. */
class ResourceCatalogTest {

  @Test
  void every_built_in_kind_resolves_by_the_key_an_operator_types() {
    ResourceCatalog catalog = ResourceCatalog.builtInOnly();

    for (ResourceKind kind : ResourceKind.builtIns()) {
      assertEquals(Optional.of(kind), catalog.resolve(kind.key()), kind.key());
    }
    assertTrue(catalog.resolve("TENANTS").isPresent());
    assertTrue(catalog.resolve("  tenants  ").isPresent());
    assertTrue(catalog.resolve("pods").isEmpty());
  }

  @Test
  void the_workload_kinds_browse_too_because_a_table_of_instances_is_not_the_workload() {
    // A Deployment's declared replica count and module coordinate are not readable from a table
    // of the instances it happens to be running, which is what `:deployments` is asked for.
    ResourceCatalog catalog = ResourceCatalog.builtInOnly();

    for (String key :
        List.of("deployments", "daemonsets", "statefulsets", "jobs", "services", "alertrules")) {
      assertTrue(catalog.resolve(key).isPresent(), key);
    }
  }

  @Test
  void the_cluster_views_own_workload_kinds_each_reach_a_browsable_kind_by_their_route() {
    // `d` finds the kind to describe by the route a WorkloadKind already names, so the two cannot
    // drift apart over what /deployments is called.
    ResourceCatalog catalog = ResourceCatalog.builtInOnly();

    for (WorkloadKind kind : WorkloadKind.values()) {
      assertTrue(catalog.forRoute(kind.route()).isPresent(), kind.name());
    }
  }

  @Test
  void a_route_nothing_browses_resolves_to_nothing_rather_than_to_the_wrong_kind() {
    assertTrue(ResourceCatalog.builtInOnly().forRoute("/cronjobs/firings").isEmpty());
  }

  @Test
  void a_registered_kind_joins_the_catalog_with_the_columns_its_definition_declares() {
    ResourceCatalog catalog = ResourceCatalog.discover(readerWithGreeting());

    ResourceKind greetings = catalog.resolve("greetings").orElseThrow();

    assertTrue(greetings.custom());
    assertEquals("/resources/Greeting", greetings.route());
    assertEquals(
        List.of("NAME", "TENANT", "MESSAGE", "TIMES SAID"),
        greetings.columns().stream().map(ResourceColumn::header).toList());
  }

  @Test
  void a_registered_kind_answers_to_its_kind_name_and_to_its_own_short_names() {
    // The short names are the cluster's own choice, the same way kubectl's aliases are.
    ResourceCatalog catalog = ResourceCatalog.discover(readerWithGreeting());

    assertTrue(catalog.resolve("Greeting").isPresent());
    assertTrue(catalog.resolve("gr").isPresent());
    assertTrue(catalog.resolve("greet").isPresent());
  }

  @Test
  void a_registered_kind_cannot_take_over_a_built_in_key_an_operator_has_already_learned() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList(
                "/kinddefinitions",
                List.of(
                    Map.of(
                        "kindName", "Tenant",
                        "names", Map.of("plural", "tenants"),
                        "printColumns", List.of())));

    ResourceKind resolved = ResourceCatalog.discover(reader).resolve("tenants").orElseThrow();

    assertFalse(resolved.custom());
    assertEquals("/tenants", resolved.route());
  }

  @Test
  void a_cluster_whose_kind_definitions_cannot_be_read_still_browses_every_built_in() {
    // Failing the whole prompt over one optional read would take away a dozen working screens.
    FakeClusterReader reader = new FakeClusterReader();
    reader.failWith(CliException.forbidden("forbidden"));

    ResourceCatalog catalog = ResourceCatalog.discover(reader);

    assertEquals(ResourceKind.builtIns().size(), catalog.kinds().size());
    assertTrue(catalog.resolve("tenants").isPresent());
  }

  @Test
  void a_definition_with_no_kind_name_is_dropped_rather_than_taking_the_catalog_with_it() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList("/kinddefinitions", List.of(Map.of("names", Map.of("plural", "ghosts"))));

    assertEquals(ResourceKind.builtIns().size(), ResourceCatalog.discover(reader).kinds().size());
  }

  @Test
  void a_definition_declaring_no_print_columns_still_shows_the_name_and_tenant_it_must_have() {
    FakeClusterReader reader =
        new FakeClusterReader().withList("/kinddefinitions", List.of(Map.of("kindName", "Bare")));

    ResourceKind bare = ResourceCatalog.discover(reader).resolve("bare").orElseThrow();

    assertEquals(
        List.of("NAME", "TENANT"), bare.columns().stream().map(ResourceColumn::header).toList());
  }

  @Test
  void a_misspelled_kind_is_answered_with_the_keys_that_share_what_was_typed() {
    // A correction is the near miss, not a list of everything the catalog holds.
    List<String> suggestions = ResourceCatalog.builtInOnly().suggestionsFor("role");

    assertEquals(List.of("rolebindings", "roles"), suggestions);
  }

  @Test
  void a_typed_prefix_matching_nothing_falls_back_to_offering_the_whole_catalog() {
    assertEquals(
        ResourceKind.builtIns().size(), ResourceCatalog.builtInOnly().suggestionsFor("zzz").size());
  }

  private static FakeClusterReader readerWithGreeting() {
    return new FakeClusterReader()
        .withList(
            "/kinddefinitions",
            List.of(
                Map.of(
                    "kindName",
                    "Greeting",
                    "scope",
                    "Tenant",
                    "description",
                    "a greeting",
                    "names",
                    Map.of("plural", "greetings", "shortNames", List.of("gr", "greet")),
                    "printColumns",
                    List.of(
                        Map.of("name", "message", "path", "spec.message"),
                        Map.of("name", "times said", "path", "status.timesSaid")))));
  }
}
