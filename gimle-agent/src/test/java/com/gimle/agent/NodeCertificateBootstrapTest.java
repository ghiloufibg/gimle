package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.CsrPurpose;
import com.gimle.core.protocol.CsrResult;
import com.gimle.core.protocol.CsrSubmission;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.Pem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.bouncycastle.asn1.x500.X500Name;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives a node's own certificate bootstrap against a real {@link CertificateAuthority} standing in
 * for the control plane's signing path -- which names the leaf ends up carrying, where the material
 * lands, and what an unwritable identity directory reports -- with no HTTP hop in the way.
 */
class NodeCertificateBootstrapTest {

  /**
   * The two SAN entry kinds {@code X509Certificate#getSubjectAlternativeNames} tags entries with.
   */
  private static final int DNS_NAME = 2;

  private static final int IP_ADDRESS = 7;

  @TempDir Path tempDir;

  private CertificateAuthority ca;
  private final List<CsrSubmission> submissions = new ArrayList<>();

  @BeforeEach
  void setUp() {
    ca = CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
  }

  @Test
  void a_bootstrapped_node_certificate_carries_the_nodes_dns_name_not_only_its_address()
      throws Exception {
    List<String> requested =
        AgentMain.nodeCertificateNames("node-a.cluster.internal", "node-a", "10.0.0.5");

    assertEquals(List.of("node-a", "node-a.cluster.internal", "localhost", "10.0.0.5"), requested);

    Path certFile = tempDir.resolve("identity/node-node-a.crt");
    Path keyFile = tempDir.resolve("identity/node-node-a.key");
    AgentMain.bootstrapCertificate(
        "node-a",
        certFile,
        keyFile,
        requested,
        Optional.of("token"),
        this::signLikeTheControlPlane);

    X509Certificate issued =
        Pem.decodeCertificate(Files.readString(certFile, StandardCharsets.US_ASCII));
    assertEquals(
        List.of("node-a", "node-a.cluster.internal", "localhost"),
        alternativeNames(issued, DNS_NAME),
        "a node is reached by name, so its own leaf has to carry that name");
    assertEquals(List.of("10.0.0.5"), alternativeNames(issued, IP_ADDRESS));
    assertEquals(CsrPurpose.NODE_CLIENT, submissions.get(0).purpose());
    assertEquals(Optional.of("token"), submissions.get(0).bootstrapToken());
  }

  @Test
  void a_wildcard_gossip_bind_address_is_not_requested_as_a_name() {
    assertEquals(
        List.of("node-a", "localhost", "10.0.0.5"),
        AgentMain.nodeCertificateNames("0.0.0.0", "node-a", "10.0.0.5"));
  }

  @Test
  void a_nodes_identity_lives_under_its_own_data_root_by_default() {
    Path dataRoot = tempDir.resolve("gimle-data");

    assertEquals(dataRoot.resolve("tls"), AgentMain.nodeIdentityDirectory(dataRoot));
    assertEquals(
        dataRoot.resolve("tls").resolve("node-node-a.crt"),
        AgentMain.nodeCertificateFile(AgentMain.nodeIdentityDirectory(dataRoot), "node-a"));
  }

  @Test
  void an_identity_directory_may_be_pointed_somewhere_else_entirely() {
    Path elsewhere = tempDir.resolve("var/lib/gimle/tls");
    String previous = System.getProperty("gimle.agent.identityDir");
    try {
      System.setProperty("gimle.agent.identityDir", elsewhere.toString());

      assertEquals(elsewhere, AgentMain.nodeIdentityDirectory(tempDir.resolve("gimle-data")));
    } finally {
      restoreProperty("gimle.agent.identityDir", previous);
    }
  }

  @Test
  void bootstrapping_repoints_the_tls_material_properties_at_what_it_actually_wrote()
      throws Exception {
    Path certFile = AgentMain.nodeCertificateFile(tempDir.resolve("tls"), "node-a");
    Path keyFile = AgentMain.nodeKeyFile(tempDir.resolve("tls"), "node-a");
    String previousCert = System.getProperty("gimle.tls.certFile");
    String previousKey = System.getProperty("gimle.tls.keyFile");
    try {
      // Where a launcher pointed this agent: the shared CA material directory, which holds no
      // node identity and is never written to here.
      System.setProperty(
          "gimle.tls.certFile", tempDir.resolve("ca-material/node-a.crt").toString());
      System.setProperty("gimle.tls.keyFile", tempDir.resolve("ca-material/node-a.key").toString());

      AgentMain.bootstrapCertificate(
          "node-a",
          certFile,
          keyFile,
          List.of("node-a"),
          Optional.of("token"),
          this::signLikeTheControlPlane);

      assertTrue(Files.isRegularFile(certFile));
      assertTrue(Files.isRegularFile(keyFile));
      assertFalse(Files.exists(tempDir.resolve("ca-material")));
      assertEquals(certFile.toString(), System.getProperty("gimle.tls.certFile"));
      assertEquals(keyFile.toString(), System.getProperty("gimle.tls.keyFile"));
    } finally {
      restoreProperty("gimle.tls.certFile", previousCert);
      restoreProperty("gimle.tls.keyFile", previousKey);
    }
  }

  @Test
  void an_unwritable_identity_directory_says_what_it_needed_to_write_and_where() throws Exception {
    // A path that cannot become a directory, so the write fails the same way it does under a
    // read-only mount -- and unlike a permission bit, deterministically for any uid.
    Path blocked = Files.createFile(tempDir.resolve("blocked"));

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                AgentMain.bootstrapCertificate(
                    "node-a",
                    blocked.resolve("node-node-a.crt"),
                    blocked.resolve("node-node-a.key"),
                    List.of("node-a"),
                    Optional.of("token"),
                    this::signLikeTheControlPlane));

    assertTrue(
        failure.getMessage().contains(blocked.toString()),
        "the failure must name the directory it needed; message=" + failure.getMessage());
    assertTrue(
        failure.getMessage().contains("gimle.agent.identityDir"),
        "the failure must name the property that moves it; message=" + failure.getMessage());
    assertTrue(
        failure.getMessage().contains("gimle.data.root"),
        "the failure must name the root it defaults under; message=" + failure.getMessage());
  }

  private CsrResult signLikeTheControlPlane(CsrSubmission submission) {
    submissions.add(submission);
    X509Certificate signed =
        ca.signCertificateRequest(Pem.decodeCsr(submission.csrPem()), Duration.ofDays(1));
    return CsrResult.approved(
        Pem.encodeCertificate(signed), Pem.encodeCertificate(ca.certificate()));
  }

  private static List<String> alternativeNames(X509Certificate certificate, int kind)
      throws CertificateParsingException {
    Collection<List<?>> entries = certificate.getSubjectAlternativeNames();
    List<String> names = new ArrayList<>();
    for (List<?> entry : entries) {
      if (((Integer) entry.get(0)) == kind) {
        names.add((String) entry.get(1));
      }
    }
    return names;
  }

  private static void restoreProperty(String key, String previous) {
    if (previous == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, previous);
    }
  }
}
