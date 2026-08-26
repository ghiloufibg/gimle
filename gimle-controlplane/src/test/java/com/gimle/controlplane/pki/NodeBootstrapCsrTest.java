package com.gimle.controlplane.pki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.gimle.controlplane.api.ApiServer;
import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.protocol.Json;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.CertificateSigningRequests;
import com.gimle.pki.Pem;
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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * The node-join flow end to end: a brand-new agent with no pre-provisioned certificate, given only
 * a bootstrap token and the CA cert, ends up with a real CA-signed certificate and can complete a
 * full mTLS handshake against the control plane afterward. Plus the negative cases that matter
 * here: an invalid token is rejected, and a token already consumed once cannot be replayed into
 * minting a second certificate.
 */
// System.setProperty mutates a JVM-global; excludes this class from running concurrently with
// any other class holding the same lock, under class-level parallel execution (root pom.xml).
@ResourceLock(Resources.SYSTEM_PROPERTIES)
// Real ApiServer + real HttpClient on a loopback ephemeral port (see ApiServerTest for why):
// excluded from running concurrently with any other class doing the same.
@ResourceLock("gimle-controlplane-api-server-http")
class NodeBootstrapCsrTest {

  private static final String PROTOCOL_PROPERTY = "gimle.transport.protocol";
  private static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";
  private static final String KEY_FILE_PROPERTY = "gimle.tls.keyFile";
  private static final String CA_FILE_PROPERTY = "gimle.tls.caFile";
  private static final String CA_KEY_FILE_PROPERTY = "gimle.tls.caKeyFile";

  @TempDir(cleanup = CleanupMode.NEVER)
  private Path tempDir;

  private Path caFile;

  @AfterEach
  void clearTransportProperties() {
    System.clearProperty(PROTOCOL_PROPERTY);
    System.clearProperty(CERT_FILE_PROPERTY);
    System.clearProperty(KEY_FILE_PROPERTY);
    System.clearProperty(CA_FILE_PROPERTY);
    System.clearProperty(CA_KEY_FILE_PROPERTY);
  }

  @Test
  void fresh_agent_obtains_a_signed_certificate_and_completes_mtls_handshake() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    InProcessFafnir inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    try (inProcessStore;
        inProcessFafnir;
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();

      HttpClient operatorClient = mutualTlsClient(ca, "existing-operator");
      String token = issueBootstrapToken(operatorClient, baseUrl);

      KeyPair agentKeyPair = generateRsaKeyPair();
      PKCS10CertificationRequest csr =
          CertificateSigningRequests.generate(
              agentKeyPair, new X500Name("CN=node-1"), List.of("localhost"));
      HttpClient trustOnlyClient = trustOnlyClient();

      Map<String, Object> result = submitCsr(trustOnlyClient, baseUrl, "NODE_CLIENT", csr, token);
      assertEquals("APPROVED", result.get("status"));
      String certPem = (String) result.get("certificatePem");
      assertNotNull(certPem);

      Path issuedCertFile = writePem("node-1-cert.pem", certPem);
      Path issuedKeyFile =
          writePem("node-1-key.pem", Pem.encodePrivateKey(agentKeyPair.getPrivate()));
      TlsSettings issuedSettings = new TlsSettings(issuedCertFile, issuedKeyFile, caFile);
      HttpClient issuedClient =
          HttpClient.newBuilder().sslContext(SslContexts.forMutualTls(issuedSettings)).build();
      // Its own /nodes/{nodeId}/assignments, not the operator-only /nodes list -- the freshly
      // issued certificate carries O=gimle:nodes (stamped server-side at issuance), which only
      // grants self-service access to node-1's own subresources, not the cluster-wide node list.
      // This is a more faithful proof of "usable for what a node actually needs to do" than the
      // list endpoint this test used pre-RBAC.
      HttpRequest nodesRequest =
          HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-1/assignments")).GET().build();
      HttpResponse<String> nodesResponse =
          issuedClient.send(
              nodesRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, nodesResponse.statusCode());

      // Negative: the same (now-consumed) token cannot be replayed for a second certificate.
      KeyPair secondKeyPair = generateRsaKeyPair();
      PKCS10CertificationRequest secondCsr =
          CertificateSigningRequests.generate(secondKeyPair, new X500Name("CN=node-2"));
      HttpResponse<String> secondResponse =
          rawSubmitCsr(trustOnlyClient, baseUrl, "NODE_CLIENT", secondCsr, token);
      assertEquals(401, secondResponse.statusCode());
    }
  }

  @Test
  void invalid_bootstrap_token_is_rejected() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    InProcessFafnir inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    try (inProcessStore;
        inProcessFafnir;
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();

      KeyPair agentKeyPair = generateRsaKeyPair();
      PKCS10CertificationRequest csr =
          CertificateSigningRequests.generate(agentKeyPair, new X500Name("CN=node-1"));
      HttpClient trustOnlyClient = trustOnlyClient();

      HttpResponse<String> response =
          rawSubmitCsr(trustOnlyClient, baseUrl, "NODE_CLIENT", csr, "not-a-real-token");
      assertEquals(401, response.statusCode());
    }
  }

  private static String issueBootstrapToken(HttpClient operatorClient, String baseUrl)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/bootstrap/tokens"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"ttlSeconds\":3600}"))
            .build();
    HttpResponse<String> response =
        operatorClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, response.statusCode());
    return (String) Json.asObject(Json.parse(response.body())).get("token");
  }

  private static Map<String, Object> submitCsr(
      HttpClient client,
      String baseUrl,
      String purpose,
      PKCS10CertificationRequest csr,
      String bootstrapToken)
      throws IOException, InterruptedException {
    HttpResponse<String> response = rawSubmitCsr(client, baseUrl, purpose, csr, bootstrapToken);
    assertEquals(200, response.statusCode());
    return Json.asObject(Json.parse(response.body()));
  }

  private static HttpResponse<String> rawSubmitCsr(
      HttpClient client,
      String baseUrl,
      String purpose,
      PKCS10CertificationRequest csr,
      String bootstrapToken)
      throws IOException, InterruptedException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("purpose", purpose);
    body.put("csrPem", Pem.encodeCsr(csr));
    if (bootstrapToken != null) {
      body.put("bootstrapToken", bootstrapToken);
    }
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/bootstrap/csr"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpClient mutualTlsClient(CertificateAuthority ca, String commonName) throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    // O=gimle:operators: this is the "existing operator" whose certificate authorizes issuing a
    // bootstrap token (BOOTSTRAP_TOKEN:WRITE, granted by the built-in cluster-admin binding) -- a
    // bare CN=, as a real /bootstrap/csr flow would never produce post-RBAC, would resolve to a
    // Principal with no group and be denied.
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(
            keyPair, new X500Name("O=gimle:operators,CN=" + commonName));
    Path certFile =
        writePem(
            commonName + "-cert.pem",
            Pem.encodeCertificate(ca.signCertificateRequest(csr, Duration.ofDays(1))));
    Path keyFile = writePem(commonName + "-key.pem", Pem.encodePrivateKey(keyPair.getPrivate()));
    TlsSettings settings = new TlsSettings(certFile, keyFile, caFile);
    return HttpClient.newBuilder().sslContext(SslContexts.forMutualTls(settings)).build();
  }

  private HttpClient trustOnlyClient() {
    SSLContext context = SslContexts.forServerTrustOnly(caFile);
    return HttpClient.newBuilder().sslContext(context).build();
  }

  private void configureServerTls(CertificateAuthority ca) throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(
            keyPair, new X500Name("CN=controlplane"), List.of("localhost"));
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
  }

  private Path writePem(String fileName, String pem) throws IOException {
    Path path = tempDir.resolve(fileName);
    Files.writeString(path, pem);
    return path;
  }

  private static KeyPair generateRsaKeyPair() throws NoSuchAlgorithmException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }
}
