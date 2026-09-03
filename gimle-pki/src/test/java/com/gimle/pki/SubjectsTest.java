package com.gimle.pki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gimle.core.authz.Principal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.Test;

class SubjectsTest {

  @Test
  void replaces_an_existing_organization_and_keeps_the_common_name() {
    X500Name result =
        Subjects.withOrganization(new X500Name("O=gimle:operators,CN=alice"), "gimle:nodes");
    assertEquals("O=gimle:nodes,CN=alice", result.toString());
  }

  @Test
  void adds_an_organization_to_a_subject_that_had_none() {
    X500Name result = Subjects.withOrganization(new X500Name("CN=node-1"), "gimle:nodes");
    assertEquals("O=gimle:nodes,CN=node-1", result.toString());
  }

  @Test
  void stamps_several_organizations_in_order_ahead_of_the_common_name() {
    X500Name result =
        Subjects.withOrganizations(
            new X500Name("O=gimle:operators,CN=node-1:orders#0"),
            List.of("gimle:workers", "gimle:tenant:acme"));
    assertEquals("O=gimle:workers,O=gimle:tenant:acme,CN=node-1:orders#0", result.toString());
  }

  @Test
  void rejects_a_subject_with_no_common_name() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Subjects.withOrganization(new X500Name("O=some-org"), "gimle:nodes"));
  }

  @Test
  void rejects_an_empty_organization_list() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Subjects.withOrganizations(new X500Name("CN=node-1"), List.of()));
  }

  /**
   * Round trip through a real signed certificate, not just an {@link X500Name}: what a receiving
   * fabric listener reads off a worker's verified peer certificate is exactly the groups the
   * control plane stamped at issuance, in order, with the CN untouched.
   */
  @Test
  void a_signed_certificate_reads_back_as_the_principal_its_stamped_subject_names()
      throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keyPair = generator.generateKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(
            keyPair, new X500Name("O=gimle:operators,CN=node-1:orders#0"));
    X509Certificate signed =
        ca.signCertificateRequest(
            csr,
            Subjects.withOrganizations(
                csr.getSubject(), List.of("gimle:workers", "gimle:tenant:acme")),
            Duration.ofDays(1));

    Principal principal = Subjects.principalFrom(signed);

    assertEquals("node-1:orders#0", principal.name());
    assertEquals(Set.of("gimle:workers", "gimle:tenant:acme"), principal.groups());
  }
}
