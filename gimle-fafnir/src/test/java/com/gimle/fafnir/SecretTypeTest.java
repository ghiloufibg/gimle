package com.gimle.fafnir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleSecretsException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SecretTypeTest {

  private static final String CERTIFICATE =
      "-----BEGIN CERTIFICATE-----\nMIIBkTCB+wIJAKl0aG\nVyZQ==\n-----END CERTIFICATE-----\n";

  @Test
  void an_absent_or_blank_type_means_opaque() {
    assertEquals(SecretType.OPAQUE, SecretType.fromWire(null));
    assertEquals(SecretType.OPAQUE, SecretType.fromWire("  "));
  }

  @Test
  void both_the_hyphenated_and_underscored_spellings_name_the_same_type() {
    assertEquals(SecretType.PEM_CERTIFICATE, SecretType.fromWire("pem-certificate"));
    assertEquals(SecretType.PEM_CERTIFICATE, SecretType.fromWire("PEM_CERTIFICATE"));
    assertEquals(SecretType.PEM_PRIVATE_KEY, SecretType.fromWire("Pem-Private-Key"));
  }

  @Test
  void an_unknown_type_is_rejected_with_the_valid_names_in_the_message() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> SecretType.fromWire("kubernetes.io/tls"));

    assertTrue(thrown.getMessage().contains("pem-certificate"));
  }

  @Test
  void opaque_accepts_anything_including_an_empty_value() {
    SecretType.OPAQUE.validate("acme", "k", new byte[0]);
    SecretType.OPAQUE.validate("acme", "k", bytes("not a pem at all"));
  }

  @Test
  void a_well_formed_certificate_passes() {
    SecretType.PEM_CERTIFICATE.validate("acme", "tls-cert", bytes(CERTIFICATE));
  }

  @Test
  void a_certificate_with_no_begin_marker_is_rejected() {
    GimleSecretsException thrown =
        assertThrows(
            GimleSecretsException.class,
            () -> SecretType.PEM_CERTIFICATE.validate("acme", "tls-cert", bytes("MIIBkTCB")));

    assertTrue(thrown.getMessage().contains("acme/tls-cert"));
    assertTrue(thrown.getMessage().contains("-----BEGIN CERTIFICATE-----"));
  }

  @Test
  void a_certificate_truncated_before_its_end_marker_is_rejected() {
    assertThrows(
        GimleSecretsException.class,
        () ->
            SecretType.PEM_CERTIFICATE.validate(
                "acme", "tls-cert", bytes("-----BEGIN CERTIFICATE-----\nMIIBkTCB")));
  }

  @Test
  void a_certificate_with_an_empty_body_is_rejected() {
    assertThrows(
        GimleSecretsException.class,
        () ->
            SecretType.PEM_CERTIFICATE.validate(
                "acme",
                "tls-cert",
                bytes("-----BEGIN CERTIFICATE-----\n\n-----END CERTIFICATE-----")));
  }

  @Test
  void a_certificate_whose_body_is_not_base64_is_rejected() {
    assertThrows(
        GimleSecretsException.class,
        () ->
            SecretType.PEM_CERTIFICATE.validate(
                "acme",
                "tls-cert",
                bytes("-----BEGIN CERTIFICATE-----\nnot base64!\n-----END CERTIFICATE-----")));
  }

  @Test
  void a_private_key_is_accepted_under_every_label_openssl_emits() {
    for (String label : new String[] {"PRIVATE KEY", "RSA PRIVATE KEY", "EC PRIVATE KEY"}) {
      SecretType.PEM_PRIVATE_KEY.validate(
          "acme",
          "tls-key",
          bytes("-----BEGIN " + label + "-----\nMIIBOgIB\n-----END " + label + "-----\n"));
    }
  }

  @Test
  void a_private_key_whose_end_label_does_not_match_its_begin_label_is_rejected() {
    assertThrows(
        GimleSecretsException.class,
        () ->
            SecretType.PEM_PRIVATE_KEY.validate(
                "acme",
                "tls-key",
                bytes("-----BEGIN RSA PRIVATE KEY-----\nMIIB\n-----END EC PRIVATE KEY-----")));
  }

  @Test
  void a_certificate_is_not_accepted_where_a_private_key_was_declared() {
    assertThrows(
        GimleSecretsException.class,
        () -> SecretType.PEM_PRIVATE_KEY.validate("acme", "tls-key", bytes(CERTIFICATE)));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
