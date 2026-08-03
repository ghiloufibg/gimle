package com.gimle.fabric.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.gimle.fabric.testsupport.Await;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.CertificateSigningRequests;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves {@code gimle.transport.protocol=tls} actually swaps gossip's UDP transport onto real DTLS
 * -- a genuine mutual handshake between two independent {@link GossipMember}s, not just "the code
 * compiles" -- per {@code claudedocs/tls-transport-security-design.md} §2's gossip row. Uses fresh
 * {@link GossipConfig} timings tight enough to keep the test fast without starving the DTLS
 * handshake of the retries {@link GossipMember}'s own suspicion-timeout-driven session cleanup
 * allows for.
 */
class GossipMemberDtlsTest {

  private static final String PROTOCOL_PROPERTY = "gimle.transport.protocol";
  private static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";
  private static final String KEY_FILE_PROPERTY = "gimle.tls.keyFile";
  private static final String CA_FILE_PROPERTY = "gimle.tls.caFile";

  private static final GossipConfig FAST_CONFIG =
      new GossipConfig(Duration.ofMillis(100), Duration.ofMillis(80), Duration.ofSeconds(2), 2, 6);

  @TempDir private Path tempDir;

  private final List<GossipMember> members = new ArrayList<>();
  private int fileCounter;

  @AfterEach
  void tearDown() {
    members.forEach(GossipMember::close);
    System.clearProperty(PROTOCOL_PROPERTY);
    System.clearProperty(CERT_FILE_PROPERTY);
    System.clearProperty(KEY_FILE_PROPERTY);
    System.clearProperty(CA_FILE_PROPERTY);
  }

  @Test
  @Timeout(20)
  void two_nodes_discover_each_other_over_mutual_dtls() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));

    GossipMember a = newSecureMember("node-a", ca);
    GossipMember b = newSecureMember("node-b", ca);
    a.start();
    b.start();

    b.join(List.of(a.self().gossipAddress()));

    Await.until(() -> isAlive(a, "node-b") && isAlive(b, "node-a"), Duration.ofSeconds(15));
  }

  @Test
  @Timeout(20)
  void a_killed_member_still_converges_to_dead_over_dtls() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));

    GossipMember a = newSecureMember("node-a", ca);
    GossipMember b = newSecureMember("node-b", ca);
    a.start();
    b.start();
    b.join(List.of(a.self().gossipAddress()));

    Await.until(() -> isAlive(a, "node-b") && isAlive(b, "node-a"), Duration.ofSeconds(15));

    b.close();

    Await.until(
        () -> a.memberState("node-b").map(s -> s.status() == MemberStatus.DEAD).orElse(false),
        Duration.ofSeconds(15));
  }

  @Test
  @Timeout(20)
  void members_trusting_different_cas_never_become_mutually_aware() throws Exception {
    CertificateAuthority caForA =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=ca-a"), Duration.ofDays(1));
    CertificateAuthority caForB =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=ca-b"), Duration.ofDays(1));

    GossipMember a = newSecureMember("node-a", caForA);
    GossipMember b = newSecureMember("node-b", caForB);
    a.start();
    b.start();
    b.join(List.of(a.self().gossipAddress()));

    // A genuine DTLS rejection is silent from the caller's perspective -- the handshake never
    // completes, so the only observable effect is that neither side ever learns the other exists.
    // Sleeping out a window comfortably longer than the positive test's own convergence time is
    // the only way to assert a negative here.
    Thread.sleep(3_000);

    assertFalse(isAlive(a, "node-b"));
    assertFalse(isAlive(b, "node-a"));
    assertEquals(1, a.members().size());
    assertEquals(1, b.members().size());
  }

  private GossipMember newSecureMember(String nodeId, CertificateAuthority ca) throws Exception {
    configureTls(ca, nodeId);
    MemberId id = new MemberId(nodeId, new InetSocketAddress("127.0.0.1", 0));
    GossipMember member = new GossipMember(id, FAST_CONFIG);
    members.add(member);
    return member;
  }

  private void configureTls(CertificateAuthority ca, String nodeId) throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(keyPair, new X500Name("CN=" + nodeId));
    X509Certificate leaf = ca.signCertificateRequest(csr, Duration.ofDays(1));

    Path certFile = writePem(nodeId + "-cert.pem", "CERTIFICATE", leaf.getEncoded());
    Path keyFile = writePem(nodeId + "-key.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
    Path caFile = writePem(nodeId + "-ca.pem", "CERTIFICATE", ca.certificate().getEncoded());

    System.setProperty(PROTOCOL_PROPERTY, "tls");
    System.setProperty(CERT_FILE_PROPERTY, certFile.toString());
    System.setProperty(KEY_FILE_PROPERTY, keyFile.toString());
    System.setProperty(CA_FILE_PROPERTY, caFile.toString());
  }

  private Path writePem(String fileName, String label, byte[] derBytes) throws IOException {
    String base64 =
        Base64.getMimeEncoder(64, System.lineSeparator().getBytes(StandardCharsets.US_ASCII))
            .encodeToString(derBytes);
    String pem =
        "-----BEGIN "
            + label
            + "-----"
            + System.lineSeparator()
            + base64
            + System.lineSeparator()
            + "-----END "
            + label
            + "-----"
            + System.lineSeparator();
    Path path = tempDir.resolve((fileCounter++) + "-" + fileName);
    Files.writeString(path, pem);
    return path;
  }

  private static KeyPair generateRsaKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private static boolean isAlive(GossipMember member, String nodeId) {
    return member.memberState(nodeId).map(s -> s.status() == MemberStatus.ALIVE).orElse(false);
  }
}
