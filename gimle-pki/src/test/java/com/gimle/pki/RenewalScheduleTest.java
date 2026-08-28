package com.gimle.pki;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.Test;

class RenewalScheduleTest {

  @Test
  void a_freshly_issued_certificate_is_not_yet_due_for_renewal() throws NoSuchAlgorithmException {
    X509Certificate leaf = signLeaf(Duration.ofDays(30));

    // The renewal window starts at 70% of validity -- the moment of issuance is nowhere near it.
    assertFalse(RenewalSchedule.of(leaf).isDue(leaf.getNotBefore().toInstant()));
  }

  @Test
  void a_certificate_is_due_once_past_the_final_20_percent_of_its_validity()
      throws NoSuchAlgorithmException {
    X509Certificate leaf = signLeaf(Duration.ofDays(30));
    Instant notBefore = leaf.getNotBefore().toInstant();
    Instant notAfter = leaf.getNotAfter().toInstant();
    Duration validity = Duration.between(notBefore, notAfter);

    // The randomized renewal point always falls within [70%, 80%) of validity, so 90% is due
    // regardless of the random draw -- deterministic without depending on which point was picked.
    Instant ninetyPercent = notBefore.plusMillis((long) (validity.toMillis() * 0.9));

    assertTrue(RenewalSchedule.of(leaf).isDue(ninetyPercent));
  }

  @Test
  void the_computed_renewal_point_always_falls_strictly_within_the_certificates_own_validity()
      throws NoSuchAlgorithmException {
    X509Certificate leaf = signLeaf(Duration.ofDays(30));
    Instant renewAt = RenewalSchedule.of(leaf).renewAt();

    assertTrue(renewAt.isAfter(leaf.getNotBefore().toInstant()));
    assertTrue(renewAt.isBefore(leaf.getNotAfter().toInstant()));
  }

  @Test
  void is_due_treats_the_certificates_own_expiry_as_always_due() throws NoSuchAlgorithmException {
    X509Certificate leaf = signLeaf(Duration.ofDays(30));

    // renewAt is always strictly before notAfter (fraction < 1), so notAfter itself is always due.
    assertTrue(RenewalSchedule.of(leaf).isDue(leaf.getNotAfter().toInstant()));
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
