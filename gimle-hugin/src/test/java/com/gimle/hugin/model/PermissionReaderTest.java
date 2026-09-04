package com.gimle.hugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.cli.CliException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Asking the control plane what this caller may do, one cell at a time.
 *
 * <p>The distinction under test throughout is denial against silence: a cell nobody answered must
 * never be drawn as a refusal, because the two are indistinguishable once drawn and only one of
 * them is a statement about anybody's grants.
 */
class PermissionReaderTest {

  @Test
  void the_grid_is_the_vocabulary_the_control_plane_names_rather_than_one_this_build_assumes() {
    // Kinds are added to the platform over time; a hard-coded list would quietly stop covering it.
    FakeClusterReader reader =
        vocabulary(List.of("DEPLOYMENT", "SECRET"), List.of("READ", "WRITE"))
            .withObject(canI("DEPLOYMENT", "READ"), allowed(true))
            .withObject(canI("DEPLOYMENT", "WRITE"), allowed(false))
            .withObject(canI("SECRET", "READ"), allowed(false))
            .withObject(canI("SECRET", "WRITE"), allowed(false));

    PermissionSnapshot snapshot = read(reader);

    assertEquals(List.of("READ", "WRITE"), snapshot.verbs());
    assertEquals(
        List.of("DEPLOYMENT", "SECRET"),
        snapshot.rows().stream().map(PermissionRow::kind).toList());
    assertEquals(Optional.of(true), snapshot.rows().getFirst().allowed("READ"));
    assertEquals(Optional.of(false), snapshot.rows().getFirst().allowed("WRITE"));
  }

  @Test
  void a_cell_the_control_plane_never_answered_reads_as_unanswered_and_not_as_denied() {
    FakeClusterReader reader =
        vocabulary(List.of("DEPLOYMENT"), List.of("READ", "WRITE"))
            .withObject(canI("DEPLOYMENT", "READ"), allowed(true));

    PermissionSnapshot snapshot = read(reader);

    assertEquals(Optional.of(true), snapshot.rows().getFirst().allowed("READ"));
    assertTrue(snapshot.rows().getFirst().allowed("WRITE").isEmpty());
    assertEquals(1, snapshot.unansweredCount());
  }

  @Test
  void the_identity_the_answers_were_given_for_is_carried_because_the_grid_is_worthless_without() {
    FakeClusterReader reader =
        vocabulary(List.of("DEPLOYMENT"), List.of("READ"))
            .withObject(
                canI("DEPLOYMENT", "READ"), Map.of("allowed", true, "principal", "ops@acme"));

    assertEquals("ops@acme", read(reader).principal());
  }

  @Test
  void a_caller_the_control_plane_could_not_identify_is_recognisable_as_such() {
    // Over plaintext there is no certificate to identify anyone, so every cell says yes and the
    // grid is about the transport rather than about any account's grants.
    FakeClusterReader reader =
        vocabulary(List.of("DEPLOYMENT"), List.of("READ"))
            .withObject(
                canI("DEPLOYMENT", "READ"), Map.of("allowed", true, "principal", "anonymous"));

    assertTrue(read(reader).anonymous());
  }

  @Test
  void a_vocabulary_that_cannot_be_read_is_reported_rather_than_shown_as_an_empty_grid() {
    // An empty grid reads as "you may do nothing", which is a different and much more specific
    // claim than "nobody would tell me".
    FakeClusterReader reader = new FakeClusterReader();
    reader.failWith(CliException.notFound("authentication required"));

    PermissionSnapshot snapshot = read(reader);

    assertFalse(snapshot.readable());
    assertTrue(snapshot.rows().isEmpty());
    assertTrue(snapshot.staleReason().orElse("").contains("authentication required"));
  }

  @Test
  void a_vocabulary_naming_no_verbs_is_a_failure_and_not_a_grid_of_no_columns() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject("/authz/vocabulary", Map.of("resourceKinds", List.of("DEPLOYMENT")));

    assertFalse(read(reader).readable());
  }

  @Test
  void the_tenant_in_scope_is_part_of_every_question_because_the_answer_differs_per_tenant() {
    FakeClusterReader reader = vocabulary(List.of("DEPLOYMENT"), List.of("READ"));

    new PermissionReader(reader, Optional.of("acme")).read();

    assertTrue(
        reader.requestedPaths().stream().anyMatch(path -> path.endsWith("&tenant=acme")),
        reader.requestedPaths().toString());
  }

  @Test
  void a_kind_or_verb_name_is_escaped_rather_than_pasted_into_the_query_string() {
    String path =
        new PermissionReader(new FakeClusterReader(), Optional.of("a b")).path("A B", "R");

    assertEquals("/authz/can-i?resource=A+B&verb=R&tenant=a+b", path);
  }

  @Test
  void a_row_with_nothing_permitted_is_told_apart_from_one_with_something() {
    FakeClusterReader reader =
        vocabulary(List.of("DEPLOYMENT", "SECRET"), List.of("READ"))
            .withObject(canI("DEPLOYMENT", "READ"), allowed(true))
            .withObject(canI("SECRET", "READ"), allowed(false));

    PermissionSnapshot snapshot = read(reader);

    assertEquals(1, snapshot.allowedKindCount());
    assertTrue(snapshot.rows().getFirst().anyAllowed());
    assertFalse(snapshot.rows().getLast().anyAllowed());
  }

  @Test
  void the_filter_narrows_by_kind_and_by_the_verbs_actually_granted() {
    FakeClusterReader reader =
        vocabulary(List.of("DEPLOYMENT", "SECRET"), List.of("READ", "DELETE"))
            .withObject(canI("DEPLOYMENT", "READ"), allowed(true))
            .withObject(canI("DEPLOYMENT", "DELETE"), allowed(false))
            .withObject(canI("SECRET", "READ"), allowed(false))
            .withObject(canI("SECRET", "DELETE"), allowed(true));

    PermissionSnapshot snapshot = read(reader);

    assertEquals(List.of("SECRET"), names(snapshot.matching("secret")));
    assertEquals(List.of("SECRET"), names(snapshot.matching("delete")));
    assertEquals(2, snapshot.matching("").size());
  }

  private static List<String> names(final List<PermissionRow> rows) {
    return rows.stream().map(PermissionRow::kind).toList();
  }

  private static PermissionSnapshot read(final FakeClusterReader reader) {
    return new PermissionReader(reader, Optional.empty()).read();
  }

  private static FakeClusterReader vocabulary(final List<String> kinds, final List<String> verbs) {
    return new FakeClusterReader()
        .withObject("/authz/vocabulary", Map.of("resourceKinds", kinds, "verbs", verbs));
  }

  private static String canI(final String kind, final String verb) {
    return "/authz/can-i?resource=" + kind + "&verb=" + verb;
  }

  private static Map<String, Object> allowed(final boolean value) {
    return Map.of("allowed", value, "principal", "ops@acme");
  }
}
