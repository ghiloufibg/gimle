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

    ResourceSnapshot snapshot = new ResourceReader(reader, kind("tenants")).read();
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
        tenant, new ResourceReader(reader, kind("tenants")).read().rows().getFirst().raw());
  }

  @Test
  void a_field_the_response_does_not_carry_costs_its_own_cell_and_nothing_else() {
    FakeClusterReader reader =
        new FakeClusterReader().withList("/tenants", List.of(Map.of("id", "acme")));

    ResourceRow row = new ResourceReader(reader, kind("tenants")).read().rows().getFirst();

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

    ResourceRow row = new ResourceReader(reader, kind("volumes")).read().rows().getFirst();

    assertEquals(List.of("ledger", "0", "acme", "node-alpha", "yes"), row.cells());
    assertEquals("data", row.name());
  }

  @Test
  void a_wrapped_route_answering_an_unexpected_shape_reads_as_empty_rather_than_being_guessed_at() {
    FakeClusterReader reader = new FakeClusterReader().withObject("/volumes", Map.of("volumes", 7));

    assertTrue(new ResourceReader(reader, kind("volumes")).read().rows().isEmpty());
  }

  @Test
  void a_refusal_of_permission_is_a_state_to_report_rather_than_an_error_to_throw() {
    // An empty table reads as "this cluster has no tenants", a different and worse claim.
    FakeClusterReader reader = new FakeClusterReader();
    reader.failWith(CliException.forbidden("forbidden"));

    ResourceSnapshot snapshot = new ResourceReader(reader, kind("tenants")).read();

    assertFalse(snapshot.permitted());
    assertTrue(snapshot.rows().isEmpty());
  }

  @Test
  void any_other_failure_is_left_to_the_poller_to_report_as_a_stale_reading() {
    FakeClusterReader reader = new FakeClusterReader();
    reader.failWith(CliException.unavailable("connection refused"));

    assertThrows(CliException.class, () -> new ResourceReader(reader, kind("tenants")).read());
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

    ResourceRow row = new ResourceReader(reader, greetings).read().rows().getFirst();

    assertEquals(List.of("hello", "acme", "góðan dag"), row.cells());
  }

  private static ResourceKind kind(final String key) {
    return ResourceKind.builtIns().stream()
        .filter(candidate -> candidate.key().equals(key))
        .findFirst()
        .orElseThrow();
  }
}
