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
 * The human-operator flow: an {@code OPERATOR_CLIENT} CSR sits {@code PENDING} until an existing
 * operator explicitly approves it -- proving the *default* behavior is "wait," not "sign," which is
 * the property that actually matters here. Plus the negative case: an approval attempt from a
 * caller without a currently-valid operator certificate of its own must be rejected.
 */
// System.setProperty mutates a JVM-global; excludes this class from running concurrently with
// any other class holding the same lock, under class-level parallel execution (root pom.xml).
@ResourceLock(Resources.SYSTEM_PROPERTIES)
// Real ApiServer + real HttpClient on a loopback ephemeral port (see ApiServerTest for why):
// excluded from running concurrently with any other class doing the same.
@ResourceLock("gimle-controlplane-api-server-http")
class HumanOperatorCsrTest {

  private static final String PROTOCOL_PROPERTY = "gimle.transport.protocol";
  private static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";
  private static final String KEY_FILE_PROPERTY = "gimle.tls.keyFile";
  private static final String CA_FILE_PROPERTY = "gimle.tls.caFile";
  private static final String CA_KEY_FILE_PROPERTY = "gimle.pki.caKeyFile";

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
  void operator_csr_sits_pending_until_an_existing_operator_approves_it() throws Exception {
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

      HttpClient trustOnlyClient = trustOnlyClient();
      KeyPair newOperatorKeyPair = generateRsaKeyPair();
      PKCS10CertificationRequest csr =
          CertificateSigningRequests.generate(newOperatorKeyPair, new X500Name("CN=new-operator"));

      Map<String, Object> submitResult = submitOperatorCsr(trustOnlyClient, baseUrl, csr, 202);
      assertEquals("PENDING", submitResult.get("status"));
      String requestId = (String) submitResult.get("requestId");
      assertNotNull(requestId);

      // Still pending before approval.
      Map<String, Object> statusBeforeApproval = pollStatus(trustOnlyClient, baseUrl, requestId);
      assertEquals("PENDING", statusBeforeApproval.get("status"));

      // Approve using an already-trusted existing operator's own certificate.
      HttpClient existingOperatorClient = mutualTlsClient(ca, "existing-operator");
      HttpRequest approveRequest =
          HttpRequest.newBuilder(URI.create(baseUrl + "/bootstrap/csr/" + requestId + "/approve"))
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      HttpResponse<String> approveResponse =
          existingOperatorClient.send(
              approveRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, approveResponse.statusCode());
      Map<String, Object> approveResult = Json.asObject(Json.parse(approveResponse.body()));
      assertEquals("APPROVED", approveResult.get("status"));
      assertNotNull(approveResult.get("certificatePem"));

      // Polling now reflects approval.
      Map<String, Object> statusAfterApproval = pollStatus(trustOnlyClient, baseUrl, requestId);
      assertEquals("APPROVED", statusAfterApproval.get("status"));
      assertNotNull(statusAfterApproval.get("certificatePem"));
    }
  }

  @Test
  void approve_without_a_client_certificate_is_rejected() throws Exception {
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

      HttpClient trustOnlyClient = trustOnlyClient();
      KeyPair newOperatorKeyPair = generateRsaKeyPair();
      PKCS10CertificationRequest csr =
          CertificateSigningRequests.generate(
              newOperatorKeyPair, new X500Name("CN=another-operator"));
      Map<String, Object> submitResult = submitOperatorCsr(trustOnlyClient, baseUrl, csr, 202);
      String requestId = (String) submitResult.get("requestId");

      HttpRequest approveRequest =
          HttpRequest.newBuilder(URI.create(baseUrl + "/bootstrap/csr/" + requestId + "/approve"))
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      HttpResponse<String> approveResponse =
          trustOnlyClient.send(
              approveRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(401, approveResponse.statusCode());
    }
  }

  private static Map<String, Object> submitOperatorCsr(
      HttpClient client, String baseUrl, PKCS10CertificationRequest csr, int expectedStatus)
      throws IOException, InterruptedException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("purpose", "OPERATOR_CLIENT");
    body.put("csrPem", Pem.encodeCsr(csr));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/bootstrap/csr"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response =
        client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(expectedStatus, response.statusCode());
    return Json.asObject(Json.parse(response.body()));
  }

  private static Map<String, Object> pollStatus(HttpClient client, String baseUrl, String requestId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/bootstrap/csr/" + requestId)).GET().build();
    HttpResponse<String> response =
        client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, response.statusCode());
    return Json.asObject(Json.parse(response.body()));
  }

  private HttpClient mutualTlsClient(CertificateAuthority ca, String commonName) throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    // O=gimle:operators: this is the "existing operator" whose certificate authorizes the
    // /approve call (CERTIFICATE_REQUEST:APPROVE, granted by the built-in cluster-admin binding)
    // -- a bare CN=, as a real /bootstrap/csr flow would never produce post-RBAC, would resolve
    // to a Principal with no group and be denied.
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
