package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.controlplane.store.StateStore;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
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
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
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
 * Proves {@code gimle.transport.protocol=tls} actually swaps {@link ApiServer} onto {@code
 * HttpsServer} with real mTLS -- a real HTTPS request over loopback with a real, CA-signed client
 * certificate, not just "the code compiles." Real certificate material comes from {@code
 * gimle-pki}, a real (main-scope) dependency of this module since the control plane now signs CSRs
 * itself (design doc §4).
 */
// System.setProperty mutates a JVM-global; excludes this class from running concurrently with
// any other class holding the same lock, under class-level parallel execution (root pom.xml).
@ResourceLock(Resources.SYSTEM_PROPERTIES)
// Real ApiServer + real HttpClient on a loopback ephemeral port (see ApiServerTest for why):
// excluded from running concurrently with any other class doing the same.
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerTlsTest {

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
  void https_request_with_a_valid_client_cert_succeeds() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(
            new X500Name("CN=test-cluster-ca"), Duration.ofDays(1));
    configureServerTls(ca);
    TlsSettings clientSettings = issueLeaf(ca, "operator");

    StateStore store = new StateStore(tempDir.resolve("store"));
    try (ApiServer server = new ApiServer(store, 0)) {
      server.start();
      SSLContext clientContext = SslContexts.forMutualTls(clientSettings);
      HttpClient client = HttpClient.newBuilder().sslContext(clientContext).build();
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("https://localhost:" + server.port() + "/nodes"))
              .GET()
              .build();

      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, response.statusCode());
    }
  }

  @Test
  void https_request_without_a_client_cert_is_rejected() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(
            new X500Name("CN=test-cluster-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    StateStore store = new StateStore(tempDir.resolve("store"));
    try (ApiServer server = new ApiServer(store, 0)) {
      server.start();
      // A client that trusts the CA (so it accepts the server's own cert) but presents no client
      // certificate of its own. The server sets wantClientAuth, not needClientAuth (see
      // ApiServer#createHttpServer's javadoc -- HttpsConfigurator negotiates per connection,
      // before the request path is known, so there's no way to make client-auth conditional on
      // path at that layer), so the TLS handshake itself succeeds;
      // ApiServer#requireClientCertificate
      // is what rejects the request, with a 401 at the HTTP layer instead of a handshake failure.
      SSLContext trustOnlyContext = SSLContext.getInstance("TLSv1.3");
      trustOnlyContext.init(null, trustManagersFor(ca), null);
      HttpClient client = HttpClient.newBuilder().sslContext(trustOnlyContext).build();
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("https://localhost:" + server.port() + "/nodes"))
              .GET()
              .build();

      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(401, response.statusCode());
    }
  }

  private static javax.net.ssl.TrustManager[] trustManagersFor(CertificateAuthority ca)
      throws Exception {
    java.security.KeyStore trustStore = java.security.KeyStore.getInstance("PKCS12");
    trustStore.load(null, null);
    trustStore.setCertificateEntry("cluster-ca", ca.certificate());
    javax.net.ssl.TrustManagerFactory factory =
        javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
    factory.init(trustStore);
    return factory.getTrustManagers();
  }

  private void configureServerTls(CertificateAuthority ca) throws Exception {
    // The client connects to "https://localhost:...", so the server's own leaf cert needs
    // "localhost" as a Subject Alternative Name -- real HTTPS clients (this test's HttpClient
    // included) do hostname verification against SAN, not just the CA trust chain.
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(
            keyPair, new X500Name("CN=controlplane"), List.of("localhost"));
    X509Certificate leaf = ca.signCertificateRequest(csr, Duration.ofDays(1));
    Path certFile = writePem("controlplane-cert.pem", "CERTIFICATE", leaf.getEncoded());
    Path keyFile =
        writePem("controlplane-key.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
    Path caFile = writePem("controlplane-ca.pem", "CERTIFICATE", ca.certificate().getEncoded());
    TlsSettings serverSettings = new TlsSettings(certFile, keyFile, caFile);
    System.setProperty(PROTOCOL_PROPERTY, "tls");
    System.setProperty(CERT_FILE_PROPERTY, serverSettings.certFile().toString());
    System.setProperty(KEY_FILE_PROPERTY, serverSettings.keyFile().toString());
    System.setProperty(CA_FILE_PROPERTY, serverSettings.caFile().toString());
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
