package com.gimle.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.module.lifecycle.SimpleModuleContext;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * Proves {@code gimle.transport.protocol=tls} actually swaps {@link GatewayHooks} onto a real
 * {@code HttpsServer} terminating a genuine TLS handshake -- mirroring {@code
 * ApiServerTlsTest}/{@code MuninnServerTlsTest}'s own shape, the same real-CA-signed-certificate
 * pattern every other {@code com.sun.net.httpserver}-based TLS listener in this codebase is proven
 * with -- plus a companion test proving the plaintext path {@link GatewayHooks} always took is
 * completely untouched when TLS isn't configured (this test file's own back-compat obligation,
 * separate from every pre-existing {@link GatewayDispatcherTest}/{@link GatewayRouteConfigTest}
 * continuing to pass unchanged).
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class GatewayHooksTlsTest {

  private static final String PROTOCOL_PROPERTY = "gimle.transport.protocol";
  private static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";
  private static final String KEY_FILE_PROPERTY = "gimle.tls.keyFile";
  private static final String CA_FILE_PROPERTY = "gimle.tls.caFile";

  @TempDir(cleanup = CleanupMode.NEVER)
  private Path tempDir;

  private GatewayHooks hooks;

  @AfterEach
  void stopGatewayAndClearTransportProperties() {
    if (hooks != null) {
      hooks.onStop(null);
    }
    System.clearProperty(PROTOCOL_PROPERTY);
    System.clearProperty(CERT_FILE_PROPERTY);
    System.clearProperty(KEY_FILE_PROPERTY);
    System.clearProperty(CA_FILE_PROPERTY);
  }

  @Test
  void https_request_with_a_valid_client_cert_reaches_the_dispatched_route() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(
            new X500Name("CN=test-cluster-ca"), Duration.ofDays(1));
    configureServerTls(ca);
    TlsSettings clientSettings = issueLeaf(ca, "caller");

    hooks = new GatewayHooks();
    hooks.onStart(contextWithGreeterRoute());

    SSLContext clientContext = SslContexts.forMutualTls(clientSettings);
    HttpClient client = HttpClient.newBuilder().sslContext(clientContext).build();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("https://localhost:" + hooks.port() + "/greet"))
            .POST(HttpRequest.BodyPublishers.ofString("Freya"))
            .build();

    HttpResponse<String> response =
        client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode());
    assertEquals("hello, Freya", response.body());
  }

  @Test
  void plaintext_gateway_still_works_when_tls_is_not_configured() throws Exception {
    hooks = new GatewayHooks();
    hooks.onStart(contextWithGreeterRoute());

    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + hooks.port() + "/greet"))
            .POST(HttpRequest.BodyPublishers.ofString("Odin"))
            .build();

    HttpResponse<String> response =
        client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode());
    assertEquals("hello, Odin", response.body());
  }

  private static SimpleModuleContext contextWithGreeterRoute() {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    ModuleId gatewayId = new ModuleId("com.gimle.gateway", Version.parse("1.0.0"));
    registry.register(gatewayId, TestGreeter.class, name -> "hello, " + name);
    return new SimpleModuleContext(
        gatewayId,
        registry,
        new ConcurrentHashMap<>(
            Map.of(
                "gateway.port",
                "0",
                "gateway.routes",
                "FABRIC /greet " + TestGreeter.class.getName() + " 1 greet STRING\n")));
  }

  private void configureServerTls(CertificateAuthority ca) throws Exception {
    // The client connects to "https://localhost:...", so the server's own leaf cert needs
    // "localhost" as a Subject Alternative Name -- real HTTPS clients do hostname verification
    // against SAN, not just the CA trust chain.
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(
            keyPair, new X500Name("CN=gimle-gateway"), List.of("localhost"));
    X509Certificate leaf = ca.signCertificateRequest(csr, Duration.ofDays(1));
    Path certFile = writePem("gateway-cert.pem", "CERTIFICATE", leaf.getEncoded());
    Path keyFile = writePem("gateway-key.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
    Path caFile = writePem("gateway-ca.pem", "CERTIFICATE", ca.certificate().getEncoded());
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
