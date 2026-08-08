package com.gimle.core.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ServiceExport;
import com.gimle.core.module.Version;
import java.util.List;
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
        new ControlMessage.InstallModule("corr-3", "/var/gimle/artifacts/orders-1.4.2.jar"),
        new ControlMessage.ResolveModule("corr-4", ID),
        new ControlMessage.StartModule("corr-5", ID),
        new ControlMessage.StopModule("corr-6", ID),
        new ControlMessage.UninstallModule("corr-7", ID),
        new ControlMessage.Ping("corr-8"));
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
  void roundTripsEveryMessageVariant(ControlMessage original) {
    String encoded = ControlMessageCodec.encode(original);
    ControlMessage decoded = ControlMessageCodec.decode(encoded);
    assertEquals(original, decoded);
  }

  @ParameterizedTest
  @MethodSource("allMessageVariants")
  void encodedFormIsASingleLine(ControlMessage original) {
    String encoded = ControlMessageCodec.encode(original);
    assertFalse(encoded.contains("\n"), "encoded frame must not contain a raw newline: " + encoded);
    assertFalse(
        encoded.contains("\r"), "encoded frame must not contain a raw carriage return: " + encoded);
  }

  @ParameterizedTest
  @MethodSource("freeTextEdgeCases")
  void nackReasonWithNewlinesAndSpacesRoundTrips(String reason) {
    ControlMessage.Nack original = new ControlMessage.Nack("corr-1", reason);
    String encoded = ControlMessageCodec.encode(original);
    assertFalse(
        encoded.contains("\n"), "a literal newline in the payload must not leak into framing");
    assertFalse(encoded.contains("\r"));
    assertEquals(original, ControlMessageCodec.decode(encoded));
  }

  @ParameterizedTest
  @MethodSource("freeTextEdgeCases")
  void installArtifactPathWithSpecialCharactersRoundTrips(String suffix) {
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
    // coverage above when the sealed hierarchy grows.
    List<Class<?>> permitted = List.of(ControlMessage.class.getPermittedSubclasses());
    assertTrue(permitted.size() >= 16, "expected at least the 16 currently-known variants");
  }
}
