package com.gimle.pki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.tls.TlsSettings;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Covers what a rotation check reports rather than what it transmits: the CSR round trip itself
 * needs a live signing endpoint and is exercised against a real cluster elsewhere.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class OwnCertificateRotatorTest {

  private static final String PROTOCOL_PROPERTY = "gimle.transport.protocol";

  @AfterEach
  void clearTransportProtocol() {
    System.clearProperty(PROTOCOL_PROPERTY);
  }

  @Test
  void a_plaintext_cluster_reports_a_disabled_check_and_never_touches_the_filesystem(
      @TempDir Path dir) throws Exception {
    System.clearProperty(PROTOCOL_PROPERTY);
    List<CertificateRotationStatus> seen = new ArrayList<>();
    OwnCertificateRotator rotator = new OwnCertificateRotator(monitor(seen::add));

    CertificateRotationStatus status =
        rotator.checkAndRotateIfDue(settingsFor(dir, signLeaf(Duration.ofDays(30))), null);

    assertEquals(CertificateRotationOutcome.DISABLED, status.outcome());
    assertFalse(status.rotated());
    assertEquals(1, seen.size());
  }

  @Test
  void a_freshly_issued_certificate_reports_not_due_with_its_own_expiry(@TempDir Path dir)
      throws Exception {
    System.setProperty(PROTOCOL_PROPERTY, "tls");
    X509Certificate leaf = signLeaf(Duration.ofDays(30));
    OwnCertificateRotator rotator = new OwnCertificateRotator(monitor(status -> {}));

    CertificateRotationStatus status =
        rotator.checkAndRotateIfDue(
            settingsFor(dir, leaf), URI.create("https://127.0.0.1:1/bootstrap"));

    assertEquals(CertificateRotationOutcome.NOT_DUE, status.outcome());
    assertEquals(leaf.getNotAfter().toInstant(), status.currentNotAfter().orElseThrow());
  }

  @Test
  void an_unreadable_certificate_reports_a_failure_and_keeps_counting_them(@TempDir Path dir)
      throws Exception {
    System.setProperty(PROTOCOL_PROPERTY, "tls");
    List<CertificateRotationStatus> seen = new ArrayList<>();
    OwnCertificateRotator rotator = new OwnCertificateRotator(monitor(seen::add));
    TlsSettings settings = settingsFor(dir, signLeaf(Duration.ofDays(30)));
    Files.writeString(settings.certFile(), "not a certificate", StandardCharsets.US_ASCII);

    CertificateRotationStatus first = rotator.checkAndRotateIfDue(settings, null);
    CertificateRotationStatus second = rotator.checkAndRotateIfDue(settings, null);

    assertTrue(first.failed());
    assertEquals(1, first.consecutiveFailures());
    assertEquals(2, second.consecutiveFailures());
    assertTrue(second.failureMessage().isPresent());
    assertEquals(2, seen.size());
  }

  @Test
  void a_due_certificate_with_no_csr_endpoint_configured_is_a_failure_not_a_quiet_no_op(
      @TempDir Path dir) throws Exception {
    System.setProperty(PROTOCOL_PROPERTY, "tls");
    // A zero-validity certificate is inside its renewal window the instant it exists, with no
    // wall-clock wait to make the check deterministic.
    X509Certificate expiring = signLeaf(Duration.ZERO);
    OwnCertificateRotator rotator = new OwnCertificateRotator(monitor(status -> {}));

    CertificateRotationStatus status =
        rotator.checkAndRotateIfDue(settingsFor(dir, expiring), null);

    assertTrue(status.failed());
    assertEquals(1, status.consecutiveFailures());
    assertTrue(status.failureMessage().orElseThrow().contains("no CSR endpoint"));
    assertEquals(expiring.getNotAfter().toInstant(), status.currentNotAfter().orElseThrow());
  }

  private static CertificateRotationMonitor monitor(CertificateRotationListener listener) {
    return new CertificateRotationMonitor("test", Duration.ofSeconds(2), listener);
  }

  private static TlsSettings settingsFor(Path dir, X509Certificate leaf) throws IOException {
    Path certFile = dir.resolve("leaf.crt");
    Path keyFile = dir.resolve("leaf.key");
    Path caFile = dir.resolve("ca.crt");
    Files.writeString(certFile, Pem.encodeCertificate(leaf), StandardCharsets.US_ASCII);
    Files.writeString(keyFile, "unused by these checks", StandardCharsets.US_ASCII);
    Files.writeString(caFile, "unused by these checks", StandardCharsets.US_ASCII);
    return new TlsSettings(certFile, keyFile, caFile);
  }

  private static X509Certificate signLeaf(Duration validity) throws NoSuchAlgorithmException {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=ca"), Duration.ofDays(3650));
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair leafKeyPair = generator.generateKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(leafKeyPair, new X500Name("CN=test-leaf"));
    return ca.signCertificateRequest(csr, validity);
  }
}
