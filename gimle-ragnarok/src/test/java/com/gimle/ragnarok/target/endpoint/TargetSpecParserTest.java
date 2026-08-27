package com.gimle.ragnarok.target.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.tls.TransportProtocol;
import com.gimle.ragnarok.RagnarokException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Parsing and validation of target documents. */
final class TargetSpecParserTest {

  @TempDir private Path tempDir;

  private static TargetSpec parse(final String yaml) {
    return TargetSpecParser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void parses_a_plaintext_target() {
    final TargetSpec spec =
        parse(
            """
            controlPlaneBaseUrls: [http://cp-0:8080, http://cp-1:8080]
            storeClientEndpoints: [store-0:7100, store-1:7100, store-2:7100]
            """);
    assertEquals(2, spec.controlPlaneBaseUrls().size());
    assertEquals(3, spec.storeClientEndpoints().size());
    assertEquals(TransportProtocol.PLAINTEXT, spec.transport());
    assertTrue(spec.tls().isEmpty());
    assertTrue(spec.muninnBaseUrls().isEmpty());
    assertTrue(spec.andvariBaseUrls().isEmpty());
  }

  @Test
  void parses_an_mtls_target_with_real_file_paths() throws Exception {
    final Path cert = Files.writeString(tempDir.resolve("operator.crt"), "cert");
    final Path key = Files.writeString(tempDir.resolve("operator.key"), "key");
    final Path ca = Files.writeString(tempDir.resolve("ca.crt"), "ca");
    final TargetSpec spec =
        parse(
            """
            controlPlaneBaseUrls: [https://cp-0:8443]
            transport: mtls
            tls:
              certFile: %s
              keyFile: %s
              caFile: %s
            """
                .formatted(cert, key, ca));
    assertEquals(TransportProtocol.TLS, spec.transport());
    assertTrue(spec.tls().isPresent());
    assertEquals(cert, spec.tls().get().certFile());
  }

  @Test
  void mtls_without_a_tls_block_is_rejected() {
    assertThrows(
        RagnarokException.class,
        () -> parse("controlPlaneBaseUrls: [https://cp-0:8443]\ntransport: mtls\n"));
  }

  @Test
  void an_unknown_transport_value_is_rejected() {
    assertThrows(
        RagnarokException.class,
        () -> parse("controlPlaneBaseUrls: [http://cp-0:8080]\ntransport: bogus\n"));
  }

  @Test
  void no_control_plane_base_urls_is_rejected() {
    assertThrows(RagnarokException.class, () -> parse("controlPlaneBaseUrls: []\n"));
  }

  @Test
  void malformed_host_port_in_store_client_endpoints_fails_on_open() {
    final TargetSpec spec =
        parse(
            """
            controlPlaneBaseUrls: [http://cp-0:8080]
            storeClientEndpoints: [not-a-host-port]
            """);
    assertThrows(RagnarokException.class, spec::open);
  }

  @Test
  void malformed_yaml_is_rejected() {
    assertThrows(RagnarokException.class, () -> parse("not: [valid"));
  }
}
