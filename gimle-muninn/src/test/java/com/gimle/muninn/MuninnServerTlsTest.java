package com.gimle.muninn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.protocol.Json;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
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
import java.util.Map;
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
 * Proves {@code gimle.transport.protocol=tls} actually swaps {@link MuninnServer} onto {@code
 * HttpsServer} with real mTLS -- a real HTTPS request over loopback with a real, CA-signed client
 * certificate, mirroring {@code FafnirServerTlsTest}'s own shape -- plus a real cert-rotation
 * reload.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-muninn-server-http")
class MuninnServerTlsTest {

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

  @Test
  @Timeout(10)
  void a_real_mtls_request_with_a_ca_signed_client_cert_succeeds() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);
    TlsSettings clientSettings = issueLeaf(ca, "caller");

    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"))) {
      try (MuninnServer server = new MuninnServer(store.client(), 0)) {
        server.start();
        SSLContext clientContext = SslContexts.forMutualTls(clientSettings);
        HttpClient client = HttpClient.newBuilder().sslContext(clientContext).build();
        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + server.port() + "/status"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode());
        Map<String, Object> body = Json.asObject(Json.parse(response.body()));
        assertEquals("TLS", body.get("transportProtocol"));
      }
    }
  }

  @Test
  @Timeout(10)
  void reloading_tls_material_lets_a_fresh_connection_succeed_without_restarting_the_server()
      throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);
    Path certFile = Path.of(System.getProperty(CERT_FILE_PROPERTY));
    Path keyFile = Path.of(System.getProperty(KEY_FILE_PROPERTY));
    Path caFile = Path.of(System.getProperty(CA_FILE_PROPERTY));

    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"))) {
      try (MuninnServer server = new MuninnServer(store.client(), 0)) {
        server.start();
        TlsSettings clientSettings = new TlsSettings(certFile, keyFile, caFile);
        HttpClient before =
            HttpClient.newBuilder().sslContext(SslContexts.forMutualTls(clientSettings)).build();
        HttpResponse<String> beforeResponse =
            before.send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + server.port() + "/status"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, beforeResponse.statusCode());

        // Rotate: a fresh CA-signed leaf, written over the *same* cert/key file paths -- exactly
        // what a real rotation does to gimle.tls.certFile/keyFile in place.
        KeyPair rotatedKeyPair = generateRsaKeyPair();
        PKCS10CertificationRequest rotatedCsr =
            CertificateSigningRequests.generate(
                rotatedKeyPair, new X500Name("CN=muninn"), List.of("localhost"));
        X509Certificate rotatedLeaf = ca.signCertificateRequest(rotatedCsr, Duration.ofDays(1));
        overwritePem(certFile, "CERTIFICATE", rotatedLeaf.getEncoded());
        overwritePem(keyFile, "PRIVATE KEY", rotatedKeyPair.getPrivate().getEncoded());

        server.reloadTlsMaterial();

        // A brand-new connection (not the already-established one from before rotation) must
        // succeed against the reloaded listener, at the *same* port, without restarting the
        // process -- if reload hadn't rebound there, this request would simply fail.
        HttpClient after =
            HttpClient.newBuilder().sslContext(SslContexts.forMutualTls(clientSettings)).build();
        HttpResponse<String> afterResponse =
            after.send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + server.port() + "/status"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, afterResponse.statusCode());
      }
    }
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

  private TlsSettings issueLeaf(CertificateAuthority ca, String commonName) throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(keyPair, new X500Name("CN=" + commonName));
    X509Certificate leaf = ca.signCertificateRequest(csr, Duration.ofDays(1));

    Path certFile = writePem(commonName + "-cert.pem", "CERTIFICATE", leaf.getEncoded());
    Path keyFile =
        writePem(commonName + "-key.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
    Path caFile = writePem(commonName + "-ca.pem", "CERTIFICATE", ca.certificate().getEncoded());

    return new TlsSettings(certFile, keyFile, caFile);
  }

  private Path writePem(String fileName, String label, byte[] derBytes) throws IOException {
    Files.writeString(fileName(fileName), pem(label, derBytes));
    return fileName(fileName);
  }

  private void overwritePem(Path path, String label, byte[] derBytes) throws IOException {
    Files.writeString(path, pem(label, derBytes));
  }

  private Path fileName(String fileName) {
    return tempDir.resolve(fileName);
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
