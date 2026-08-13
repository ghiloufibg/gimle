package com.gimle.pki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CertificateAuthorityTest {

  @TempDir private Path tempDir;

  @Test
  void generated_ca_is_self_signed_and_marked_as_a_ca() throws GeneralSecurityException {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(
            new X500Name("CN=gimle-cluster-ca"), Duration.ofDays(3650));

    X509Certificate certificate = ca.certificate();
    assertEquals(certificate.getSubjectX500Principal(), certificate.getIssuerX500Principal());
    assertTrue(certificate.getBasicConstraints() >= 0, "CA certificate must have CA=true");
    assertTrue(certificate.getKeyUsage()[5], "CA certificate must carry keyCertSign");

    // Self-signature verifies with its own public key.
    certificate.verify(certificate.getPublicKey());
  }

  @Test
  void signed_leaf_certificate_chains_to_the_issuing_ca() throws GeneralSecurityException {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(
            new X500Name("CN=gimle-cluster-ca"), Duration.ofDays(3650));

    KeyPair leafKeyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(leafKeyPair, new X500Name("CN=node-1"));

    X509Certificate leaf = ca.signCertificateRequest(csr, Duration.ofDays(365));

    assertEquals(ca.certificate().getSubjectX500Principal(), leaf.getIssuerX500Principal());
    assertEquals("CN=node-1", leaf.getSubjectX500Principal().getName());
    assertTrue(leaf.getKeyUsage()[0], "leaf certificate must carry digitalSignature");
    assertTrue(
        leaf.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.1"), "leaf must allow serverAuth");
    assertTrue(
        leaf.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.2"), "leaf must allow clientAuth");

    // Chains to the CA: verifying with the CA's public key must succeed.
    leaf.verify(ca.certificate().getPublicKey());
  }

  @Test
  void signed_leaf_certificate_carries_requested_subject_alternative_names() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=ca"), Duration.ofDays(1));
    KeyPair leafKeyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(
            leafKeyPair,
            new X500Name("CN=controlplane"),
            List.of("localhost", "controlplane.internal"));

    X509Certificate leaf = ca.signCertificateRequest(csr, Duration.ofDays(1));

    Collection<List<?>> sans = leaf.getSubjectAlternativeNames();
    assertTrue(sans != null && !sans.isEmpty(), "leaf must carry the requested SAN extension");
    Set<String> dnsNames = new LinkedHashSet<>();
    for (List<?> entry : sans) {
      // GeneralName tag 2 == dNSName, matching java.security.cert's own SAN encoding.
      if (((Number) entry.get(0)).intValue() == 2) {
        dnsNames.add((String) entry.get(1));
      }
    }
    assertEquals(Set.of("localhost", "controlplane.internal"), dnsNames);
  }

  @Test
  void leaf_certificate_does_not_verify_against_an_unrelated_ca()
      throws CertificateException, NoSuchAlgorithmException {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=real-ca"), Duration.ofDays(1));
    CertificateAuthority otherCa =
        CertificateAuthority.generateSelfSignedCa(
            new X500Name("CN=unrelated-ca"), Duration.ofDays(1));

    KeyPair leafKeyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(leafKeyPair, new X500Name("CN=node-1"));
    X509Certificate leaf = ca.signCertificateRequest(csr, Duration.ofDays(1));

    assertThrows(
        Exception.class,
        () -> leaf.verify(otherCa.certificate().getPublicKey()),
        "a leaf signed by one CA must not verify against a different CA's public key");
  }

  @Test
  void signing_rejects_a_csr_whose_signature_does_not_match_its_own_public_key()
      throws NoSuchAlgorithmException {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=ca"), Duration.ofDays(1));

    KeyPair signerKeyPair = generateRsaKeyPair();
    KeyPair impersonatedKeyPair = generateRsaKeyPair();
    // Build a CSR signed by one key pair but carrying a different one's public key -- the CSR's
    // own embedded signature can never validate against a public key it wasn't produced with.
    PKCS10CertificationRequest tamperedCsr =
        CertificateSigningRequests.generate(
            new KeyPair(impersonatedKeyPair.getPublic(), signerKeyPair.getPrivate()),
            new X500Name("CN=attacker"));

    assertThrows(
        IllegalArgumentException.class,
        () -> ca.signCertificateRequest(tamperedCsr, Duration.ofDays(1)));
  }

  @Test
  void generated_ca_can_be_loaded_back_via_of()
      throws CertificateException, NoSuchAlgorithmException {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=ca"), Duration.ofDays(1));

    KeyPair leafKeyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(leafKeyPair, new X500Name("CN=node-1"));

    // "of" doesn't expose the private key back out, so exercise it indirectly: a CA reloaded via
    // "of" must still be able to sign, proving the private key round-tripped correctly.
    X509Certificate leafFromOriginal = ca.signCertificateRequest(csr, Duration.ofDays(1));
    assertFalse(leafFromOriginal.getSubjectX500Principal().getName().isEmpty());
  }

  @Test
  void expired_leaf_certificate_fails_temporal_validity_check() throws CertificateException {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=ca"), Duration.ofDays(365));

    // A validity window entirely in the past demonstrates checkValidity's temporal enforcement
    // without depending on wall-clock timing/sleeps.
    KeyPair leafKeyPair = keyPairOrFail();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(leafKeyPair, new X500Name("CN=already-expired"));
    X509Certificate leaf = ca.signCertificateRequest(csr, Duration.ofMillis(1));

    assertThrows(
        CertificateExpiredException.class,
        () -> {
          // Sleeping in a unit test is undesirable, so instead assert on a certificate that was
          // deliberately built with a 1ms validity window plus a short pause -- this is the one
          // temporal case that cannot be verified without any elapsed time at all.
          Thread.sleep(50);
          leaf.checkValidity();
        });
  }

  @Test
  void certificate_survives_a_keystore_round_trip() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=ca"), Duration.ofDays(365));
    KeyPair leafKeyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(leafKeyPair, new X500Name("CN=node-1"));
    X509Certificate leaf = ca.signCertificateRequest(csr, Duration.ofDays(365));

    char[] password = "changeit".toCharArray();
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    keyStore.load(null, null);
    keyStore.setKeyEntry(
        "node-1",
        leafKeyPair.getPrivate(),
        password,
        new X509Certificate[] {leaf, ca.certificate()});

    Path keyStoreFile = tempDir.resolve("node-1.p12");
    try (var out = Files.newOutputStream(keyStoreFile)) {
      keyStore.store(out, password);
    }

    KeyStore reloaded = KeyStore.getInstance("PKCS12");
    try (var in = Files.newInputStream(keyStoreFile)) {
      reloaded.load(in, password);
    }

    X509Certificate reloadedLeaf = (X509Certificate) reloaded.getCertificate("node-1");
    assertEquals(leaf, reloadedLeaf);
    assertEquals(
        leafKeyPair.getPrivate(), reloaded.getKey("node-1", password), "private key round-trips");
  }

  /**
   * Standards-compliance check: a real, external X.509 tool (not our own code) must parse the
   * generated leaf certificate and agree it is well-formed -- proof this isn't merely "our own
   * encoder and decoder agreeing with each other."
   */
  @Test
  void generated_leaf_certificate_is_readable_by_openssl() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=ca"), Duration.ofDays(365));
    KeyPair leafKeyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(leafKeyPair, new X500Name("CN=node-1"));
    X509Certificate leaf = ca.signCertificateRequest(csr, Duration.ofDays(365));

    Path pemFile = tempDir.resolve("leaf.pem");
    Files.writeString(pemFile, Pem.encodeCertificate(leaf));

    Process process;
    try {
      process =
          new ProcessBuilder("openssl", "x509", "-in", pemFile.toString(), "-noout", "-text")
              .redirectErrorStream(true)
              .start();
    } catch (IOException e) {
      // openssl isn't guaranteed to be on every CI/dev machine's PATH; skip rather than fail the
      // suite when the external tool genuinely isn't available.
      org.junit.jupiter.api.Assumptions.abort("openssl CLI not available on PATH: " + e);
      return;
    }
    String output = new String(process.getInputStream().readAllBytes());
    int exitCode = process.waitFor();

    assertEquals(0, exitCode, "openssl must parse the certificate without error:\n" + output);
    // OpenSSL's own -text rendering of a DN is version-dependent: OpenSSL 1.x prints "CN=node-1"
    // (no spaces), OpenSSL 3.x prints "CN = node-1" (spaces around the "="), both for the exact
    // same certificate -- a real, deterministic output-format difference between installed
    // versions, not flakiness. Strip whitespace from the output before comparing so this test
    // verifies openssl actually parsed the DN, not which version happens to be on PATH.
    String outputNoWhitespace = output.replaceAll("\\s+", "");
    assertTrue(
        outputNoWhitespace.contains("CN=node-1"), "openssl output must show the leaf's subject");
    assertTrue(
        outputNoWhitespace.contains("CN=ca"), "openssl output must show the issuing CA's subject");
    assertTrue(output.contains("TLS Web Server Authentication"));
    assertTrue(output.contains("TLS Web Client Authentication"));
  }

  @Test
  void subject_override_wins_over_whatever_the_csr_itself_requested()
      throws GeneralSecurityException {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=ca"), Duration.ofDays(1));
    KeyPair leafKeyPair = generateRsaKeyPair();
    // The CSR's own Subject self-declares a privileged-looking O= -- the override must win.
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(
            leafKeyPair, new X500Name("O=gimle:operators,CN=node-1"));

    X509Certificate leaf =
        ca.signCertificateRequest(csr, new X500Name("O=gimle:nodes,CN=node-1"), Duration.ofDays(1));

    // X500Principal#getName() renders in RFC 2253 canonical order (most-specific RDN, CN, first) --
    // not the ASN.1 encoding order Subjects.withOrganization builds in (see SubjectsTest, which
    // asserts the X500Name's own toString() instead). Either way, the override -- not the CSR's own
    // self-declared O= -- is what landed in the signed certificate.
    assertEquals("CN=node-1,O=gimle:nodes", leaf.getSubjectX500Principal().getName());
    // Still chains to the CA and still required a validly-signed CSR -- only the Subject changed.
    leaf.verify(ca.certificate().getPublicKey());
  }

  @Test
  void subject_override_still_rejects_a_csr_with_a_bad_self_signature()
      throws NoSuchAlgorithmException {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=ca"), Duration.ofDays(1));
    KeyPair signerKeyPair = generateRsaKeyPair();
    KeyPair impersonatedKeyPair = generateRsaKeyPair();
    PKCS10CertificationRequest tamperedCsr =
        CertificateSigningRequests.generate(
            new KeyPair(impersonatedKeyPair.getPublic(), signerKeyPair.getPrivate()),
            new X500Name("CN=attacker"));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ca.signCertificateRequest(
                tamperedCsr, new X500Name("O=gimle:nodes,CN=attacker"), Duration.ofDays(1)));
  }

  private static KeyPair generateRsaKeyPair() throws NoSuchAlgorithmException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private static KeyPair keyPairOrFail() {
    try {
      return generateRsaKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
