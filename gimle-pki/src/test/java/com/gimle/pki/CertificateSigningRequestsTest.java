package com.gimle.pki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
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

  private static KeyPair generateRsaKeyPair() throws NoSuchAlgorithmException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }
}
