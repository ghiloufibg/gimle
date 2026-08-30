package com.gimle.mimir.galdr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.mimir.raft.LogEntry;
import com.gimle.mimir.raft.RaftCodec;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.rpc.StoreCodec;
import com.gimle.mimir.rpc.StoreRpc;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Round-trips for the five Galdr {@link StateMutation}s and the Galdr {@link StoreRpc}
 * request/response variants. Variants carrying a {@code byte[]} transitively (a {@link
 * CustomResource}'s spec/status) are field-compared rather than {@code equals}-compared, the same
 * reason {@code RaftCodecTest} field-compares {@code InstallSnapshot}.
 */
class GaldrCodecTest {

  /** Every schema field type at once, nested, so no encoder branch can be silently skipped. */
  private static SchemaModel everyFieldTypeSchema() {
    return new SchemaModel(
        List.of(
            new SchemaField.StringField("message", true, Optional.empty(), OptionalInt.of(80)),
            new SchemaField.IntField(
                "repeat", false, OptionalLong.of(1L), OptionalLong.of(1L), OptionalLong.of(100L)),
            new SchemaField.DoubleField(
                "ratio",
                false,
                OptionalDouble.of(0.5),
                OptionalDouble.of(0.0),
                OptionalDouble.of(1.0)),
            new SchemaField.BoolField("enabled", false, Optional.of(Boolean.TRUE)),
            new SchemaField.EnumField(
                "tone", false, Optional.of("friendly"), List.of("friendly", "formal")),
            new SchemaField.ListField(
                "tags",
                new SchemaField.StringField("items", false, Optional.empty(), OptionalInt.empty()),
                OptionalInt.of(0),
                OptionalInt.of(5)),
            new SchemaField.ObjectField(
                "extra",
                List.of(
                    new SchemaField.ListField(
                        "entries",
                        new SchemaField.ObjectField(
                            "items",
                            List.of(new SchemaField.BoolField("flag", false, Optional.empty()))),
                        OptionalInt.empty(),
                        OptionalInt.empty())))));
  }

  private static KindDefinitionSpec definition() {
    return new KindDefinitionSpec(
        "custom.Greeting",
        KindScope.TENANT,
        "a greeting this cluster should keep saying",
        new KindNames(Optional.of("greetings"), List.of("gr", "greet")),
        everyFieldTypeSchema(),
        List.of(
            new PrintColumn("MESSAGE", "spec.message"),
            new PrintColumn("SAID", "status.timesSaid")),
        3L);
  }

  private static KindDefinitionSpec clusterScopedBareDefinition() {
    return new KindDefinitionSpec(
        "acme.FeatureFlag",
        KindScope.CLUSTER,
        "",
        KindNames.none(),
        new SchemaModel(List.of()),
        List.of(),
        1L);
  }

  private static CustomResource resource() {
    return new CustomResource(
        "custom.Greeting",
        "hello-world",
        Optional.of("tenant-1"),
        "{\"message\":\"hello\",\"repeat\":3}".getBytes(StandardCharsets.UTF_8),
        "{\"timesSaid\":3,\"observedGeneration\":1}".getBytes(StandardCharsets.UTF_8),
        2L);
  }

  private static LogEntry roundTripped(StateMutation mutation) {
    LogEntry entry = new LogEntry(7L, 42L, mutation);
    return RaftCodec.decodeLogEntry(RaftCodec.encodeLogEntry(entry));
  }

  // ---- the five mutations through the Raft log encoding ----

  @Test
  void round_trips_a_put_kind_definition_mutation_carrying_every_schema_field_type() {
    LogEntry decoded = roundTripped(new StateMutation.PutKindDefinition(definition(), 2L));
    StateMutation.PutKindDefinition mutation = (StateMutation.PutKindDefinition) decoded.payload();
    assertEquals(definition(), mutation.spec());
    assertEquals(2L, mutation.expectedGeneration());
  }

  @Test
  void round_trips_a_cluster_scoped_definition_with_no_names_columns_or_fields() {
    LogEntry decoded =
        roundTripped(new StateMutation.PutKindDefinition(clusterScopedBareDefinition(), 0L));
    assertEquals(
        clusterScopedBareDefinition(),
        ((StateMutation.PutKindDefinition) decoded.payload()).spec());
  }

  @Test
  void round_trips_a_remove_kind_definition_mutation() {
    LogEntry decoded = roundTripped(new StateMutation.RemoveKindDefinition("custom.Greeting"));
    assertEquals(
        "custom.Greeting", ((StateMutation.RemoveKindDefinition) decoded.payload()).kindName());
  }

  @Test
  void round_trips_a_put_custom_resource_mutation_carrying_arbitrary_json_bytes() {
    LogEntry decoded = roundTripped(new StateMutation.PutCustomResource(resource(), 1L));
    StateMutation.PutCustomResource mutation = (StateMutation.PutCustomResource) decoded.payload();
    assertEquals(resource().kindName(), mutation.resource().kindName());
    assertEquals(resource().name(), mutation.resource().name());
    assertEquals(resource().tenantId(), mutation.resource().tenantId());
    assertEquals(resource().generation(), mutation.resource().generation());
    assertArrayEquals(resource().specJson(), mutation.resource().specJson());
    assertArrayEquals(resource().statusJson(), mutation.resource().statusJson());
    assertEquals(1L, mutation.expectedGeneration());
  }

  @Test
  void round_trips_a_remove_custom_resource_mutation_tenanted_and_untenanted() {
    LogEntry tenanted =
        roundTripped(
            new StateMutation.RemoveCustomResource(
                "custom.Greeting", Optional.of("tenant-1"), "hello-world"));
    assertEquals(
        new StateMutation.RemoveCustomResource(
            "custom.Greeting", Optional.of("tenant-1"), "hello-world"),
        tenanted.payload());

    LogEntry untenanted =
        roundTripped(
            new StateMutation.RemoveCustomResource("acme.FeatureFlag", Optional.empty(), "flag-1"));
    assertEquals(
        new StateMutation.RemoveCustomResource("acme.FeatureFlag", Optional.empty(), "flag-1"),
        untenanted.payload());
  }

  @Test
  void round_trips_a_put_custom_resource_status_mutation_carrying_arbitrary_bytes() {
    byte[] status = {123, 34, 0, -1, 125};
    LogEntry decoded =
        roundTripped(
            new StateMutation.PutCustomResourceStatus(
                "custom.Greeting", Optional.of("tenant-1"), "hello-world", status));
    StateMutation.PutCustomResourceStatus mutation =
        (StateMutation.PutCustomResourceStatus) decoded.payload();
    assertEquals("custom.Greeting", mutation.kindName());
    assertEquals(Optional.of("tenant-1"), mutation.tenantId());
    assertEquals("hello-world", mutation.name());
    assertArrayEquals(status, mutation.statusJson());
  }

  // ---- the StoreRpc surface ----

  static Stream<StoreRpc> equalityRoundTrippableVariants() {
    return Stream.of(
        new StoreRpc.ListKindDefinitions(),
        new StoreRpc.GetKindDefinition("custom.Greeting"),
        new StoreRpc.ListCustomResources("custom.Greeting"),
        new StoreRpc.ListCustomResourcesFor("custom.Greeting", Optional.of("tenant-1")),
        new StoreRpc.ListCustomResourcesFor("acme.FeatureFlag", Optional.empty()),
        new StoreRpc.GetCustomResource("custom.Greeting", Optional.of("tenant-1"), "hello-world"),
        new StoreRpc.GetCustomResource("acme.FeatureFlag", Optional.empty(), "flag-1"),
        new StoreRpc.KindDefinitionResult(true, definition()),
        new StoreRpc.KindDefinitionResult(false, null),
        new StoreRpc.KindDefinitionListResult(List.of(definition(), clusterScopedBareDefinition())),
        new StoreRpc.KindDefinitionListResult(List.of()),
        new StoreRpc.CustomResourceResult(false, null),
        new StoreRpc.CustomResourceListResult(List.of()));
  }

  @ParameterizedTest
  @MethodSource("equalityRoundTrippableVariants")
  void round_trips_galdr_store_rpc_variants(StoreRpc original) throws IOException {
    assertEquals(original, storeRoundTripped(original));
  }

  @Test
  void round_trips_a_custom_resource_result_carrying_arbitrary_json_bytes() throws IOException {
    StoreRpc.CustomResourceResult decoded =
        (StoreRpc.CustomResourceResult)
            storeRoundTripped(new StoreRpc.CustomResourceResult(true, resource()));
    assertEquals(resource().kindName(), decoded.value().kindName());
    assertEquals(resource().name(), decoded.value().name());
    assertEquals(resource().tenantId(), decoded.value().tenantId());
    assertEquals(resource().generation(), decoded.value().generation());
    assertArrayEquals(resource().specJson(), decoded.value().specJson());
    assertArrayEquals(resource().statusJson(), decoded.value().statusJson());
  }

  @Test
  void round_trips_a_custom_resource_list_result() throws IOException {
    StoreRpc.CustomResourceListResult decoded =
        (StoreRpc.CustomResourceListResult)
            storeRoundTripped(new StoreRpc.CustomResourceListResult(List.of(resource())));
    assertEquals(1, decoded.values().size());
    assertArrayEquals(resource().specJson(), decoded.values().get(0).specJson());
  }

  private static StoreRpc storeRoundTripped(StoreRpc original) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    StoreCodec.write(buffer, original);
    return StoreCodec.read(new ByteArrayInputStream(buffer.toByteArray()));
  }
}
