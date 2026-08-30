package com.gimle.core.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ServiceExport;
import com.gimle.core.module.Version;
import com.gimle.core.tenant.NetworkPolicyRule;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ControlMessageCodecTest {

  private static final ModuleId ID =
      new ModuleId("com.gimle.example.orders", Version.parse("1.4.2-rc1"));

  static Stream<ControlMessage> allMessageVariants() {
    return Stream.of(
        new ControlMessage.Hello("worker-1", 4242L),
        new ControlMessage.Ack("corr-1"),
        new ControlMessage.Nack("corr-1", "simple failure"),
        new ControlMessage.ModuleStateChanged(ID, "ACTIVE"),
        new ControlMessage.HealthReport(ID, true, false),
        new ControlMessage.MetricsReport(ID, 250L, 134217728L),
        new ControlMessage.MetricsReport(ID, 250L, 134217728L, 12.5, 7),
        new ControlMessage.MetricsReport(ID, 250L, 134217728L, 12.5, 7, 1.5),
        new ControlMessage.MetricsReport(
            ID, 250L, 134217728L, 12.5, 7, 1.5, Map.of("HTTP_PORT", 8080, "ADMIN_PORT", 9090)),
        new ControlMessage.ServiceRegistered(
            ID, new ServiceExport("com.gimle.example.Greeter", Version.parse("1.0.0"))),
        new ControlMessage.ServiceUnregistered(
            ID, new ServiceExport("com.gimle.example.Greeter", Version.parse("1.0.0"))),
        new ControlMessage.CatalogUpdate(
            "node-a",
            "worker-1",
            ID,
            new ServiceExport("com.gimle.example.Greeter", Version.parse("1.0.0")),
            42L,
            true,
            "/tmp/worker-1.sock",
            "127.0.0.1",
            9000),
        new ControlMessage.Pong("corr-2"),
        new ControlMessage.ConfigDelivered("db.password", "hunter2", true),
        new ControlMessage.RelayControlPlaneRead("corr-10", "/endpoints/orders-service"),
        new ControlMessage.RelayResourceStatusPut(
            "corr-12",
            "custom.Greeting",
            "acme",
            "hello",
            "{\"timesSaid\":3,\"observedGeneration\":2}"),
        new ControlMessage.RelayResourceStatusPut(
            "corr-13",
            "custom.ClusterThing",
            "",
            "wide",
            "{\"note\":\"spaces and \\\"quotes\\\"\"}"),
        new ControlMessage.RelayControlPlaneResult(
            "corr-10", 200, "[{\"instanceIndex\":0,\"nodeId\":\"node-a\"}]"),
        new ControlMessage.RelayControlPlaneResult(
            "corr-11", 403, "path not whitelisted for relay: /secrets/acme"),
        new ControlMessage.NetworkPoliciesUpdated(
            List.of(
                new NetworkPolicyRule("deny-by-default", "acme", Set.of()),
                new NetworkPolicyRule(
                    "allow list with spaces", "acme corp", Set.of("partner one", "partner-two")),
                new NetworkPolicyRule(
                    "deployment-scoped",
                    "acme",
                    Optional.of(Set.of("orders-service", "payments-service")),
                    Set.of("partner-tenant")))),
        new ControlMessage.NetworkPoliciesUpdated(List.of()),
        new ControlMessage.MetricsSnapshot(
            "worker-1",
            "{\"name\":\"gimle.module.request.count\"}\n{\"name\":\"gimle.module.threads\"}"),
        new ControlMessage.TracesSnapshot(
            "worker-1", "{\"traceId\":\"abc\",\"name\":\"do-something\"}"),
        new ControlMessage.InstallModule("corr-3", "/var/gimle/artifacts/orders-1.4.2.jar"),
        new ControlMessage.RenameInstance("corr-9", ID, "orders-service", 1),
        new ControlMessage.ResolveModule("corr-4", ID),
        new ControlMessage.ResolveModule(
            "corr-4b", ID, Map.of("data", "/var/gimle/volumes/orders-statefulset/0/data")),
        new ControlMessage.StartModule("corr-5", ID),
        new ControlMessage.StopModule("corr-6", ID),
        new ControlMessage.UninstallModule("corr-7", ID),
        new ControlMessage.Ping("corr-8"),
        new ControlMessage.InstanceEventOccurred(
            new InstanceEvent(
                "evt-1", "orders-service", 0, InstanceEventKind.ACTIVE, "module active", 1_000L)),
        new ControlMessage.InstanceEventOccurred(
            new InstanceEvent(
                "evt-2",
                "orders-service",
                0,
                InstanceEventKind.TRANSITION_FAILED,
                "transition ACTIVE -> STOPPING failed",
                Optional.of("java.lang.IllegalStateException: boom with spaces"),
                2_000L)));
  }

  static Stream<String> freeTextEdgeCases() {
    return Stream.of(
        "stack trace line 1\nstack trace line 2\nline 3",
        "message with spaces and words",
        "carriage\r\nreturn style",
        "trailing backslash literal\\",
        "");
  }

  @ParameterizedTest
  @MethodSource("allMessageVariants")
  void round_trips_every_message_variant(ControlMessage original) {
    String encoded = ControlMessageCodec.encode(original);
    ControlMessage decoded = ControlMessageCodec.decode(encoded);
    assertEquals(original, decoded);
  }

  @ParameterizedTest
  @MethodSource("allMessageVariants")
  void encoded_form_is_a_single_line(ControlMessage original) {
    String encoded = ControlMessageCodec.encode(original);
    assertFalse(encoded.contains("\n"), "encoded frame must not contain a raw newline: " + encoded);
    assertFalse(
        encoded.contains("\r"), "encoded frame must not contain a raw carriage return: " + encoded);
  }

  @ParameterizedTest
  @MethodSource("freeTextEdgeCases")
  void nack_reason_with_newlines_and_spaces_round_trips(String reason) {
    ControlMessage.Nack original = new ControlMessage.Nack("corr-1", reason);
    String encoded = ControlMessageCodec.encode(original);
    assertFalse(
        encoded.contains("\n"), "a literal newline in the payload must not leak into framing");
    assertFalse(encoded.contains("\r"));
    assertEquals(original, ControlMessageCodec.decode(encoded));
  }

  @ParameterizedTest
  @MethodSource("freeTextEdgeCases")
  void install_artifact_path_with_special_characters_round_trips(String suffix) {
    ControlMessage.InstallModule original =
        new ControlMessage.InstallModule("corr-1", "C:\\path " + suffix);
    String encoded = ControlMessageCodec.encode(original);
    assertFalse(encoded.contains("\n"));
    assertEquals(original, ControlMessageCodec.decode(encoded));
  }

  @Test
  void module_id_with_qualifier_round_trips() {
    ModuleId qualified = new ModuleId("com.gimle.example.orders", Version.parse("2.0.0-beta.3"));
    ControlMessage.ResolveModule original = new ControlMessage.ResolveModule("corr-1", qualified);
    assertEquals(original, ControlMessageCodec.decode(ControlMessageCodec.encode(original)));
  }

  @Test
  void decode_rejects_empty_line() {
    assertThrows(IllegalArgumentException.class, () -> ControlMessageCodec.decode(""));
  }

  @Test
  void decode_rejects_unknown_message_type() {
    assertThrows(IllegalArgumentException.class, () -> ControlMessageCodec.decode("BOGUS a b c"));
  }

  @Test
  void decode_rejects_missing_fields() {
    assertThrows(
        IllegalArgumentException.class, () -> ControlMessageCodec.decode("HELLO worker-1"));
  }

  @Test
  void decode_rejects_malformed_module_id() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ControlMessageCodec.decode("START corr-1 no-at-sign"));
  }

  @Test
  void all_variants_list_is_exhaustive_sanity_check() {
    // Guards against silently forgetting to add a new ControlMessage subtype to the round-trip
    // coverage above when the sealed hierarchy grows: every permitted subclass must have at least
    // one representative in allMessageVariants(), computed fresh each run so this can't go stale.
    Set<Class<?>> permitted = Set.of(ControlMessage.class.getPermittedSubclasses());
    Set<Class<?>> covered =
        allMessageVariants().map(Object::getClass).collect(Collectors.toUnmodifiableSet());
    assertEquals(
        permitted,
        covered,
        "every permitted ControlMessage subtype must appear in allMessageVariants()");
  }
}
