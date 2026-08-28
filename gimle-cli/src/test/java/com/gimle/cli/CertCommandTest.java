package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.CertificateSigningRequests;
import com.gimle.pki.Pem;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * {@link CertCommand#warnIfRenewalDue} against real, short-lived certificates minted directly
 * through {@link CertificateAuthority} -- validity is a plain constructor argument there, so a
 * certificate already inside (or nowhere near) its own renewal window is built without needing any
 * CLI/API-exposed way to request a short-lived certificate from a live cluster.
 */
// System.setProperty mutates a JVM-global; excludes this class from running concurrently with any
// other class holding the same lock, under class-level parallel execution (root pom.xml) -- see
// TransportProtocolTest for the same pattern against the same underlying property.
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class CertCommandTest {

  private static final String TRANSPORT_PROPERTY = "gimle.transport.protocol";
  private static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";

  @TempDir private Path tempDir;

  @AfterEach
  void clearProperties() {
    System.clearProperty(TRANSPORT_PROPERTY);
    System.clearProperty(CERT_FILE_PROPERTY);
  }

  @Test
  void prints_nothing_under_plaintext_transport_even_for_a_certificate_already_due()
      throws NoSuchAlgorithmException {
    // gimle.transport.protocol deliberately left unset -- TransportProtocol.fromConfig() defaults
    // to PLAINTEXT, and warnIfRenewalDue must never check a certificate at all in that mode.
    Path certFile = writeLeafCertificate(Duration.ofMillis(1));
    System.setProperty(CERT_FILE_PROPERTY, certFile.toString());
    sleepPastRenewalWindow();
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    CertCommand.warnIfRenewalDue(new PrintStream(buffer));

    assertEquals("", buffer.toString(StandardCharsets.US_ASCII));
  }

  @Test
  void prints_nothing_for_a_freshly_issued_certificate_under_tls() throws NoSuchAlgorithmException {
    Path certFile = writeLeafCertificate(Duration.ofDays(365));
    System.setProperty(TRANSPORT_PROPERTY, "tls");
    System.setProperty(CERT_FILE_PROPERTY, certFile.toString());
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    CertCommand.warnIfRenewalDue(new PrintStream(buffer));

    assertEquals("", buffer.toString(StandardCharsets.US_ASCII));
  }

  @Test
  void warns_once_a_short_lived_certificate_enters_its_renewal_window()
      throws NoSuchAlgorithmException {
    Path certFile = writeLeafCertificate(Duration.ofMillis(1));
    System.setProperty(TRANSPORT_PROPERTY, "tls");
    System.setProperty(CERT_FILE_PROPERTY, certFile.toString());
    sleepPastRenewalWindow();
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    CertCommand.warnIfRenewalDue(new PrintStream(buffer));

    assertTrue(
        buffer.toString(StandardCharsets.US_ASCII).contains("due for renewal"),
        "expected a renewal warning, got: " + buffer);
  }

  @Test
  void a_missing_cert_file_property_is_silently_ignored() {
    // No gimle.tls.certFile set at all -- the overwhelmingly common case (TLS configured for
    // process transport but the CLI's own identity file simply isn't present yet).
    System.setProperty(TRANSPORT_PROPERTY, "tls");
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    CertCommand.warnIfRenewalDue(new PrintStream(buffer));

    assertEquals("", buffer.toString(StandardCharsets.US_ASCII));
  }

  /**
   * A 1ms-validity certificate's renewal window opens within its own first millisecond -- sleeping
   * briefly afterward deterministically lands past it, the same "1ms validity + a short pause"
   * pattern {@code CertificateAuthorityTest} already uses to exercise temporal checks without
   * depending on wall-clock timing beyond a short, generous margin.
   */
  private static void sleepPastRenewalWindow() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private Path writeLeafCertificate(Duration validity) throws NoSuchAlgorithmException {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(
            new X500Name("CN=test-ca"), Duration.ofDays(3650));
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair leafKeyPair = generator.generateKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(leafKeyPair, new X500Name("CN=test-leaf"));
    X509Certificate leaf = ca.signCertificateRequest(csr, validity);

    Path certFile = tempDir.resolve("leaf-" + System.nanoTime() + ".pem");
    try {
      Files.writeString(certFile, Pem.encodeCertificate(leaf), StandardCharsets.US_ASCII);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return certFile;
  }
}
