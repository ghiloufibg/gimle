package com.gimle.mimir.rpc;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleRaftException;
import com.gimle.mimir.raft.RaftLog;
import com.gimle.mimir.raft.RaftNode;
import com.gimle.mimir.store.StateStore;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.CertificateSigningRequests;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * A bad mTLS certificate must surface as its own clear, TLS-specific {@link GimleRaftException} --
 * never {@link GimleRaftException#storeUnreachable}'s generic message, which reads exactly like a
 * genuinely dead cluster or network partition and sends an operator debugging a certificate mistake
 * looking in the wrong place entirely. Modeled on {@code RaftClusterTlsTest}'s own cross-CA
 * handshake-rejection scenario, one layer up: a real {@link StoreTransport} listener trusting one
 * CA, dialed by a real {@link StoreClient} presenting a leaf signed by a different one -- the same
 * failure shape a genuinely mismatched or expired certificate produces, since both fail at
 * handshake time.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@Isolated
class StoreClientTlsFailureTest {

  private static final String PROTOCOL_PROPERTY = "gimle.transport.protocol";
  private static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";
  private static final String KEY_FILE_PROPERTY = "gimle.tls.keyFile";
  private static final String CA_FILE_PROPERTY = "gimle.tls.caFile";

  @TempDir Path tempDir;

  private StoreTransport serverTransport;
  private RaftNode serverRaftNode;
  private StoreClient client;
  private int fileCounter;

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
    if (serverTransport != null) {
      serverTransport.close();
    }
    if (serverRaftNode != null) {
      serverRaftNode.close();
    }
    System.clearProperty(PROTOCOL_PROPERTY);
    System.clearProperty(CERT_FILE_PROPERTY);
    System.clearProperty(KEY_FILE_PROPERTY);
    System.clearProperty(CA_FILE_PROPERTY);
  }

  private static InetSocketAddress reserveAddress() throws IOException {
    try (ServerSocketChannel probe = ServerSocketChannel.open()) {
      probe.bind(new InetSocketAddress("127.0.0.1", 0));
      return (InetSocketAddress) probe.getLocalAddress();
    }
  }

  private void configureTls(CertificateAuthority ca, String commonName) throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(keyPair, new X500Name("CN=" + commonName));
    X509Certificate leaf = ca.signCertificateRequest(csr, Duration.ofDays(1));

    Path certFile = writePem("cert.pem", "CERTIFICATE", leaf.getEncoded());
    Path keyFile = writePem("key.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
    Path caFile = writePem("ca.pem", "CERTIFICATE", ca.certificate().getEncoded());

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

  /**
   * A minimal, never-started single "node": enough for {@link StoreTransport} to bind a real {@code
   * SSLServerSocket} that trusts {@code serverCa}, but the connection this test drives always fails
   * at the TLS handshake, before any request byte would ever reach {@link StoreNode}.
   */
  private InetSocketAddress startServer(CertificateAuthority serverCa) throws Exception {
    configureTls(serverCa, "store-node");
    StateStore store = new StateStore();
    RaftLog raftLog = new RaftLog(tempDir.resolve("server-raft"));
    serverRaftNode = new RaftNode("node-0:1", Map.of(), raftLog, store);
    StoreNode storeNode = new StoreNode(serverRaftNode, store, Map.of());
    serverTransport = new StoreTransport(storeNode);
    return (InetSocketAddress) serverTransport.listen(reserveAddress());
  }

  @Test
  @Timeout(15)
  void an_any_node_call_surfaces_a_tls_specific_failure_with_the_handshake_exception_chained()
      throws Exception {
    CertificateAuthority serverCa =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=real-ca"), Duration.ofDays(1));
    InetSocketAddress serverAddress = startServer(serverCa);

    // The server's SSLServerSocketFactory is baked in at listen() time -- reconfiguring the
    // global CA afterward only changes what a *new* client presents, a genuine cross-CA mismatch,
    // the same failure shape as a validly-signed-but-wrongly-issued or expired leaf.
    CertificateAuthority impostorCa =
        CertificateAuthority.generateSelfSignedCa(
            new X500Name("CN=impostor-ca"), Duration.ofDays(1));
    configureTls(impostorCa, "impostor-client");
    client = new StoreClient(List.of(serverAddress));

    GimleRaftException thrown = assertThrows(GimleRaftException.class, client::status);

    assertInstanceOf(SSLHandshakeException.class, thrown.getCause());
    String message = thrown.getMessage().toLowerCase();
    assertTrue(
        message.contains("tls") || message.contains("handshake") || message.contains("certificate"),
        "expected a TLS-specific message, got: " + thrown.getMessage());
  }

  @Test
  @Timeout(15)
  void a_leader_routed_call_fails_fast_on_a_bad_certificate_instead_of_waiting_out_the_timeout()
      throws Exception {
    CertificateAuthority serverCa =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=real-ca"), Duration.ofDays(1));
    InetSocketAddress serverAddress = startServer(serverCa);

    CertificateAuthority impostorCa =
        CertificateAuthority.generateSelfSignedCa(
            new X500Name("CN=impostor-ca"), Duration.ofDays(1));
    configureTls(impostorCa, "impostor-client");
    client = new StoreClient(List.of(serverAddress));

    long startNanos = System.nanoTime();
    GimleRaftException thrown =
        assertThrows(GimleRaftException.class, () -> client.getTenant("acme"));
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

    assertInstanceOf(SSLHandshakeException.class, thrown.getCause());
    // The leader-search budget is 10s; a bad certificate must not spend it -- it can never
    // self-resolve the way a mid-election "no leader yet" gap can.
    assertTrue(elapsed.toSeconds() < 5, "expected a fast failure, took " + elapsed);
  }

  @Test
  @Timeout(15)
  void an_ordinary_unreachable_endpoint_still_produces_the_generic_unreachable_message()
      throws Exception {
    InetSocketAddress nothingListening = reserveAddress();
    client = new StoreClient(List.of(nothingListening));

    GimleRaftException thrown = assertThrows(GimleRaftException.class, client::status);

    assertNull(thrown.getCause());
    assertTrue(
        thrown.getMessage().contains("no reachable store leader could serve"),
        "expected the unchanged generic message, got: " + thrown.getMessage());
  }
}
