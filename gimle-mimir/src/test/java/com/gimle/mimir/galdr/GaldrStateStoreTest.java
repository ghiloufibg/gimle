package com.gimle.mimir.galdr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.mimir.raft.MutationOutcome;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.StateSnapshot;
import com.gimle.mimir.store.StateStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Galdr slice of {@link StateStore}'s contract, exercised through {@link StateMutation} exactly
 * the way committed Raft entries reach the store in production: generation CAS on both puts, status
 * semantics, the remove-definition-while-instances-exist refusal, and snapshot/restore keeping
 * generations intact.
 */
class GaldrStateStoreTest {

  private static final String KIND = "custom.Greeting";
  private static final Optional<String> TENANT = Optional.of("tenant-1");

  private StateStore store;

  @BeforeEach
  void createStore() {
    store = new StateStore();
  }

  private static KindDefinitionSpec definition(String kindName) {
    return new KindDefinitionSpec(
        kindName,
        KindScope.TENANT,
        "a greeting this cluster should keep saying",
        new KindNames(Optional.of("greetings"), List.of("gr")),
        new SchemaModel(
            List.of(
                new SchemaField.StringField("message", true, Optional.empty(), OptionalInt.empty()),
                new SchemaField.IntField(
                    "repeat",
                    false,
                    OptionalLong.of(1L),
                    OptionalLong.of(1L),
                    OptionalLong.of(100L)))),
        List.of(new PrintColumn("MESSAGE", "spec.message")),
        0L);
  }

  private static CustomResource resource(String name, String specJson) {
    return new CustomResource(
        KIND, name, TENANT, specJson.getBytes(StandardCharsets.UTF_8), new byte[0], 0L);
  }

  private MutationOutcome apply(StateMutation mutation) {
    return mutation.applyTo(store);
  }

  private static boolean accepted(MutationOutcome outcome) {
    return outcome instanceof MutationOutcome.Accepted;
  }

  private static String rejectionReason(MutationOutcome outcome) {
    return ((MutationOutcome.Rejected) outcome).reason();
  }

  // ---- definition lifecycle ----

  @Test
  void stores_a_definition_with_a_store_assigned_generation_lineage() {
    assertTrue(accepted(apply(new StateMutation.PutKindDefinition(definition(KIND), 0L))));
    assertEquals(1L, store.getKindDefinitionGeneration(KIND));

    assertTrue(accepted(apply(new StateMutation.PutKindDefinition(definition(KIND), 1L))));
    assertEquals(2L, store.getKindDefinition(KIND).orElseThrow().generation());
    assertEquals(
        List.of(KIND),
        store.listKindDefinitions().stream().map(KindDefinitionSpec::kindName).toList());
  }

  @Test
  void rejects_a_definition_put_whose_expected_generation_is_stale() {
    apply(new StateMutation.PutKindDefinition(definition(KIND), 0L));

    MutationOutcome lostRace = apply(new StateMutation.PutKindDefinition(definition(KIND), 0L));

    assertFalse(accepted(lostRace));
    assertTrue(rejectionReason(lostRace).contains("generation 1, expected 0"));
    assertEquals(1L, store.getKindDefinitionGeneration(KIND));
  }

  @Test
  void refuses_to_remove_a_definition_while_instances_of_it_exist() {
    apply(new StateMutation.PutKindDefinition(definition(KIND), 0L));
    apply(new StateMutation.PutCustomResource(resource("hello-world", "{}"), 0L));

    MutationOutcome refused = apply(new StateMutation.RemoveKindDefinition(KIND));

    assertFalse(accepted(refused));
    assertTrue(rejectionReason(refused).contains("1 instance(s)"));
    assertTrue(store.getKindDefinition(KIND).isPresent());

    apply(new StateMutation.RemoveCustomResource(KIND, TENANT, "hello-world"));
    assertTrue(accepted(apply(new StateMutation.RemoveKindDefinition(KIND))));
    assertTrue(store.getKindDefinition(KIND).isEmpty());
  }

  @Test
  void a_removed_definition_restarts_its_generation_lineage_at_one() {
    apply(new StateMutation.PutKindDefinition(definition(KIND), 0L));
    apply(new StateMutation.RemoveKindDefinition(KIND));
    assertEquals(0L, store.getKindDefinitionGeneration(KIND));

    apply(new StateMutation.PutKindDefinition(definition(KIND), 0L));
    assertEquals(1L, store.getKindDefinitionGeneration(KIND));
  }

  // ---- instance lifecycle and the CAS ----

  @Test
  void stores_an_instance_bumping_generation_on_each_accepted_spec_put() {
    assertTrue(
        accepted(apply(new StateMutation.PutCustomResource(resource("hello", "{\"a\":1}"), 0L))));
    assertEquals(1L, store.getCustomResource(KIND, TENANT, "hello").orElseThrow().generation());

    assertTrue(
        accepted(apply(new StateMutation.PutCustomResource(resource("hello", "{\"a\":2}"), 1L))));
    CustomResource updated = store.getCustomResource(KIND, TENANT, "hello").orElseThrow();
    assertEquals(2L, updated.generation());
    assertArrayEquals("{\"a\":2}".getBytes(StandardCharsets.UTF_8), updated.specJson());
  }

  @Test
  void rejects_a_spec_put_whose_expected_generation_is_stale_leaving_the_store_untouched() {
    apply(new StateMutation.PutCustomResource(resource("hello", "{\"a\":1}"), 0L));
    apply(new StateMutation.PutCustomResource(resource("hello", "{\"a\":2}"), 1L));

    MutationOutcome lostRace =
        apply(new StateMutation.PutCustomResource(resource("hello", "{\"a\":9}"), 1L));

    assertFalse(accepted(lostRace));
    assertTrue(rejectionReason(lostRace).contains("generation 2, expected 1"));
    CustomResource current = store.getCustomResource(KIND, TENANT, "hello").orElseThrow();
    assertEquals(2L, current.generation());
    assertArrayEquals("{\"a\":2}".getBytes(StandardCharsets.UTF_8), current.specJson());
  }

  @Test
  void a_delete_that_committed_first_defeats_a_stale_racing_apply() {
    apply(new StateMutation.PutCustomResource(resource("hello", "{}"), 0L));
    apply(new StateMutation.RemoveCustomResource(KIND, TENANT, "hello"));

    MutationOutcome staleApply =
        apply(new StateMutation.PutCustomResource(resource("hello", "{}"), 1L));

    assertFalse(accepted(staleApply));
    assertTrue(store.getCustomResource(KIND, TENANT, "hello").isEmpty());
  }

  // ---- status semantics ----

  @Test
  void a_status_put_replaces_status_without_bumping_the_generation() {
    apply(new StateMutation.PutCustomResource(resource("hello", "{}"), 0L));

    apply(
        new StateMutation.PutCustomResourceStatus(
            KIND, TENANT, "hello", "{\"timesSaid\":3}".getBytes(StandardCharsets.UTF_8)));

    CustomResource current = store.getCustomResource(KIND, TENANT, "hello").orElseThrow();
    assertEquals(1L, current.generation());
    assertArrayEquals("{\"timesSaid\":3}".getBytes(StandardCharsets.UTF_8), current.statusJson());
  }

  @Test
  void a_spec_update_preserves_the_operator_reported_status() {
    apply(new StateMutation.PutCustomResource(resource("hello", "{\"a\":1}"), 0L));
    apply(
        new StateMutation.PutCustomResourceStatus(
            KIND, TENANT, "hello", "{\"timesSaid\":3}".getBytes(StandardCharsets.UTF_8)));

    apply(new StateMutation.PutCustomResource(resource("hello", "{\"a\":2}"), 1L));

    CustomResource current = store.getCustomResource(KIND, TENANT, "hello").orElseThrow();
    assertArrayEquals("{\"timesSaid\":3}".getBytes(StandardCharsets.UTF_8), current.statusJson());
    assertArrayEquals("{\"a\":2}".getBytes(StandardCharsets.UTF_8), current.specJson());
  }

  @Test
  void a_status_for_a_deleted_instance_is_silently_dropped() {
    apply(
        new StateMutation.PutCustomResourceStatus(
            KIND, TENANT, "gone", "{\"timesSaid\":1}".getBytes(StandardCharsets.UTF_8)));
    assertTrue(store.getCustomResource(KIND, TENANT, "gone").isEmpty());
    assertEquals(List.of(), store.listCustomResources(KIND));
  }

  // ---- reads and scoping ----

  @Test
  void list_reads_filter_by_kind_and_tenant() {
    apply(new StateMutation.PutCustomResource(resource("hello", "{}"), 0L));
    apply(
        new StateMutation.PutCustomResource(
            new CustomResource(
                KIND, "other", Optional.of("tenant-2"), new byte[0], new byte[0], 0L),
            0L));
    apply(
        new StateMutation.PutCustomResource(
            new CustomResource("acme.FeatureFlag", "hello", TENANT, new byte[0], new byte[0], 0L),
            0L));

    assertEquals(2, store.listCustomResources(KIND).size());
    assertEquals(1, store.listCustomResourcesFor(KIND, TENANT).size());
    assertEquals("hello", store.listCustomResourcesFor(KIND, TENANT).get(0).name());
    assertEquals(1, store.listCustomResources("acme.FeatureFlag").size());
  }

  @Test
  void the_same_name_under_two_tenants_and_the_untenanted_namespace_never_collide() {
    apply(new StateMutation.PutCustomResource(resource("hello", "{\"who\":\"t1\"}"), 0L));
    apply(
        new StateMutation.PutCustomResource(
            new CustomResource(
                KIND,
                "hello",
                Optional.of("tenant-2"),
                "{\"who\":\"t2\"}".getBytes(StandardCharsets.UTF_8),
                new byte[0],
                0L),
            0L));
    apply(
        new StateMutation.PutCustomResource(
            new CustomResource(
                KIND,
                "hello",
                Optional.empty(),
                "{\"who\":\"untenanted\"}".getBytes(StandardCharsets.UTF_8),
                new byte[0],
                0L),
            0L));

    assertEquals(3, store.listCustomResources(KIND).size());
    assertArrayEquals(
        "{\"who\":\"t2\"}".getBytes(StandardCharsets.UTF_8),
        store.getCustomResource(KIND, Optional.of("tenant-2"), "hello").orElseThrow().specJson());
    assertArrayEquals(
        "{\"who\":\"untenanted\"}".getBytes(StandardCharsets.UTF_8),
        store.getCustomResource(KIND, Optional.empty(), "hello").orElseThrow().specJson());
  }

  // ---- snapshot / restore ----

  @Test
  void snapshot_and_restore_round_trip_definitions_instances_and_their_generations() {
    apply(new StateMutation.PutKindDefinition(definition(KIND), 0L));
    apply(new StateMutation.PutKindDefinition(definition(KIND), 1L));
    apply(new StateMutation.PutKindDefinition(definition("acme.FeatureFlag"), 0L));
    apply(new StateMutation.PutCustomResource(resource("hello", "{\"a\":1}"), 0L));
    apply(new StateMutation.PutCustomResource(resource("hello", "{\"a\":2}"), 1L));
    apply(
        new StateMutation.PutCustomResourceStatus(
            KIND, TENANT, "hello", "{\"timesSaid\":3}".getBytes(StandardCharsets.UTF_8)));

    StateSnapshot snapshot = store.snapshot();
    StateStore restored = new StateStore();
    restored.restoreFromSnapshot(snapshot);

    assertEquals(2, restored.listKindDefinitions().size());
    // Generations survive verbatim, so a restored replica's CAS decisions agree with the rest of
    // the cluster instead of restarting every lineage at 1.
    assertEquals(2L, restored.getKindDefinitionGeneration(KIND));
    assertEquals(1L, restored.getKindDefinitionGeneration("acme.FeatureFlag"));
    CustomResource restoredResource =
        restored.getCustomResource(KIND, TENANT, "hello").orElseThrow();
    assertEquals(2L, restoredResource.generation());
    assertArrayEquals("{\"a\":2}".getBytes(StandardCharsets.UTF_8), restoredResource.specJson());
    assertArrayEquals(
        "{\"timesSaid\":3}".getBytes(StandardCharsets.UTF_8), restoredResource.statusJson());

    // The next CAS continues from the restored lineage, not from a fresh one.
    assertTrue(
        accepted(
            new StateMutation.PutCustomResource(resource("hello", "{\"a\":3}"), 2L)
                .applyTo(restored)));
    assertEquals(3L, restored.getCustomResource(KIND, TENANT, "hello").orElseThrow().generation());
  }

  @Test
  void restore_wipes_galdr_state_the_snapshot_does_not_carry() {
    apply(new StateMutation.PutKindDefinition(definition(KIND), 0L));
    apply(new StateMutation.PutCustomResource(resource("hello", "{}"), 0L));
    StateSnapshot empty = new StateStore().snapshot();

    store.restoreFromSnapshot(empty);

    assertEquals(List.of(), store.listKindDefinitions());
    assertEquals(List.of(), store.listCustomResources(KIND));
    assertEquals(0L, store.getKindDefinitionGeneration(KIND));
  }
}
