package com.gimle.muninn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.authz.Permission;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Role;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.authz.Verb;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.mimir.store.StateStore;
import com.gimle.muninn.testsupport.InProcessStore;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.CertificateSigningRequests;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLContext;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * {@link MuninnServer} runs its own, independent {@code Authorizer.authorize(...)} check on every
 * {@code /logs/*}, {@code /metrics/*}, and {@code /traces/*} read rather than trusting "reachable
 * at all on this port" as proof of authorization -- mirroring {@code FafnirSecretsAuthzTest}'s own
 * shape for the identical defense-in-depth posture on {@code /secrets/*}. Every test here talks to
 * {@link MuninnServer} directly over real mTLS -- no {@code ApiServer} in the loop -- to prove
 * Muninn's own gate works standalone, including the specific scenario a compromised or absent proxy
 * check would otherwise let through: a caller whose own certificate holds no {@code LOGS}
 * permission for the tenant/node it's asking to read.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-muninn-server-http")
class MuninnServerReadAuthzTest {

  private static final String PROTOCOL_PROPERTY = "gimle.transport.protocol";
  private static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";
  private static final String KEY_FILE_PROPERTY = "gimle.tls.keyFile";
  private static final String CA_FILE_PROPERTY = "gimle.tls.caFile";

  @TempDir(cleanup = CleanupMode.NEVER)
  private Path tempDir;

  @AfterEach
  void clearTransportProperties() {
    System.clearProperty(PROTOCOL_PROPERTY);
    System.clearProperty(CERT_FILE_PROPERTY);
    System.clearProperty(KEY_FILE_PROPERTY);
    System.clearProperty(CA_FILE_PROPERTY);
  }

  /**
   * The legitimate case that must keep working: a caller holding a real, tenant-scoped {@code
   * LOGS}/{@code READ} grant for {@code acme} can read {@code acme}'s own instance logs.
   */
  @Test
  @Timeout(10)
  void a_caller_whose_own_certificate_holds_the_logs_permission_can_read_instance_logs()
      throws Exception {
    CertificateAuthority ca = selfSignedCa();
    configureServerTls(ca);

    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"))) {
      grantLogsRead(store.store(), "caller", "acme");
      try (MuninnServer server = new MuninnServer(store.client(), 0, tempDir.resolve("data"))) {
        server.start();
        HttpClient client = clientWithLeaf(ca, "caller");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create(
                            "https://localhost:"
                                + server.port()
                                + "/logs/instances/orders/0/APPLICATION?tenant=acme"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
      }
    }
  }

  /**
   * The gap this fix closes: before it, {@code read()} never resolved a caller identity or checked
   * one at all -- any CA-signed certificate could read any tenant's instance logs. "mallory" holds
   * no {@code LOGS} grant for {@code acme} whatsoever.
   */
  @Test
  @Timeout(10)
  void a_caller_with_no_logs_permission_is_forbidden_reading_instance_logs() throws Exception {
    CertificateAuthority ca = selfSignedCa();
    configureServerTls(ca);

    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"))) {
      // No Role/RoleBinding granted to "mallory" at all -- an authenticated, CA-signed identity
      // with zero RBAC permissions.
      try (MuninnServer server = new MuninnServer(store.client(), 0, tempDir.resolve("data"))) {
        server.start();
        HttpClient client = clientWithLeaf(ca, "mallory");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create(
                            "https://localhost:"
                                + server.port()
                                + "/logs/instances/orders/0/APPLICATION?tenant=acme"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(403, response.statusCode());
      }
    }
  }

  /**
   * A grant scoped to a different tenant does not leak into this one -- proves the check is a
   * genuine per-tenant scoping, not merely "does this caller hold any LOGS grant at all."
   */
  @Test
  @Timeout(10)
  void a_grant_for_a_different_tenant_does_not_authorize_reading_this_ones_instance_logs()
      throws Exception {
    CertificateAuthority ca = selfSignedCa();
    configureServerTls(ca);

    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"))) {
      grantLogsRead(store.store(), "caller", "other-tenant");
      try (MuninnServer server = new MuninnServer(store.client(), 0, tempDir.resolve("data"))) {
        server.start();
        HttpClient client = clientWithLeaf(ca, "caller");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create(
                            "https://localhost:"
                                + server.port()
                                + "/logs/instances/orders/0/APPLICATION?tenant=acme"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(403, response.statusCode());
      }
    }
  }

  /**
   * A {@code gimle:nodes} certificate may self-service its own node's logs (no {@code RoleBinding}
   * needed for it -- the same self-service branch {@code Authorizer#isNodeSelfService} grants for
   * {@code FafnirServer}'s own node-scoped reads).
   */
  @Test
  @Timeout(10)
  void a_node_may_read_its_own_node_logs() throws Exception {
    CertificateAuthority ca = selfSignedCa();
    configureServerTls(ca);

    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"))) {
      try (MuninnServer server = new MuninnServer(store.client(), 0, tempDir.resolve("data"))) {
        server.start();
        HttpClient client = nodeClientWithLeaf(ca, "node-1");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create(
                            "https://localhost:" + server.port() + "/logs/nodes/node-1/PLATFORM"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
      }
    }
  }

  /**
   * Self-service is scoped to exactly the node's own {@code nodeId}, not every node -- one
   * compromised node certificate must not be able to read another node's log stream.
   */
  @Test
  @Timeout(10)
  void a_node_may_not_read_a_different_nodes_logs() throws Exception {
    CertificateAuthority ca = selfSignedCa();
    configureServerTls(ca);

    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"))) {
      try (MuninnServer server = new MuninnServer(store.client(), 0, tempDir.resolve("data"))) {
        server.start();
        HttpClient client = nodeClientWithLeaf(ca, "node-1");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create(
                            "https://localhost:" + server.port() + "/logs/nodes/node-2/PLATFORM"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(403, response.statusCode());
      }
    }
  }

  /**
   * Metrics/traces reads are unscoped by tenant (a process's metrics belong to no single tenant),
   * so an unscoped {@code LOGS}/{@code READ} grant is what covers them -- the same shape {@code
   * ApiServer#handleHistoryProxy} checks before proxying to Muninn.
   */
  @Test
  @Timeout(10)
  void a_caller_with_an_unscoped_logs_permission_can_read_metrics() throws Exception {
    CertificateAuthority ca = selfSignedCa();
    configureServerTls(ca);

    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"))) {
      grantUnscopedLogsRead(store.store(), "caller");
      try (MuninnServer server = new MuninnServer(store.client(), 0, tempDir.resolve("data"))) {
        server.start();
        HttpClient client = clientWithLeaf(ca, "caller");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create(
                            "https://localhost:"
                                + server.port()
                                + "/metrics/controlplane/localhost:9000"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void a_caller_with_no_logs_permission_is_forbidden_reading_metrics() throws Exception {
    CertificateAuthority ca = selfSignedCa();
    configureServerTls(ca);

    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"))) {
      try (MuninnServer server = new MuninnServer(store.client(), 0, tempDir.resolve("data"))) {
        server.start();
        HttpClient client = clientWithLeaf(ca, "mallory");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create(
                            "https://localhost:"
                                + server.port()
                                + "/metrics/controlplane/localhost:9000"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(403, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void a_caller_with_no_logs_permission_is_forbidden_reading_traces() throws Exception {
    CertificateAuthority ca = selfSignedCa();
    configureServerTls(ca);

    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"))) {
      try (MuninnServer server = new MuninnServer(store.client(), 0, tempDir.resolve("data"))) {
        server.start();
        HttpClient client = clientWithLeaf(ca, "mallory");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create(
                            "https://localhost:"
                                + server.port()
                                + "/traces/controlplane/localhost:9000"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(403, response.statusCode());
      }
    }
  }

  /**
   * The cross-process trace search reaches every process's shipped spans at once, so an unchecked
   * one would be a wider hole than any single per-process read: it must run the same gate.
   */
  @Test
  @Timeout(10)
  void a_caller_with_no_logs_permission_is_forbidden_searching_for_a_trace() throws Exception {
    CertificateAuthority ca = selfSignedCa();
    configureServerTls(ca);

    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"))) {
      try (MuninnServer server = new MuninnServer(store.client(), 0, tempDir.resolve("data"))) {
        server.start();
        HttpClient client = clientWithLeaf(ca, "mallory");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create(
                            "https://localhost:"
                                + server.port()
                                + "/traces-by-id/0af7651916cd43dd8448eb211c80319c"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(403, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void a_caller_with_an_unscoped_logs_permission_can_search_for_a_trace() throws Exception {
    CertificateAuthority ca = selfSignedCa();
    configureServerTls(ca);

    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"))) {
      grantUnscopedLogsRead(store.store(), "caller");
      try (MuninnServer server = new MuninnServer(store.client(), 0, tempDir.resolve("data"))) {
        server.start();
        HttpClient client = clientWithLeaf(ca, "caller");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create(
                            "https://localhost:"
                                + server.port()
                                + "/traces-by-id/0af7651916cd43dd8448eb211c80319c"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
      }
    }
  }

  /**
   * Plaintext mode has no identity to check -- fully open, matching every other Gimlé process's
   * documented plaintext posture (see {@code FafnirServer#authorizeSecrets}'s own identical
   * carve-out). Proves the fix didn't accidentally regress the unconfigured default.
   */
  @Test
  @Timeout(10)
  void an_unauthenticated_plaintext_read_is_allowed() throws Exception {
    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"))) {
      try (MuninnServer server = new MuninnServer(store.client(), 0, tempDir.resolve("data"))) {
        server.start();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://127.0.0.1:"
                                + server.port()
                                + "/logs/instances/orders/0/APPLICATION?tenant=acme"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
      }
    }
  }

  private static void grantLogsRead(StateStore store, String username, String tenantId) {
    store.putRole(
        new Role(
            "logs-read-" + tenantId,
            Set.of(Permission.scoped(ResourceKind.LOGS, Verb.READ, tenantId))));
    store.putRoleBinding(
        new RoleBinding(
            "b-" + username + "-" + tenantId,
            RoleBinding.userSubject(username),
            "logs-read-" + tenantId));
  }

  private static void grantUnscopedLogsRead(StateStore store, String username) {
    store.putRole(
        new Role("logs-read-unscoped", Set.of(Permission.unscoped(ResourceKind.LOGS, Verb.READ))));
    store.putRoleBinding(
        new RoleBinding("b-" + username, RoleBinding.userSubject(username), "logs-read-unscoped"));
  }

  private static CertificateAuthority selfSignedCa() throws Exception {
    return CertificateAuthority.generateSelfSignedCa(
        new X500Name("CN=test-ca"), Duration.ofDays(1));
  }

  private void configureServerTls(CertificateAuthority ca) throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(
            keyPair, new X500Name("CN=muninn"), List.of("localhost"));
    X509Certificate leaf = ca.signCertificateRequest(csr, Duration.ofDays(1));
    Path certFile = writePem("muninn-cert.pem", "CERTIFICATE", leaf.getEncoded());
    Path keyFile = writePem("muninn-key.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
    Path caFile = writePem("muninn-ca.pem", "CERTIFICATE", ca.certificate().getEncoded());

    System.setProperty(PROTOCOL_PROPERTY, "tls");
    System.setProperty(CERT_FILE_PROPERTY, certFile.toString());
    System.setProperty(KEY_FILE_PROPERTY, keyFile.toString());
    System.setProperty(CA_FILE_PROPERTY, caFile.toString());
  }

  private HttpClient clientWithLeaf(CertificateAuthority ca, String commonName) throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(keyPair, new X500Name("CN=" + commonName));
    X509Certificate leaf = ca.signCertificateRequest(csr, Duration.ofDays(1));
    TlsSettings settings = writeLeaf(commonName, keyPair, leaf, ca);
    SSLContext sslContext = SslContexts.forMutualTls(settings);
    return HttpClient.newBuilder().sslContext(sslContext).build();
  }

  /**
   * An {@link HttpClient} presenting a leaf stamped {@code O=gimle:nodes} (the server-side-only
   * stamp real CSR issuance applies) -- exercises the node self-service authorization path rather
   * than {@link #clientWithLeaf}'s ordinary-RBAC one.
   */
  private HttpClient nodeClientWithLeaf(CertificateAuthority ca, String nodeId) throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(keyPair, new X500Name("CN=" + nodeId));
    X509Certificate leaf =
        ca.signCertificateRequest(
            csr, new X500Name("O=gimle:nodes,CN=" + nodeId), Duration.ofDays(1));
    TlsSettings settings = writeLeaf(nodeId, keyPair, leaf, ca);
    SSLContext sslContext = SslContexts.forMutualTls(settings);
    return HttpClient.newBuilder().sslContext(sslContext).build();
  }

  private TlsSettings writeLeaf(
      String label, KeyPair keyPair, X509Certificate leaf, CertificateAuthority ca)
      throws Exception {
    Path certFile = writePem(label + "-cert.pem", "CERTIFICATE", leaf.getEncoded());
    Path keyFile = writePem(label + "-key.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
    Path caFile = writePem(label + "-ca.pem", "CERTIFICATE", ca.certificate().getEncoded());
    return new TlsSettings(certFile, keyFile, caFile);
  }

  private Path writePem(String fileName, String label, byte[] derBytes) throws IOException {
    Path path = tempDir.resolve(fileName);
    Files.writeString(path, pem(label, derBytes));
    return path;
  }

  private static String pem(String label, byte[] derBytes) {
    String base64 =
        Base64.getMimeEncoder(64, System.lineSeparator().getBytes(StandardCharsets.US_ASCII))
            .encodeToString(derBytes);
    return "-----BEGIN "
        + label
        + "-----"
        + System.lineSeparator()
        + base64
        + System.lineSeparator()
        + "-----END "
        + label
        + "-----"
        + System.lineSeparator();
  }

  private static KeyPair generateRsaKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }
}
