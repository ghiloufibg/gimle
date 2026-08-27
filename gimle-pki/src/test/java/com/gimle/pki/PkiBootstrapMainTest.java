package com.gimle.pki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.authz.BuiltinRoles;
import com.gimle.core.authz.Principal;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link PkiBootstrapMain}'s multi-hostname leaf minting: one {@code <role>-<hostname>}
 * cert/key pair per (role, hostname) pair, distinct from every other hostname's own leaf.
 */
class PkiBootstrapMainTest {

  @TempDir Path outputDir;

  @Test
  void mints_one_leaf_per_role_per_hostname() throws IOException {
    PkiBootstrapMain.main(new String[] {outputDir.toString(), "test-ca", "h1", "h2"});

    for (String role : List.of("controlplane", "fafnir", "muninn", "andvari")) {
      for (String hostname : List.of("h1", "h2")) {
        assertTrue(
            Files.exists(outputDir.resolve(role + "-" + hostname + ".crt")),
            role + "-" + hostname + ".crt should exist");
        assertTrue(
            Files.exists(outputDir.resolve(role + "-" + hostname + ".key")),
            role + "-" + hostname + ".key should exist");
      }
    }
    assertTrue(Files.exists(outputDir.resolve("ca.crt")));
    assertTrue(Files.exists(outputDir.resolve("ca.key")));
    assertTrue(Files.exists(outputDir.resolve("operator.crt")));
    assertTrue(Files.exists(outputDir.resolve("operator.key")));
    assertTrue(Files.exists(outputDir.resolve("bootstrap-account.yaml")));
  }

  @Test
  void a_hostnames_own_leaf_is_sand_to_that_hostname_and_localhost_only() throws Exception {
    PkiBootstrapMain.main(new String[] {outputDir.toString(), "test-ca", "h1", "h2"});

    X509Certificate h1ControlPlane = readCertificate(outputDir.resolve("controlplane-h1.crt"));
    Collection<List<?>> sans = h1ControlPlane.getSubjectAlternativeNames();
    List<String> dnsNames =
        sans.stream().map(entry -> (String) entry.get(1)).collect(Collectors.toList());

    assertTrue(dnsNames.contains("h1"));
    assertTrue(dnsNames.contains("localhost"));
    assertFalse(dnsNames.contains("h2"), "h1's own leaf must not carry h2 in its SAN list");
  }

  /**
   * ADD-10: without an {@code O=} of its own, the control plane's own scheduling-time artifact pull
   * had no group any {@code Authorizer} grant could ever match, so a fresh mTLS cluster's own
   * control plane could never pull an artifact it didn't already have cached.
   */
  @Test
  void the_control_plane_leaf_carries_the_controlplane_group_but_other_roles_do_not()
      throws Exception {
    PkiBootstrapMain.main(new String[] {outputDir.toString(), "test-ca", "h1"});

    X509Certificate controlPlane = readCertificate(outputDir.resolve("controlplane-h1.crt"));
    Principal principal = Subjects.principalFrom(controlPlane);
    assertEquals("h1", principal.name());
    assertEquals(Set.of(BuiltinRoles.GROUP_CONTROLPLANE), principal.groups());

    X509Certificate fafnir = readCertificate(outputDir.resolve("fafnir-h1.crt"));
    assertEquals(Set.of(), Subjects.principalFrom(fafnir).groups());
  }

  @Test
  void every_hostnames_leaves_for_one_role_are_signed_by_the_same_shared_ca() throws Exception {
    PkiBootstrapMain.main(new String[] {outputDir.toString(), "test-ca", "h1", "h2"});

    X509Certificate ca = readCertificate(outputDir.resolve("ca.crt"));
    X509Certificate h1 = readCertificate(outputDir.resolve("fafnir-h1.crt"));
    X509Certificate h2 = readCertificate(outputDir.resolve("fafnir-h2.crt"));

    h1.verify(ca.getPublicKey());
    h2.verify(ca.getPublicKey());
    assertEquals(ca.getSubjectX500Principal(), h1.getIssuerX500Principal());
  }

  private static X509Certificate readCertificate(Path file) throws Exception {
    try (FileInputStream in = new FileInputStream(file.toFile())) {
      return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
    }
  }
}
