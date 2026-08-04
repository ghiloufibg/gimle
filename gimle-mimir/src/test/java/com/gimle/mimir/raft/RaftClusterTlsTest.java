package com.gimle.mimir.raft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.store.StateStore;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.CertificateSigningRequests;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLHandshakeException;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Mirrors {@link RaftClusterTest}'s own scenarios (leader election, write replication, leader-crash
 * re-election) but with {@code gimle.transport.protocol=tls}, proving mTLS doesn't break consensus
 * mechanics -- design doc §5 verification item 2. Every simulated node shares one CA-issued
 * identity rather than a distinct one per node: {@link
 * com.gimle.core.tls.TransportProtocol#fromConfig()}/{@link
 * com.gimle.core.tls.TlsSettings#fromConfig()} read process-global system properties, and this
 * test's "nodes" are threads in one JVM, not separate processes the way a real cluster's nodes are
 * -- a real per-node identity difference isn't expressible here, but is exactly what {@link
 * com.gimle.pki.SslContextsIntegrationTest} and {@code ApiServerTlsTest} already cover directly.
 * What this test proves instead -- and what those can't -- is that {@link RaftTransport}/{@link
 * PeerConnection}'s socket-factory swap doesn't disturb Raft's own RPC framing or timing.
 *
 * <p>The negative case (§5 item 3, "a peer cert not signed by the configured CA is rejected") is
 * exercised here too, exploiting the one piece of state that *does* stay put across a system
 * property change mid-test: {@link RaftTransport#listen} bakes its {@code SSLServerSocketFactory}
 * in at bind time, so reconfiguring the global CA *after* a listener is already bound and
 * connecting a fresh {@link PeerConnection} against a *different* CA afterward is a genuine,
 * deterministic cross-CA handshake -- not a workaround, an actual exploit of how the config is
 * read.
 */
// System.setProperty mutates a JVM-global; excludes this class from running concurrently with
// any other class holding the same lock, under class-level parallel execution (root pom.xml).
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class RaftClusterTlsTest {

  private static final String PROTOCOL_PROPERTY = "gimle.transport.protocol";
  private static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";
  private static final String KEY_FILE_PROPERTY = "gimle.tls.keyFile";
  private static final String CA_FILE_PROPERTY = "gimle.tls.caFile";

  @TempDir Path tempDir;

  private final List<RaftNode> nodes = new ArrayList<>();
  private final List<RaftTransport> transports = new ArrayList<>();
  private int fileCounter;

  @AfterEach
  void tearDown() {
    for (RaftNode node : nodes) {
      node.close();
    }
    for (RaftTransport transport : transports) {
      transport.close();
    }
    System.clearProperty(PROTOCOL_PROPERTY);
    System.clearProperty(CERT_FILE_PROPERTY);
    System.clearProperty(KEY_FILE_PROPERTY);
    System.clearProperty(CA_FILE_PROPERTY);
  }

  private record ClusterNode(
      String id,
      RaftNode raftNode,
      RaftTransport transport,
      InetSocketAddress address,
      StateStore store) {}

  private static final class HandlerRef implements RaftRpcHandler {
    volatile RaftRpcHandler delegate;

    @Override
    public RequestVoteResponse onRequestVote(RequestVote request) {
      return delegate.onRequestVote(request);
    }

    @Override
    public AppendEntriesResponse onAppendEntries(AppendEntries request) {
      return delegate.onAppendEntries(request);
    }

    @Override
    public InstallSnapshotResponse onInstallSnapshot(InstallSnapshot request) {
      return delegate.onInstallSnapshot(request);
    }
  }

  private static InetSocketAddress reserveAddress() throws IOException {
    try (ServerSocketChannel probe = ServerSocketChannel.open()) {
      probe.bind(new InetSocketAddress("127.0.0.1", 0));
      return (InetSocketAddress) probe.getLocalAddress();
    }
  }

  private void configureTls(CertificateAuthority ca) throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(keyPair, new X500Name("CN=raft-node"));
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

  private List<ClusterNode> buildAndStartCluster(int n) throws Exception {
    List<String> ids = new ArrayList<>();
    List<InetSocketAddress> addresses = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      ids.add("node-" + i);
      addresses.add(reserveAddress());
    }

    List<ClusterNode> clusterNodes = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      HandlerRef ref = new HandlerRef();
      RaftTransport transport = new RaftTransport(ref);
      transports.add(transport);

      Map<String, RaftPeerClient> peers = new HashMap<>();
      for (int j = 0; j < n; j++) {
        if (j == i) {
          continue;
        }
        peers.put(ids.get(j), new PeerConnection(addresses.get(j)));
      }

      Path dir = tempDir.resolve("node-" + i);
      StateStore store = new StateStore(dir.resolve("store"));
      RaftLog raftLog = new RaftLog(dir.resolve("raft"));
      RaftNode node = new RaftNode(ids.get(i), peers, raftLog, store);
      ref.delegate = node;
      nodes.add(node);
      clusterNodes.add(new ClusterNode(ids.get(i), node, transport, addresses.get(i), store));
    }

    for (ClusterNode clusterNode : clusterNodes) {
      clusterNode.transport().listen(clusterNode.address());
      clusterNode.raftNode().start();
    }
    return clusterNodes;
  }

  private static void awaitTrue(BooleanSupplier condition, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(20);
    }
    assertTrue(condition.getAsBoolean(), "condition not met within " + timeout);
  }

  private static ClusterNode awaitLeader(List<ClusterNode> cluster) throws InterruptedException {
    awaitTrue(() -> cluster.stream().anyMatch(c -> c.raftNode().isLeader()), Duration.ofSeconds(5));
    return cluster.stream().filter(c -> c.raftNode().isLeader()).findFirst().orElseThrow();
  }

  @Test
  @Timeout(15)
  void leader_election_and_write_replication_work_over_mtls() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureTls(ca);

    List<ClusterNode> cluster = buildAndStartCluster(3);
    ClusterNode leader = awaitLeader(cluster);
    long leaderCount = cluster.stream().filter(c -> c.raftNode().isLeader()).count();
    assertEquals(1, leaderCount);

    leader
        .raftNode()
        .propose(new StateMutation.PutTenant(new Tenant("t1", new ResourceQuota(10, 10, 10))));

    awaitTrue(
        () -> cluster.stream().allMatch(c -> c.store().getTenant("t1").isPresent()),
        Duration.ofSeconds(5));
    for (ClusterNode c : cluster) {
      assertTrue(c.store().getTenant("t1").isPresent());
    }
  }

  @Test
  @Timeout(20)
  void leader_crash_triggers_re_election_over_mtls() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureTls(ca);

    List<ClusterNode> cluster = buildAndStartCluster(3);
    ClusterNode firstLeader = awaitLeader(cluster);

    firstLeader.transport().close();
    firstLeader.raftNode().close();

    List<ClusterNode> remaining = cluster.stream().filter(c -> c != firstLeader).toList();
    ClusterNode newLeader = awaitLeader(remaining);
    assertNotEquals(firstLeader.id(), newLeader.id());
  }

  @Test
  @Timeout(10)
  void reloading_tls_material_lets_a_fresh_connection_succeed_without_restarting_the_transport()
      throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureTls(ca);
    Path certFile = Path.of(System.getProperty(CERT_FILE_PROPERTY));
    Path keyFile = Path.of(System.getProperty(KEY_FILE_PROPERTY));

    HandlerRef ref = new HandlerRef();
    RaftTransport serverTransport = new RaftTransport(ref);
    transports.add(serverTransport);
    RaftNode serverNode =
        new RaftNode(
            "server",
            Map.of(),
            new RaftLog(tempDir.resolve("server-raft")),
            new StateStore(tempDir.resolve("server-store")));
    ref.delegate = serverNode;
    nodes.add(serverNode);
    InetSocketAddress serverAddress = (InetSocketAddress) serverTransport.listen(reserveAddress());

    // Baseline: the original cert/key material works before any rotation.
    PeerConnection before = new PeerConnection(serverAddress);
    before.requestVote(new RequestVote(1, "server", 0, 0));
    before.close();

    // Rotate: a fresh CA-signed leaf, written over the *same* cert/key file paths -- exactly what
    // §4b's own rotation does to gimle.tls.certFile/keyFile in place.
    KeyPair rotatedKeyPair = generateRsaKeyPair();
    PKCS10CertificationRequest rotatedCsr =
        CertificateSigningRequests.generate(rotatedKeyPair, new X500Name("CN=raft-node"));
    X509Certificate rotatedLeaf = ca.signCertificateRequest(rotatedCsr, Duration.ofDays(1));
    overwritePem(certFile, "CERTIFICATE", rotatedLeaf.getEncoded());
    overwritePem(keyFile, "PRIVATE KEY", rotatedKeyPair.getPrivate().getEncoded());

    serverTransport.reloadTlsMaterial();

    // A brand-new connection (not the already-established one from before rotation) must succeed
    // against the reloaded listener, at the *same* address, without restarting the process --
    // if reload hadn't rebound there, this connection attempt would simply fail.
    PeerConnection after = new PeerConnection(serverAddress);
    RequestVoteResponse response = after.requestVote(new RequestVote(2, "server", 0, 0));
    assertNotEquals(null, response);
    after.close();
  }

  private void overwritePem(Path path, String label, byte[] derBytes) throws IOException {
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
    Files.writeString(path, pem);
  }

  @Test
  @Timeout(10)
  void a_peer_cert_not_signed_by_the_configured_ca_is_rejected_at_handshake() throws Exception {
    CertificateAuthority realCa =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=real-ca"), Duration.ofDays(1));
    configureTls(realCa);

    // The server's SSLServerSocketFactory is resolved and baked in right now, at listen() time --
    // reconfiguring the global CA afterward does not retroactively change it.
    HandlerRef ref = new HandlerRef();
    RaftTransport serverTransport = new RaftTransport(ref);
    transports.add(serverTransport);
    RaftNode serverNode =
        new RaftNode(
            "server",
            Map.of(),
            new RaftLog(tempDir.resolve("server-raft")),
            new StateStore(tempDir.resolve("server-store")));
    ref.delegate = serverNode;
    nodes.add(serverNode);
    InetSocketAddress serverAddress = reserveAddress();
    serverTransport.listen(serverAddress);

    CertificateAuthority impostorCa =
        CertificateAuthority.generateSelfSignedCa(
            new X500Name("CN=impostor-ca"), Duration.ofDays(1));
    configureTls(impostorCa);
    PeerConnection client = new PeerConnection(serverAddress);

    UncheckedIOException thrown =
        assertThrows(
            UncheckedIOException.class,
            () -> client.requestVote(new RequestVote(1, "server", 0, 0)));
    assertInstanceOf(SSLHandshakeException.class, thrown.getCause());
    client.close();
  }
}
