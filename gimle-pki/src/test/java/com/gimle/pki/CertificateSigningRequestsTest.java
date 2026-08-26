package com.gimle.pki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.operator.ContentVerifierProvider;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.junit.jupiter.api.Test;

class CertificateSigningRequestsTest {

  @Test
  void generated_csr_carries_the_requested_subject_and_public_key()
      throws NoSuchAlgorithmException {
    KeyPair keyPair = generateRsaKeyPair();
    X500Name subject = new X500Name("CN=node-1,O=gimle");

    PKCS10CertificationRequest csr = CertificateSigningRequests.generate(keyPair, subject);

    assertEquals(subject, csr.getSubject());
  }

  @Test
  void generated_csr_is_self_verifiable_with_its_own_public_key() throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(keyPair, new X500Name("CN=node-1"));

    JcaPKCS10CertificationRequest jcaCsr = new JcaPKCS10CertificationRequest(csr);
    ContentVerifierProvider verifierProvider =
        new JcaContentVerifierProviderBuilder().build(jcaCsr.getPublicKey());

    assertTrue(
        csr.isSignatureValid(verifierProvider),
        "a CSR must verify against the public key it carries");
  }

  /**
   * The signed leaf is where SAN typing actually matters (hostname verifiers read it there), so
   * this asserts through a real CA signing round trip: an IP literal must come back tagged {@code
   * iPAddress} (tag 7) -- a {@code dNSName} carrying "127.0.0.1" never matches an IP-dialed
   * connection -- while an ordinary hostname stays {@code dNSName} (tag 2).
   */
  @Test
  void an_ip_literal_san_is_typed_ip_address_and_a_hostname_stays_dns_name() throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(
            keyPair, new X500Name("CN=node-1"), List.of("localhost", "127.0.0.1", "::1"));
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));

    X509Certificate leaf = ca.signCertificateRequest(csr, Duration.ofDays(1));

    Map<String, Integer> tagsByName = new HashMap<>();
    for (List<?> entry : leaf.getSubjectAlternativeNames()) {
      tagsByName.put((String) entry.get(1), (Integer) entry.get(0));
    }
    // java.security.cert SAN encoding: 2 == dNSName, 7 == iPAddress.
    assertEquals(2, tagsByName.get("localhost"));
    assertEquals(7, tagsByName.get("127.0.0.1"));
    assertEquals(7, tagsByName.get("0:0:0:0:0:0:0:1"));
  }

  private static KeyPair generateRsaKeyPair() throws NoSuchAlgorithmException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }
}
