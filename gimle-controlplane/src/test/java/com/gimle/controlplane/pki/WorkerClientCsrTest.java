package com.gimle.controlplane.pki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.gimle.controlplane.api.ApiServer;
import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.authz.BuiltinRoles;
import com.gimle.core.authz.Principal;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.Json;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.CertificateSigningRequests;
import com.gimle.pki.Pem;
import com.gimle.pki.Subjects;
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
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * The worker-identity issuance flow end to end: a node agent, over its own {@code gimle:nodes}
 * certificate, obtains a certificate for a worker it is about to spawn -- stamped {@code
 * O=gimle:workers} plus the one tenant that node actually holds an assignment for -- and every way
 * that grant is refused: a tenant the node was never assigned, a subject naming another node's
 * worker, a caller that isn't a node at all, and a caller presenting no certificate.
 */
// System.setProperty mutates a JVM-global; excludes this class from running concurrently with
// any other class holding the same lock, under class-level parallel execution (root pom.xml).
@ResourceLock(Resources.SYSTEM_PROPERTIES)
// Real ApiServer + real HttpClient on a loopback ephemeral port (see ApiServerTest for why):
// excluded from running concurrently with any other class doing the same.
@ResourceLock("gimle-controlplane-api-server-http")
class WorkerClientCsrTest {

  private static final String PROTOCOL_PROPERTY = "gimle.transport.protocol";
  private static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";
  private static final String KEY_FILE_PROPERTY = "gimle.tls.keyFile";
  private static final String CA_FILE_PROPERTY = "gimle.tls.caFile";
  private static final String CA_KEY_FILE_PROPERTY = "gimle.tls.caKeyFile";
  private static final ModuleId ORDERS = new ModuleId("com.acme.orders", Version.parse("1.0.0"));

  @TempDir(cleanup = CleanupMode.NEVER)
  private Path tempDir;

  private Path caFile;
  private int fileCounter;

  @AfterEach
  void clearTransportProperties() {
    System.clearProperty(PROTOCOL_PROPERTY);
    System.clearProperty(CERT_FILE_PROPERTY);
    System.clearProperty(KEY_FILE_PROPERTY);
    System.clearProperty(CA_FILE_PROPERTY);
    System.clearProperty(CA_KEY_FILE_PROPERTY);
  }

  @Test
  void a_node_obtains_a_worker_certificate_for_a_tenant_it_holds_an_assignment_for()
      throws Exception {
    CertificateAuthority ca = startTls();
    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"));
        InProcessFafnir fafnir = InProcessFafnir.start(store.client(), tempDir.resolve("k/s.key"));
        ApiServer server = new ApiServer(store.client(), 0, fafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      assignToNode(store, "acme", "node-1");
      HttpClient node = mutualTlsClient(ca, "node-1", BuiltinRoles.GROUP_NODES);

      KeyPair workerKeyPair = generateRsaKeyPair();
      PKCS10CertificationRequest csr =
          CertificateSigningRequests.generate(
              workerKeyPair, new X500Name("O=gimle:nodes,CN=node-1:orders#0"));
      HttpResponse<String> response = submit(node, baseUrl, csr, Optional.of("acme"));

      assertEquals(200, response.statusCode(), response.body());
      Map<String, Object> result = Json.asObject(Json.parse(response.body()));
      assertEquals("APPROVED", result.get("status"));
      X509Certificate issued = Pem.decodeCertificate((String) result.get("certificatePem"));
      Principal worker = Subjects.principalFrom(issued);
      assertEquals("node-1:orders#0", worker.name());
      // Stamped server-side: the CSR's own O=gimle:nodes never survives into the certificate.
      assertEquals(
          Set.of(BuiltinRoles.GROUP_WORKERS, BuiltinRoles.tenantGroup("acme")), worker.groups());

      // A worker's identity is not a node's: the node self-service surface refuses it.
      TlsSettings workerSettings =
          new TlsSettings(
              writePem("worker-cert.pem", Pem.encodeCertificate(issued)),
              writePem("worker-key.pem", Pem.encodePrivateKey(workerKeyPair.getPrivate())),
              caFile);
      HttpClient asWorker =
          HttpClient.newBuilder().sslContext(SslContexts.forMutualTls(workerSettings)).build();
      HttpResponse<String> asNode =
          asWorker.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-1/assignments"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertNotEquals(200, asNode.statusCode());
    }
  }

  @Test
  void a_tenant_the_node_holds_no_assignment_for_is_refused() throws Exception {
    CertificateAuthority ca = startTls();
    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"));
        InProcessFafnir fafnir = InProcessFafnir.start(store.client(), tempDir.resolve("k/s.key"));
        ApiServer server = new ApiServer(store.client(), 0, fafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      assignToNode(store, "acme", "node-2"); // acme runs on node-2, never on node-1
      HttpClient node = mutualTlsClient(ca, "node-1", BuiltinRoles.GROUP_NODES);

      HttpResponse<String> response =
          submit(node, baseUrl, workerCsr("node-1:orders#0"), Optional.of("acme"));

      assertEquals(403, response.statusCode(), response.body());
    }
  }

  @Test
  void a_subject_naming_another_nodes_worker_is_refused() throws Exception {
    CertificateAuthority ca = startTls();
    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"));
        InProcessFafnir fafnir = InProcessFafnir.start(store.client(), tempDir.resolve("k/s.key"));
        ApiServer server = new ApiServer(store.client(), 0, fafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      assignToNode(store, "acme", "node-1");
      HttpClient node = mutualTlsClient(ca, "node-1", BuiltinRoles.GROUP_NODES);

      HttpResponse<String> response =
          submit(node, baseUrl, workerCsr("node-2:orders#0"), Optional.of("acme"));

      assertEquals(403, response.statusCode(), response.body());
    }
  }

  @Test
  void a_caller_that_is_not_a_node_is_refused() throws Exception {
    CertificateAuthority ca = startTls();
    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"));
        InProcessFafnir fafnir = InProcessFafnir.start(store.client(), tempDir.resolve("k/s.key"));
        ApiServer server = new ApiServer(store.client(), 0, fafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      assignToNode(store, "acme", "node-1");
      // Even a cluster operator: worker identities are only ever minted by the node spawning them.
      HttpClient operator = mutualTlsClient(ca, "node-1", BuiltinRoles.GROUP_OPERATORS);

      HttpResponse<String> response =
          submit(operator, baseUrl, workerCsr("node-1:orders#0"), Optional.of("acme"));

      assertEquals(403, response.statusCode(), response.body());
    }
  }

  @Test
  void a_caller_presenting_no_certificate_is_refused() throws Exception {
    startTls();
    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"));
        InProcessFafnir fafnir = InProcessFafnir.start(store.client(), tempDir.resolve("k/s.key"));
        ApiServer server = new ApiServer(store.client(), 0, fafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      HttpClient anonymous =
          HttpClient.newBuilder().sslContext(SslContexts.forServerTrustOnly(caFile)).build();

      HttpResponse<String> response =
          submit(anonymous, baseUrl, workerCsr("node-1:orders#0"), Optional.of("acme"));

      assertEquals(401, response.statusCode(), response.body());
    }
  }

  @Test
  void an_untenanted_worker_gets_the_workers_group_and_no_tenant() throws Exception {
    CertificateAuthority ca = startTls();
    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"));
        InProcessFafnir fafnir = InProcessFafnir.start(store.client(), tempDir.resolve("k/s.key"));
        ApiServer server = new ApiServer(store.client(), 0, fafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      HttpClient node = mutualTlsClient(ca, "node-1", BuiltinRoles.GROUP_NODES);

      HttpResponse<String> response =
          submit(node, baseUrl, workerCsr("node-1:platform#0"), Optional.empty());

      assertEquals(200, response.statusCode(), response.body());
      Map<String, Object> result = Json.asObject(Json.parse(response.body()));
      X509Certificate issued = Pem.decodeCertificate((String) result.get("certificatePem"));
      assertEquals(Set.of(BuiltinRoles.GROUP_WORKERS), Subjects.principalFrom(issued).groups());
    }
  }

  private static void assignToNode(InProcessStore store, String tenantId, String nodeId) {
    store
        .client()
        .propose(
            new StateMutation.PutDeployment(
                new DeploymentSpec(
                    "orders",
                    ORDERS,
                    "/artifacts/orders.jar",
                    1,
                    PlacementConstraints.NONE,
                    Optional.empty(),
                    Optional.of(tenantId)),
                0));
    store
        .client()
        .propose(
            new StateMutation.PutAssignment(
                new InstanceAssignment(
                    "orders",
                    0,
                    nodeId,
                    ORDERS,
                    "/artifacts/orders.jar",
                    OptionalInt.empty(),
                    Optional.of(tenantId))));
  }

  private static PKCS10CertificationRequest workerCsr(String commonName)
      throws NoSuchAlgorithmException {
    return CertificateSigningRequests.generate(
        generateRsaKeyPair(), new X500Name("CN=" + commonName));
  }

  private static HttpResponse<String> submit(
      HttpClient client, String baseUrl, PKCS10CertificationRequest csr, Optional<String> tenantId)
      throws IOException, InterruptedException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("purpose", "WORKER_CLIENT");
    body.put("csrPem", Pem.encodeCsr(csr));
    tenantId.ifPresent(id -> body.put("tenantId", id));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/bootstrap/csr"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpClient mutualTlsClient(CertificateAuthority ca, String commonName, String group)
      throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(
            keyPair, new X500Name("O=" + group + ",CN=" + commonName));
    Path certFile =
        writePem(
            "client-cert.pem",
            Pem.encodeCertificate(ca.signCertificateRequest(csr, Duration.ofDays(1))));
    Path keyFile = writePem("client-key.pem", Pem.encodePrivateKey(keyPair.getPrivate()));
    TlsSettings settings = new TlsSettings(certFile, keyFile, caFile);
    return HttpClient.newBuilder().sslContext(SslContexts.forMutualTls(settings)).build();
  }

  private CertificateAuthority startTls() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(
            keyPair,
            new X500Name("O=" + BuiltinRoles.GROUP_CONTROLPLANE + ",CN=controlplane"),
            List.of("localhost"));
    Path certFile =
        writePem(
            "controlplane-cert.pem",
            Pem.encodeCertificate(ca.signCertificateRequest(csr, Duration.ofDays(1))));
    Path keyFile = writePem("controlplane-key.pem", Pem.encodePrivateKey(keyPair.getPrivate()));
    caFile = writePem("test-ca.pem", Pem.encodeCertificate(ca.certificate()));
    Path caKeyFile = writePem("test-ca-key.pem", Pem.encodePrivateKey(ca.privateKey()));
    System.setProperty(PROTOCOL_PROPERTY, "tls");
    System.setProperty(CERT_FILE_PROPERTY, certFile.toString());
    System.setProperty(KEY_FILE_PROPERTY, keyFile.toString());
    System.setProperty(CA_FILE_PROPERTY, caFile.toString());
    System.setProperty(CA_KEY_FILE_PROPERTY, caKeyFile.toString());
    return ca;
  }

  private Path writePem(String fileName, String pem) throws IOException {
    Path path = tempDir.resolve((fileCounter++) + "-" + fileName);
    Files.writeString(path, pem);
    return path;
  }

  private static KeyPair generateRsaKeyPair() throws NoSuchAlgorithmException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }
}
