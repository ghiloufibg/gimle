package com.gimle.controlplane.pki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
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
 * §4b's rotation flow: a component holding a still-valid certificate requests rotation over mTLS
 * (no bootstrap token involved), receives a new certificate for the *same* Subject with a new key
 * pair and a later-or-equal expiry, and it's immediately usable for a fresh mTLS handshake. Plus
 * the negative case that actually matters for rotation specifically: a CSR whose Subject does not
 * match the authenticating certificate's own Subject must be rejected -- auto-approval here is only
 * safe because it can't be used to request a *different* identity than the one already proven.
 */
// System.setProperty mutates a JVM-global; excludes this class from running concurrently with
// any other class holding the same lock, under class-level parallel execution (root pom.xml).
@ResourceLock(Resources.SYSTEM_PROPERTIES)
// Real ApiServer + real HttpClient on a loopback ephemeral port (see ApiServerTest for why):
// excluded from running concurrently with any other class doing the same.
@ResourceLock("gimle-controlplane-api-server-http")
class CertificateRotationTest {

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
  void rotation_issues_a_new_cert_for_the_same_subject_and_it_works_immediately() throws Exception {
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

      // O=gimle:operators on both the original and the rotation CSR: the rotation subject-match
      // check requires them identical, and this is what proves rotation carries group membership
      // forward unchanged (a bare CN=, as a real /bootstrap/csr flow would never produce
      // post-RBAC, would resolve to a Principal with no group and be denied at the final /nodes
      // check below).
      KeyPair oldKeyPair = generateRsaKeyPair();
      PKCS10CertificationRequest oldCsr =
          CertificateSigningRequests.generate(
              oldKeyPair, new X500Name("O=gimle:operators,CN=operator-1"));
      X509Certificate oldCert = ca.signCertificateRequest(oldCsr, Duration.ofDays(1));
      Path oldCertFile = writePem("old-cert.pem", Pem.encodeCertificate(oldCert));
      Path oldKeyFile = writePem("old-key.pem", Pem.encodePrivateKey(oldKeyPair.getPrivate()));
      TlsSettings oldSettings = new TlsSettings(oldCertFile, oldKeyFile, caFile);
      HttpClient oldClient =
          HttpClient.newBuilder().sslContext(SslContexts.forMutualTls(oldSettings)).build();

      KeyPair newKeyPair = generateRsaKeyPair();
      PKCS10CertificationRequest rotationCsr =
          CertificateSigningRequests.generate(
              newKeyPair, new X500Name("O=gimle:operators,CN=operator-1"));
      Map<String, Object> result = submitCsr(oldClient, baseUrl, rotationCsr, 200);
      assertEquals("APPROVED", result.get("status"));
      String newCertPem = (String) result.get("certificatePem");
      assertNotNull(newCertPem);

      X509Certificate newCert = Pem.decodeCertificate(newCertPem);
      assertNotEquals(oldCert.getSerialNumber(), newCert.getSerialNumber());
      assertEquals(oldCert.getSubjectX500Principal(), newCert.getSubjectX500Principal());

      Path newCertFile = writePem("new-cert.pem", newCertPem);
      Path newKeyFile = writePem("new-key.pem", Pem.encodePrivateKey(newKeyPair.getPrivate()));
      TlsSettings newSettings = new TlsSettings(newCertFile, newKeyFile, caFile);
      HttpClient newClient =
          HttpClient.newBuilder().sslContext(SslContexts.forMutualTls(newSettings)).build();
      HttpRequest nodesRequest =
          HttpRequest.newBuilder(URI.create(baseUrl + "/nodes")).GET().build();
      HttpResponse<String> nodesResponse =
          newClient.send(nodesRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, nodesResponse.statusCode());
    }
  }

  @Test
  void rotation_csr_with_a_mismatched_subject_is_rejected() throws Exception {
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

      KeyPair keyPair = generateRsaKeyPair();
      PKCS10CertificationRequest csr =
          CertificateSigningRequests.generate(keyPair, new X500Name("CN=operator-1"));
      X509Certificate cert = ca.signCertificateRequest(csr, Duration.ofDays(1));
      Path certFile = writePem("operator-cert.pem", Pem.encodeCertificate(cert));
      Path keyFile = writePem("operator-key.pem", Pem.encodePrivateKey(keyPair.getPrivate()));
      TlsSettings settings = new TlsSettings(certFile, keyFile, caFile);
      HttpClient client =
          HttpClient.newBuilder().sslContext(SslContexts.forMutualTls(settings)).build();

      KeyPair mismatchedKeyPair = generateRsaKeyPair();
      PKCS10CertificationRequest mismatchedCsr =
          CertificateSigningRequests.generate(mismatchedKeyPair, new X500Name("CN=someone-else"));

      HttpResponse<String> response = rawSubmitCsr(client, baseUrl, mismatchedCsr);
      assertEquals(403, response.statusCode());
    }
  }

  private static Map<String, Object> submitCsr(
      HttpClient client, String baseUrl, PKCS10CertificationRequest csr, int expectedStatus)
      throws IOException, InterruptedException {
    HttpResponse<String> response = rawSubmitCsr(client, baseUrl, csr);
    assertEquals(expectedStatus, response.statusCode());
    return Json.asObject(Json.parse(response.body()));
  }

  private static HttpResponse<String> rawSubmitCsr(
      HttpClient client, String baseUrl, PKCS10CertificationRequest csr)
      throws IOException, InterruptedException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("purpose", "OPERATOR_CLIENT");
    body.put("csrPem", Pem.encodeCsr(csr));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/bootstrap/csr"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
