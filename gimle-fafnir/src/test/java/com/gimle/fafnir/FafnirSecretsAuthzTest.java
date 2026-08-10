package com.gimle.fafnir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.authz.Permission;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Role;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.authz.Verb;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.fafnir.testsupport.InProcessStore;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.StateStore;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.CertificateSigningRequests;
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
import java.util.Optional;
import java.util.Set;
import javax.net.ssl.SSLContext;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Design doc §9's corrected defense-in-depth: Fafnir runs its own, independent {@code
 * Authorizer.authorize(...)} check on {@code /secrets/*} rather than trusting "this request arrived
 * already-forwarded by gimle-controlplane" as proof of authorization by itself. Every test here
 * talks to {@link FafnirServer} directly over real mTLS -- no {@code ApiServer} in the loop -- to
 * prove Fafnir's own gate works standalone, including the specific scenario a buggy or compromised
 * proxy would exploit: a forwarded-principal header naming someone who does not actually hold the
 * permission.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-fafnir-server-http")
class FafnirSecretsAuthzTest {

  private static final String PROTOCOL_PROPERTY = "gimle.transport.protocol";
  private static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";
  private static final String KEY_FILE_PROPERTY = "gimle.tls.keyFile";
  private static final String CA_FILE_PROPERTY = "gimle.tls.caFile";
  private static final String FORWARDED_PRINCIPAL_HEADER = "X-Gimle-Forwarded-Principal";

  @TempDir Path tempDir;

  @AfterEach
  void clearTransportProperties() {
    System.clearProperty(PROTOCOL_PROPERTY);
    System.clearProperty(CERT_FILE_PROPERTY);
    System.clearProperty(KEY_FILE_PROPERTY);
    System.clearProperty(CA_FILE_PROPERTY);
  }

  @Test
  @Timeout(10)
  void a_caller_whose_own_certificate_holds_no_secret_permission_is_forbidden() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      // No Role/RoleBinding granted to "caller" at all -- an authenticated, CA-signed identity
      // with zero RBAC permissions, the plain "not authorized" case.
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = clientWithLeaf(ca, "caller");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/acme"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(403, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void a_caller_whose_own_certificate_holds_the_permission_succeeds() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      grantSecretReadAndWrite(inProcessStore.store(), "caller");
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = clientWithLeaf(ca, "caller");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/acme"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void a_forwarded_principal_who_actually_holds_the_permission_succeeds() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      grantSecretReadAndWrite(inProcessStore.store(), "alice");
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        // "proxy" here presents its own cert (an identity with no SECRET permission of its own)
        // but forwards a claim naming "alice", the real, authorized principal -- exactly the
        // shape gimle-controlplane's own proxy hop uses.
        HttpClient client = clientWithLeaf(ca, "proxy");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/acme"))
                    .header(FORWARDED_PRINCIPAL_HEADER, "alice")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void a_forwarded_principal_who_does_not_actually_hold_the_permission_is_still_forbidden()
      throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      // "mallory" is never granted anything -- a buggy or compromised proxy claims to be
      // forwarding an authorized request on her behalf; Fafnir's own independent RBAC read still
      // finds no grant and denies it, proving the proxy's own (missing, in this test) authz check
      // is not what's actually protecting this endpoint (design doc §9's corrected
      // defense-in-depth: never trust "arrived already-forwarded" as proof by itself).
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = clientWithLeaf(ca, "proxy");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/acme"))
                    .header(FORWARDED_PRINCIPAL_HEADER, "mallory")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(403, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void an_unauthenticated_plaintext_request_is_allowed_matching_every_other_gimle_process()
      throws Exception {
    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/secrets/acme"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void a_node_with_an_active_assignment_for_the_tenant_may_read_its_secrets() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      assignDeploymentToNode(inProcessStore.store(), "node-1", "acme");
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = nodeClientWithLeaf(ca, "node-1");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/acme"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void a_node_with_no_assignment_for_the_tenant_is_forbidden_regardless_of_key() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      // "node-1" is assigned to a deployment for a different tenant -- proves this is a genuine
      // per-tenant check, not merely "does this node have any assignment at all."
      assignDeploymentToNode(inProcessStore.store(), "node-1", "other-tenant");
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = nodeClientWithLeaf(ca, "node-1");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/acme"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(403, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void a_node_may_never_write_a_secret_even_with_an_active_assignment() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      assignDeploymentToNode(inProcessStore.store(), "node-1", "acme");
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = nodeClientWithLeaf(ca, "node-1");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create(
                            "https://localhost:" + server.port() + "/secrets/acme/db-password"))
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":\"aGVsbG8=\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(403, response.statusCode());
      }
    }
  }

  /**
   * O-4 of {@code OBSERVABILITY_AUDIT_DESIGN.md}'s Part A: allowed and denied {@code /secrets/*}
   * decisions both land in the durable audit trail alongside Fafnir's own existing SLF4J log line.
   */
  @Test
  @Timeout(10)
  void an_allowed_secret_request_is_recorded_in_the_durable_audit_trail() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      grantSecretReadAndWrite(inProcessStore.store(), "caller");
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = clientWithLeaf(ca, "caller");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/acme"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode());

        var events =
            inProcessStore
                .store()
                .listAuditEvents(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(1, events.size());
        assertEquals("caller", events.get(0).principal());
        assertEquals("SECRET", events.get(0).resourceKind());
        assertEquals("READ", events.get(0).verb());
        assertEquals(Optional.of("acme"), events.get(0).tenantId());
        assertEquals(true, events.get(0).allowed());
      }
    }
  }

  @Test
  @Timeout(10)
  void a_denied_secret_request_is_recorded_in_the_durable_audit_trail() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      // No Role/RoleBinding granted -- the plain "not authorized" case, same as this file's own
      // a_caller_whose_own_certificate_holds_no_secret_permission_is_forbidden test.
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = clientWithLeaf(ca, "caller");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/acme"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(403, response.statusCode());

        var events =
            inProcessStore
                .store()
                .listAuditEvents(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(1, events.size());
        assertEquals(false, events.get(0).allowed());
      }
    }
  }

  /**
   * The {@code GROUP_NODES} self-service branch (see {@link FafnirServer#authorizeSecrets})
   * bypasses {@code authorizer.authorize(...)} entirely, but still computes an {@code allowed}
   * boolean that belongs in the trail exactly like an ordinary caller's decision does.
   */
  @Test
  @Timeout(10)
  void a_nodes_self_service_secret_read_is_also_audited() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      assignDeploymentToNode(inProcessStore.store(), "node-1", "acme");
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = nodeClientWithLeaf(ca, "node-1");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/acme"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode());

        var events =
            inProcessStore
                .store()
                .listAuditEvents(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(1, events.size());
        assertEquals("node-1", events.get(0).principal());
        assertEquals(true, events.get(0).allowed());
      }
    }
  }

  /**
   * A single-replica deployment placed on {@code nodeId} for {@code tenantId} -- the minimal
   * scheduler-decision shape {@link FafnirServer#isTenantAssignedToNode} joins against, mirroring
   * exactly what a real {@code DeploymentReconciler} placement would have written.
   */
  private static void assignDeploymentToNode(StateStore store, String nodeId, String tenantId) {
    ModuleId moduleId = new ModuleId("com.gimle.example.orders", Version.parse("1.0.0"));
    store.putDeployment(
        new DeploymentSpec(
            "dep-" + tenantId,
            moduleId,
            "/var/gimle/artifacts/orders-1.0.0.jar",
            1,
            PlacementConstraints.NONE,
            Optional.empty(),
            Optional.of(tenantId)));
    store.putAssignment(new InstanceAssignment("dep-" + tenantId, 0, nodeId));
  }

  private static void grantSecretReadAndWrite(StateStore store, String username) {
    store.putRole(
        new Role(
            "secret-rw",
            Set.of(
                Permission.unscoped(ResourceKind.SECRET, Verb.READ),
                Permission.unscoped(ResourceKind.SECRET, Verb.WRITE),
                Permission.unscoped(ResourceKind.SECRET, Verb.DELETE))));
    store.putRoleBinding(
        new RoleBinding("b-" + username, RoleBinding.userSubject(username), "secret-rw"));
  }

  private void configureServerTls(CertificateAuthority ca) throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(
            keyPair, new X500Name("CN=fafnir"), java.util.List.of("localhost"));
    X509Certificate leaf = ca.signCertificateRequest(csr, Duration.ofDays(1));
    Path certFile = writePem("fafnir-cert.pem", "CERTIFICATE", leaf.getEncoded());
    Path keyFile = writePem("fafnir-key.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
    Path caFile = writePem("fafnir-ca.pem", "CERTIFICATE", ca.certificate().getEncoded());

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
    Path certFile = writePem(commonName + "-cert.pem", "CERTIFICATE", leaf.getEncoded());
    Path keyFile =
        writePem(commonName + "-key.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
    Path caFile = writePem(commonName + "-ca.pem", "CERTIFICATE", ca.certificate().getEncoded());

    SSLContext sslContext = SslContexts.forMutualTls(new TlsSettings(certFile, keyFile, caFile));
    return HttpClient.newBuilder().sslContext(sslContext).build();
  }

  /**
   * A leaf certificate stamped {@code O=gimle:nodes} (the server-side-only stamp real CSR issuance
   * applies, see {@code Subjects.withOrganization}) -- exercises §9's node-authorization path
   * rather than {@link #clientWithLeaf}'s ordinary-RBAC one.
   */
  private HttpClient nodeClientWithLeaf(CertificateAuthority ca, String nodeId) throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(keyPair, new X500Name("CN=" + nodeId));
    X509Certificate leaf =
        ca.signCertificateRequest(
            csr, new X500Name("O=gimle:nodes,CN=" + nodeId), Duration.ofDays(1));
    Path certFile = writePem(nodeId + "-cert.pem", "CERTIFICATE", leaf.getEncoded());
    Path keyFile = writePem(nodeId + "-key.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
    Path caFile = writePem(nodeId + "-ca.pem", "CERTIFICATE", ca.certificate().getEncoded());

    SSLContext sslContext = SslContexts.forMutualTls(new TlsSettings(certFile, keyFile, caFile));
    return HttpClient.newBuilder().sslContext(sslContext).build();
  }

  private Path writePem(String fileName, String label, byte[] derBytes) throws Exception {
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
