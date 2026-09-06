package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.authz.BuiltinRoles;
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
import java.util.List;
import java.util.Map;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * The half of request idempotency that only exists once requests have real, distinct identities: a
 * request id is opaque and chosen by its own caller, so holding one must never be enough to read
 * back what somebody else's request was answered with.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerRequestIdempotencyAuthzTest {

  private static final String REQUEST_ID_HEADER = "X-Gimle-Request-Id";
  private static final String REPLAYED_HEADER = "X-Gimle-Replayed";
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
  void a_different_principal_presenting_the_same_request_id_does_not_get_the_first_outcome()
      throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
        InProcessFafnir inProcessFafnir =
            InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      HttpClient alice = mutualTlsClient(ca, "O=" + BuiltinRoles.GROUP_OPERATORS + ",CN=alice");
      HttpClient bob = mutualTlsClient(ca, "O=" + BuiltinRoles.GROUP_OPERATORS + ",CN=bob");

      assertEquals(200, putDeployment(alice, baseUrl, "orders", "1.0.0").statusCode());
      assertEquals(200, putDeployment(alice, baseUrl, "orders", "2.0.0").statusCode());

      String sharedRequestId = "req-shared-0001";
      HttpResponse<String> aliceRollback = rollback(alice, baseUrl, "orders", sharedRequestId);
      assertEquals(200, aliceRollback.statusCode());
      assertEquals(3, revisions(alice, baseUrl, "orders").size());

      HttpResponse<String> bobRollback = rollback(bob, baseUrl, "orders", sharedRequestId);

      assertEquals(200, bobRollback.statusCode());
      assertTrue(
          bobRollback.headers().firstValue(REPLAYED_HEADER).isEmpty(),
          "another caller's receipt must read as a miss, never as a replay");
      assertEquals(
          4,
          revisions(alice, baseUrl, "orders").size(),
          "bob's request must have executed on its own rather than replaying alice's outcome");

      // Alice's own retry still replays -- the mismatch above is about identity, not about the
      // receipt having been consumed or invalidated by bob's request.
      HttpResponse<String> aliceReplay = rollback(alice, baseUrl, "orders", sharedRequestId);
      assertEquals("true", aliceReplay.headers().firstValue(REPLAYED_HEADER).orElse(null));
      assertEquals(aliceRollback.body(), aliceReplay.body());
      assertEquals(4, revisions(alice, baseUrl, "orders").size());
    }
  }

  private static String deploymentYaml(String name, String version) {
    return """
        kind: Deployment
        name: %s
        module:
          name: com.gimle.example.orders
          version: %s
        artifactPath: /var/gimle/artifacts/orders-%s.jar
        replicas: 1
        """
        .formatted(name, version, version);
  }

  private static HttpResponse<String> putDeployment(
      HttpClient client, String baseUrl, String name, String version) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name))
            .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml(name, version)))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> rollback(
      HttpClient client, String baseUrl, String name, String requestId) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name + "/rollback"))
            .header(REQUEST_ID_HEADER, requestId)
            .POST(HttpRequest.BodyPublishers.ofString(""))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static List<Map<String, Object>> revisions(HttpClient client, String baseUrl, String name)
      throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name + "/revisions"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, response.statusCode());
    return Json.asObjectList(Json.asObject(Json.parse(response.body())).get("revisions"));
  }

  private HttpClient mutualTlsClient(CertificateAuthority ca, String subject) throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(keyPair, new X500Name(subject));
    String safeName = subject.replaceAll("[^a-zA-Z0-9]", "_");
    Path certFile =
        writePem(
            safeName + "-cert.pem",
            Pem.encodeCertificate(ca.signCertificateRequest(csr, Duration.ofDays(1))));
    Path keyFile = writePem(safeName + "-key.pem", Pem.encodePrivateKey(keyPair.getPrivate()));
    return HttpClient.newBuilder()
        .sslContext(SslContexts.forMutualTls(new TlsSettings(certFile, keyFile, caFile)))
        .build();
  }

  private void configureServerTls(CertificateAuthority ca) throws Exception {
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
