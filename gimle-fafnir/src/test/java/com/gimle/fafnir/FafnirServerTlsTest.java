package com.gimle.fafnir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.authz.Permission;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Role;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.authz.Verb;
import com.gimle.core.protocol.Json;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.fafnir.testsupport.InProcessStore;
import com.gimle.fafnir.testsupport.TlsTestFixtures;
import com.gimle.mimir.store.StateStore;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLContext;
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
 * Proves {@code gimle.transport.protocol=tls} actually swaps {@link FafnirServer} onto {@code
 * HttpsServer} with real mTLS -- a real HTTPS request over loopback with a real, CA-signed client
 * certificate, mirroring {@code ApiServerTlsTest}'s own shape -- plus a real cert-rotation reload,
 * mirroring {@code RaftClusterTlsTest}'s own {@code reloadTlsMaterial} coverage.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-fafnir-server-http")
class FafnirServerTlsTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  private Path tempDir;

  private TlsTestFixtures tls;

  @BeforeEach
  void setUp() {
    tls = new TlsTestFixtures(tempDir);
  }

  @AfterEach
  void clearTransportProperties() {
    TlsTestFixtures.clearTransportProperties();
  }

  @Test
  @Timeout(10)
  void a_real_mtls_request_with_a_ca_signed_client_cert_succeeds() throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);
    TlsSettings clientSettings = tls.issueLeaf(ca, "caller");

    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"))) {
      FafnirCrypto crypto = new FafnirCrypto(store.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        SSLContext clientContext = SslContexts.forMutualTls(clientSettings);
        HttpClient client = HttpClient.newBuilder().sslContext(clientContext).build();
        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create(
                            "https://localhost:" + server.port() + "/internal/secrets/encrypt"))
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            Json.write(
                                Map.of(
                                    "value",
                                    Base64.getEncoder()
                                        .encodeToString("hi".getBytes(StandardCharsets.UTF_8))))))
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void reloading_tls_material_lets_a_fresh_connection_succeed_without_restarting_the_server()
      throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);
    Path certFile = Path.of(System.getProperty(TlsTestFixtures.CERT_FILE_PROPERTY));
    Path keyFile = Path.of(System.getProperty(TlsTestFixtures.KEY_FILE_PROPERTY));
    Path caFile = Path.of(System.getProperty(TlsTestFixtures.CA_FILE_PROPERTY));

    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"))) {
      // The reused server leaf's own CN ("fafnir") is also this test's client identity below --
      // needs the same cluster-wide SECRET/WRITE grant FafnirServer#authorizeGlobalSecretsAdmin
      // now requires of any /secrets/rotate-key caller.
      grantSecretWrite(store.store(), "fafnir");
      FafnirCrypto crypto = new FafnirCrypto(store.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        TlsSettings clientSettings = new TlsSettings(certFile, keyFile, caFile);
        HttpClient before =
            HttpClient.newBuilder().sslContext(SslContexts.forMutualTls(clientSettings)).build();
        HttpResponse<String> beforeResponse =
            before.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/rotate-key"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, beforeResponse.statusCode());

        // Rotate: a fresh CA-signed leaf, written over the *same* cert/key file paths -- exactly
        // what a real rotation does to gimle.tls.certFile/keyFile in place.
        KeyPair rotatedKeyPair = TlsTestFixtures.generateRsaKeyPair();
        PKCS10CertificationRequest rotatedCsr =
            CertificateSigningRequests.generate(
                rotatedKeyPair, new X500Name("CN=fafnir"), List.of("localhost"));
        X509Certificate rotatedLeaf = ca.signCertificateRequest(rotatedCsr, Duration.ofDays(1));
        tls.overwritePem(certFile, "CERTIFICATE", rotatedLeaf.getEncoded());
        tls.overwritePem(keyFile, "PRIVATE KEY", rotatedKeyPair.getPrivate().getEncoded());

        server.reloadTlsMaterial();

        // A brand-new connection (not the already-established one from before rotation) must
        // succeed against the reloaded listener, at the *same* port, without restarting the
        // process -- if reload hadn't rebound there, this request would simply fail.
        HttpClient after =
            HttpClient.newBuilder().sslContext(SslContexts.forMutualTls(clientSettings)).build();
        HttpResponse<String> afterResponse =
            after.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/rotate-key"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, afterResponse.statusCode());
      }
    }
  }

  private static void grantSecretWrite(StateStore store, String username) {
    store.putRole(
        new Role("secret-write", Set.of(Permission.unscoped(ResourceKind.SECRET, Verb.WRITE))));
    store.putRoleBinding(
        new RoleBinding("b-" + username, RoleBinding.userSubject(username), "secret-write"));
  }
}
