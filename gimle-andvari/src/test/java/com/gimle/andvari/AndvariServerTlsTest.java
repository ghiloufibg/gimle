package com.gimle.andvari;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.andvari.testsupport.InProcessStore;
import com.gimle.andvari.testsupport.TlsTestFixtures;
import com.gimle.core.authz.BuiltinRoles;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.CertificateSigningRequests;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Proves {@code gimle.transport.protocol=tls} actually swaps {@link AndvariServer} onto {@code
 * HttpsServer} with real mTLS, and that the authorization posture holds against real CA-signed
 * client certificates: operators may push, an ungrouped principal is refused by this process's own
 * independent RBAC check, a {@code gimle:nodes} identity may only ever pull, and a forwarded
 * principal both wins over the peer certificate and is re-checked rather than trusted -- plus a
 * real cert-rotation reload, mirroring {@code FafnirServerTlsTest}'s own shape.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-andvari-server-http")
class AndvariServerTlsTest {

  private static final byte[] JAR = "pretend-jar-bytes".getBytes(StandardCharsets.UTF_8);

  @TempDir(cleanup = CleanupMode.NEVER)
  private Path tempDir;

  private TlsTestFixtures tls;
  private CertificateAuthority ca;
  private InProcessStore store;
  private AndvariServer server;

  @BeforeEach
  void setUp() throws Exception {
    tls = new TlsTestFixtures(tempDir);
    ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);
    store = InProcessStore.start(tempDir.resolve("store"));
    server = new AndvariServer(store.client(), 0, tempDir.resolve("data"));
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.close();
    store.close();
    TlsTestFixtures.clearTransportProperties();
  }

  @Test
  @Timeout(10)
  void an_operator_group_certificate_may_push_and_pull() throws Exception {
    HttpClient operator =
        tls.clientWithGroupLeaf(ca, BuiltinRoles.GROUP_OPERATORS, "admin-operator");

    assertEquals(200, send(operator, push("com.example.app", "1.0.0")).statusCode());
    assertEquals(200, send(operator, pull("com.example.app", "1.0.0")).statusCode());
  }

  @Test
  @Timeout(10)
  void an_ungrouped_certificate_is_refused_by_the_independent_rbac_check() throws Exception {
    // A valid, CA-signed certificate -- the mTLS handshake itself succeeds -- but the principal has
    // no group and no role binding in the store, so the Authorizer re-check denies it.
    HttpClient stranger = tls.clientWithLeaf(ca, "stranger");

    assertEquals(403, send(stranger, push("com.example.app", "1.0.0")).statusCode());
    assertEquals(403, send(stranger, pull("com.example.app", "1.0.0")).statusCode());
  }

  @Test
  @Timeout(10)
  void a_nodes_group_certificate_may_pull_only_coordinates_assigned_to_its_node() throws Exception {
    HttpClient operator =
        tls.clientWithGroupLeaf(ca, BuiltinRoles.GROUP_OPERATORS, "admin-operator");
    assertEquals(200, send(operator, push("com.example.app", "1.0.0")).statusCode());

    HttpClient node = tls.clientWithGroupLeaf(ca, BuiltinRoles.GROUP_NODES, "node-1");
    // No assignment for this node yet: even a coordinate that exists in the store is refused --
    // a node identity may only pull what the control plane has actually placed on it.
    assertEquals(403, send(node, pull("com.example.app", "1.0.0")).statusCode());

    store
        .client()
        .propose(
            new StateMutation.PutAssignment(
                new InstanceAssignment(
                    "greeter",
                    0,
                    "node-1",
                    new ModuleId("com.example.app", Version.parse("1.0.0")),
                    "")));

    assertEquals(200, send(node, pull("com.example.app", "1.0.0")).statusCode());
    // Assignment scoping is per-coordinate, not per-node-blanket: a different version stays 403.
    assertEquals(403, send(node, pull("com.example.app", "9.9.9")).statusCode());
    assertEquals(403, send(node, push("com.example.app", "2.0.0")).statusCode());
    assertEquals(
        403,
        send(node, HttpRequest.newBuilder(uri("com.example.app", "1.0.0")).DELETE().build())
            .statusCode());
  }

  @Test
  @Timeout(10)
  void a_forwarded_principal_wins_over_the_peer_certificate_and_is_re_checked() throws Exception {
    // The peer certificate carries the operators group, but the forwarded principal -- the
    // originating caller a control-plane proxy names -- does not: the forwarded identity must win
    // and be independently re-checked, so the push is refused despite the privileged transport.
    HttpClient proxyLikeCaller =
        tls.clientWithGroupLeaf(ca, BuiltinRoles.GROUP_OPERATORS, "controlplane-proxy");
    HttpRequest forwardedStranger =
        HttpRequest.newBuilder(uri("com.example.app", "1.0.0"))
            .header("X-Gimle-Forwarded-Principal", "user:stranger")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(JAR))
            .build();
    assertEquals(403, send(proxyLikeCaller, forwardedStranger).statusCode());

    // And the converse: an unprivileged peer certificate forwarding an operators-group principal
    // is allowed -- the forwarded claim, not the connection's own leaf, is what gets authorized.
    HttpClient plainProxy = tls.clientWithLeaf(ca, "plain-proxy");
    HttpRequest forwardedOperator =
        HttpRequest.newBuilder(uri("com.example.app", "1.0.0"))
            .header("X-Gimle-Forwarded-Principal", "user:admin")
            .header("X-Gimle-Forwarded-Groups", BuiltinRoles.GROUP_OPERATORS)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(JAR))
            .build();
    assertEquals(200, send(plainProxy, forwardedOperator).statusCode());
  }

  @Test
  @Timeout(10)
  void reloading_tls_material_lets_a_fresh_connection_succeed_without_restarting_the_server()
      throws Exception {
    Path certFile = Path.of(System.getProperty(TlsTestFixtures.CERT_FILE_PROPERTY));
    Path keyFile = Path.of(System.getProperty(TlsTestFixtures.KEY_FILE_PROPERTY));
    Path caFile = Path.of(System.getProperty(TlsTestFixtures.CA_FILE_PROPERTY));
    TlsSettings clientSettings = new TlsSettings(certFile, keyFile, caFile);
    HttpClient before =
        HttpClient.newBuilder().sslContext(SslContexts.forMutualTls(clientSettings)).build();
    assertEquals(
        200,
        before
            .send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .statusCode());

    // Rotate: a fresh CA-signed leaf, written over the *same* cert/key file paths -- exactly what
    // a real rotation does to gimle.tls.certFile/keyFile in place.
    KeyPair rotatedKeyPair = TlsTestFixtures.generateRsaKeyPair();
    PKCS10CertificationRequest rotatedCsr =
        CertificateSigningRequests.generate(
            rotatedKeyPair, new X500Name("CN=andvari"), List.of("localhost"));
    X509Certificate rotatedLeaf = ca.signCertificateRequest(rotatedCsr, Duration.ofDays(1));
    tls.overwritePem(certFile, "CERTIFICATE", rotatedLeaf.getEncoded());
    tls.overwritePem(keyFile, "PRIVATE KEY", rotatedKeyPair.getPrivate().getEncoded());

    server.reloadTlsMaterial();

    // A brand-new connection (not the already-established one from before rotation) must succeed
    // against the reloaded listener, at the *same* port, without restarting the process.
    HttpClient after =
        HttpClient.newBuilder().sslContext(SslContexts.forMutualTls(clientSettings)).build();
    assertEquals(
        200,
        after
            .send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .statusCode());
  }

  private String baseUrl() {
    return "https://localhost:" + server.port();
  }

  private URI uri(String moduleId, String version) {
    return URI.create(baseUrl() + "/artifacts/" + moduleId + "/" + version);
  }

  private HttpRequest push(String moduleId, String version) {
    return HttpRequest.newBuilder(uri(moduleId, version))
        .PUT(HttpRequest.BodyPublishers.ofByteArray(JAR))
        .build();
  }

  private HttpRequest pull(String moduleId, String version) {
    return HttpRequest.newBuilder(uri(moduleId, version)).GET().build();
  }

  private static HttpResponse<String> send(HttpClient client, HttpRequest request)
      throws Exception {
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }
}
