package com.gimle.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleTlsException;
import com.gimle.core.tls.HostCertificate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GatewayTlsConfigTest {

  @TempDir private Path tempDir;

  private Path ordersCert;
  private Path ordersKey;
  private Path shopCert;
  private Path shopKey;

  @BeforeEach
  void writeCertificateFiles() throws IOException {
    // GatewayTlsConfig only checks that the files exist -- whether their contents are real PEM is
    // SslContexts' concern, proven against a real handshake in GatewayHooksTlsTest.
    ordersCert = Files.writeString(tempDir.resolve("orders-cert.pem"), "cert");
    ordersKey = Files.writeString(tempDir.resolve("orders-key.pem"), "key");
    shopCert = Files.writeString(tempDir.resolve("shop-cert.pem"), "cert");
    shopKey = Files.writeString(tempDir.resolve("shop-key.pem"), "key");
  }

  @Test
  void parses_one_binding_per_line_ignoring_blanks_and_comments() {
    List<HostCertificate> certificates =
        GatewayTlsConfig.parse(
            """
            # hostname          certFile   keyFile

            orders.example.com  %s         %s
            shop.example.com    %s         %s
            """
                .formatted(ordersCert, ordersKey, shopCert, shopKey));

    assertEquals(
        List.of(
            new HostCertificate("orders.example.com", ordersCert, ordersKey),
            new HostCertificate("shop.example.com", shopCert, shopKey)),
        certificates);
  }

  @Test
  void an_absent_config_value_is_simply_no_bindings() {
    assertEquals(List.of(), GatewayTlsConfig.parse(""));
  }

  @Test
  void a_hostname_is_normalized_to_lower_case() {
    List<HostCertificate> certificates =
        GatewayTlsConfig.parse("Orders.Example.COM %s %s".formatted(ordersCert, ordersKey));

    assertEquals("orders.example.com", certificates.get(0).hostname());
  }

  @Test
  void a_line_with_the_wrong_field_count_is_rejected() {
    GatewayConfigException e =
        assertThrows(
            GatewayConfigException.class,
            () -> GatewayTlsConfig.parse("orders.example.com " + ordersCert));

    assertTrue(e.getMessage().contains("expected 3 fields"), e.getMessage());
  }

  @Test
  void a_duplicate_hostname_is_rejected() {
    GatewayConfigException e =
        assertThrows(
            GatewayConfigException.class,
            () ->
                GatewayTlsConfig.parse(
                    """
                    orders.example.com %s %s
                    ORDERS.example.com %s %s
                    """
                        .formatted(ordersCert, ordersKey, shopCert, shopKey)));

    assertTrue(e.getMessage().contains("duplicate TLS certificate"), e.getMessage());
  }

  @Test
  void a_wildcard_hostname_is_rejected_rather_than_silently_never_matching() {
    assertThrows(
        GatewayConfigException.class,
        () -> GatewayTlsConfig.parse("*.example.com %s %s".formatted(ordersCert, ordersKey)));
  }

  @Test
  void a_binding_pointing_at_a_missing_file_fails_at_parse_time() {
    // Fail while the config is being read, not on the first connection that happens to need that
    // certificate -- the same posture TlsSettings takes toward the cluster-wide material.
    assertThrows(
        GimleTlsException.class,
        () ->
            GatewayTlsConfig.parse(
                "orders.example.com %s %s".formatted(tempDir.resolve("nope-cert.pem"), ordersKey)));
  }
}
