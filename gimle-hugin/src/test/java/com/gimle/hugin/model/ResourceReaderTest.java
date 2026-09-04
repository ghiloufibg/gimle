package com.gimle.hugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.cli.CliException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Turning one kind's collection route into rows, including the shapes that arrive broken. */
class ResourceReaderTest {

  @Test
  void each_of_the_kinds_own_declared_columns_becomes_a_cell_in_its_own_order() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList(
                "/tenants",
                List.of(
                    Map.of(
                        "id",
                        "acme",
                        "isolationPosture",
                        "STRICT",
                        "usage",
                        Map.of("instances", 3),
                        "quota",
                        Map.of("maxInstances", 10),
                        "quotaViolating",
                        false)));

    ResourceSnapshot snapshot =
        new ResourceReader(reader, kind("tenants"), Optional.empty()).read();
    ResourceRow row = snapshot.rows().getFirst();

    assertEquals(List.of("acme", "STRICT", "3", "10", "no"), row.cells());
    assertEquals("acme", row.name());
    assertEquals(Optional.of("acme"), row.tenantId());
    assertTrue(snapshot.permitted());
  }

  @Test
  void a_row_keeps_the_object_it_was_built_from_so_describe_never_needs_a_second_read() {
    // Two reads could disagree, leaving an operator to work out which of them is current.
    Map<String, Object> tenant = Map.of("id", "acme", "isolationPosture", "STRICT");
    FakeClusterReader reader = new FakeClusterReader().withList("/tenants", List.of(tenant));

    assertEquals(
        tenant,
        new ResourceReader(reader, kind("tenants"), Optional.empty())
            .read()
            .rows()
            .getFirst()
            .raw());
  }

  @Test
  void a_field_the_response_does_not_carry_costs_its_own_cell_and_nothing_else() {
    FakeClusterReader reader =
        new FakeClusterReader().withList("/tenants", List.of(Map.of("id", "acme")));

    ResourceRow row =
        new ResourceReader(reader, kind("tenants"), Optional.empty()).read().rows().getFirst();

    assertEquals("acme", row.cells().getFirst());
    assertEquals(List.of("", "", "", ""), row.cells().subList(1, row.cells().size()));
  }

  @Test
  void a_kind_whose_route_wraps_its_collection_reads_through_the_wrapper() {
    // /volumes answers with an object, since it also reports the nodes it could not reach.
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                "/volumes",
                Map.of(
                    "volumes",
                    List.of(
                        Map.of(
                            "statefulSet", "ledger",
                            "instanceIndex", 0,
                            "tenantId", "acme",
                            "nodeId", "node-alpha",
                            "volumeName", "data",
                            "inUse", true)),
                    "unreachableNodes",
                    List.of("node-beta")));

    ResourceRow row =
        new ResourceReader(reader, kind("volumes"), Optional.empty()).read().rows().getFirst();

    assertEquals(List.of("ledger", "0", "acme", "node-alpha", "yes"), row.cells());
    assertEquals("data", row.name());
  }

  @Test
  void a_wrapped_route_answering_an_unexpected_shape_reads_as_empty_rather_than_being_guessed_at() {
    FakeClusterReader reader = new FakeClusterReader().withObject("/volumes", Map.of("volumes", 7));

    assertTrue(
        new ResourceReader(reader, kind("volumes"), Optional.empty()).read().rows().isEmpty());
  }

  @Test
  void a_refusal_of_permission_is_a_state_to_report_rather_than_an_error_to_throw() {
    // An empty table reads as "this cluster has no tenants", a different and worse claim.
    FakeClusterReader reader = new FakeClusterReader();
    reader.failWith(CliException.forbidden("forbidden"));

    ResourceSnapshot snapshot =
        new ResourceReader(reader, kind("tenants"), Optional.empty()).read();

    assertFalse(snapshot.permitted());
    assertTrue(snapshot.rows().isEmpty());
  }

  @Test
  void any_other_failure_is_left_to_the_poller_to_report_as_a_stale_reading() {
    FakeClusterReader reader = new FakeClusterReader();
    reader.failWith(CliException.unavailable("connection refused"));

    assertThrows(
        CliException.class,
        () -> new ResourceReader(reader, kind("tenants"), Optional.empty()).read());
  }

  @Test
  void a_custom_kind_reads_the_columns_its_own_definition_declared() {
    ResourceKind greetings =
        ResourceKind.fromDefinition(
            "Greeting",
            "greetings",
            Optional.of("a greeting"),
            List.of(ResourceColumn.of("MESSAGE", "spec.message")));
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList(
                "/resources/Greeting",
                List.of(
                    Map.of(
                        "kind", "Greeting",
                        "name", "hello",
                        "tenantId", "acme",
                        "spec", Map.of("message", "góðan dag"))));

    ResourceRow row =
        new ResourceReader(reader, greetings, Optional.empty()).read().rows().getFirst();

    assertEquals(List.of("hello", "acme", "góðan dag"), row.cells());
  }

  @Test
  void a_workload_kind_reads_the_spec_wrapper_its_list_route_actually_answers_with() {
    // These routes answer {spec:{...}, instances:[...], unplacedCount}, not a flat object, so
    // every column path here has to go through the wrapper.
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList(
                "/deployments",
                List.of(
                    Map.of(
                        "spec",
                            Map.of(
                                "name", "checkout-api",
                                "tenantId", "acme",
                                "replicas", 3,
                                "moduleId", "com.example:checkout:1.2.0"),
                        "unplacedCount", 1,
                        "instances", List.of(Map.of("instanceIndex", 0)))));

    ResourceRow row =
        new ResourceReader(reader, kind("deployments"), Optional.empty()).read().rows().getFirst();

    assertEquals(
        List.of("checkout-api", "acme", "3", "1", "com.example:checkout:1.2.0"), row.cells());
    assertEquals("checkout-api", row.name());
    assertEquals(Optional.of("acme"), row.tenantId());
  }

  // ---- the kinds that list one tenant's own holdings ----

  @Test
  void a_tenant_scoped_kind_asks_the_route_for_the_tenant_in_scope() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList(
                "/config/acme",
                List.of(Map.of("key", "log.level", "value", "DEBUG", "encrypted", false)));

    ResourceRow row =
        new ResourceReader(reader, kind("config"), Optional.of("acme")).read().rows().getFirst();

    assertEquals(List.of("log.level", "no", "DEBUG"), row.cells());
  }

  @Test
  void an_encrypted_config_entry_keeps_its_row_and_loses_its_value() {
    // The flat listing hands back every value already decrypted, the encrypted ones included --
    // the one read this view makes that could put a secret on a screen.
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList(
                "/config/acme",
                List.of(Map.of("key", "db.password", "value", "hunter2", "encrypted", true)));

    ResourceRow row =
        new ResourceReader(reader, kind("config"), Optional.of("acme")).read().rows().getFirst();

    assertEquals("db.password", row.name());
    assertFalse(row.cells().contains("hunter2"), row.cells().toString());
    assertFalse(row.raw().toString().contains("hunter2"), "the describe pane reads the raw object");
  }

  @Test
  void a_route_answering_with_bare_names_browses_as_rows_carrying_that_name() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject("/secretmaps/acme", Map.of("names", List.of("db", "smtp")));

    List<ResourceRow> rows =
        new ResourceReader(reader, kind("secretmaps"), Optional.of("acme")).read().rows();

    assertEquals(List.of("db", "smtp"), rows.stream().map(ResourceRow::name).toList());
  }

  @Test
  void a_secret_listing_carries_versions_and_never_a_value() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                "/secrets/acme",
                Map.of("secrets", List.of(Map.of("key", "db.password", "latestVersion", 3))));

    ResourceRow row =
        new ResourceReader(reader, kind("secrets"), Optional.of("acme")).read().rows().getFirst();

    assertEquals(List.of("db.password", "3"), row.cells());
  }

  private static ResourceKind kind(final String key) {
    return ResourceKind.builtIns().stream()
        .filter(candidate -> candidate.key().equals(key))
        .findFirst()
        .orElseThrow();
  }
}
