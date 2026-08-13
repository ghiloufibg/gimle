package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.controlplane.muninn.MuninnClient;
import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.authz.Account;
import com.gimle.core.authz.PasswordHashes;
import com.gimle.core.authz.Permission;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Role;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.authz.Verb;
import com.gimle.core.protocol.Json;
import com.gimle.core.tls.SslContexts;
import com.gimle.mimir.store.StateStore;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.CertificateSigningRequests;
import com.gimle.pki.Pem;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
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
 * {@code GET /metrics-history/{processKind}/{processId}}: a thin, {@code ResourceKind.LOGS}/{@code
 * Verb.READ}-gated proxy to Muninn's own {@code GET /metrics/{processKind}/{processId}}. The
 * plaintext-mode proxy behavior mirrors {@code ApiServerLogsFallbackTest}'s own shape; the 403 case
 * needs real TLS+RBAC, reusing {@code ApiServerAuthzTest}'s own session-login/role-binding pattern.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerMetricsHistoryTest {

  private static final String PROTOCOL_PROPERTY = "gimle.transport.protocol";
  private static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";
  private static final String KEY_FILE_PROPERTY = "gimle.tls.keyFile";
  private static final String CA_FILE_PROPERTY = "gimle.tls.caFile";

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessStore inProcessStore;
  private InProcessFafnir inProcessFafnir;
  private ApiServer server;
  private HttpClient client;
  private String baseUrl;
  private HttpServer muninnStub;
  private Path caFile;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
    if (muninnStub != null) {
      muninnStub.stop(0);
    }
    if (inProcessFafnir != null) {
      inProcessFafnir.close();
    }
    if (inProcessStore != null) {
      inProcessStore.close();
    }
    System.clearProperty(PROTOCOL_PROPERTY);
    System.clearProperty(CERT_FILE_PROPERTY);
    System.clearProperty(KEY_FILE_PROPERTY);
    System.clearProperty(CA_FILE_PROPERTY);
  }

  private void startPlaintextServer(MuninnClient muninnClient) throws IOException {
    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    server =
        muninnClient == null
            ? new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())
            : new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client(), muninnClient);
    server.start();
    baseUrl = "http://localhost:" + server.port();
    client = HttpClient.newHttpClient();
  }

  private HttpServer startMuninnStub(List<String> receivedPaths) throws IOException {
    HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext(
        "/metrics",
        exchange -> {
          receivedPaths.add(
              exchange.getRequestURI().getPath() + "?" + exchange.getRequestURI().getRawQuery());
          byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    stub.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    stub.start();
    return stub;
  }

  @Test
  @Timeout(10)
  void proxies_to_muninn_forwarding_the_since_query_parameter() throws Exception {
    List<String> receivedPaths = new CopyOnWriteArrayList<>();
    muninnStub = startMuninnStub(receivedPaths);
    startPlaintextServer(new MuninnClient("127.0.0.1:" + muninnStub.getAddress().getPort()));

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(
                        baseUrl + "/metrics-history/CONTROLPLANE/127.0.0.1:8080?since=cursor-1"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode());
    assertEquals(1, receivedPaths.size());
    assertEquals("/metrics/CONTROLPLANE/127.0.0.1:8080?since=cursor-1", receivedPaths.get(0));
  }

  @Test
  @Timeout(10)
  void a_404_when_no_muninn_endpoint_is_configured() throws Exception {
    startPlaintextServer(null);

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/metrics-history/CONTROLPLANE/127.0.0.1:8080"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(404, response.statusCode());
  }

  @Test
  @Timeout(10)
  void a_malformed_path_missing_process_id_is_rejected() throws Exception {
    muninnStub = startMuninnStub(new CopyOnWriteArrayList<>());
    startPlaintextServer(new MuninnClient("127.0.0.1:" + muninnStub.getAddress().getPort()));

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/metrics-history/CONTROLPLANE"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(400, response.statusCode());
  }

  /**
   * The RBAC gate itself, at the real HTTP/mTLS layer -- a caller with no {@code LOGS} permission
   * at all is forbidden, mirroring {@code ApiServerAuthzTest}'s own session-login pattern.
   */
  @Test
  @Timeout(10)
  void a_caller_with_no_logs_permission_is_forbidden() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    StateStore store = inProcessStore.store();
    store.putAccount(new Account("nobody", PasswordHashes.hash("pw".toCharArray())));
    // A role that grants some other permission, but never LOGS -- proves the gate is
    // resource-kind-specific, not "any authenticated session passes."
    store.putRole(
        new Role(
            "deployments-only", Set.of(Permission.unscoped(ResourceKind.DEPLOYMENT, Verb.READ))));
    store.putRoleBinding(
        new RoleBinding("b1", RoleBinding.userSubject("nobody"), "deployments-only"));

    inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    // Reuses the #server/#tearDown fields the rest of this class already relies on for cleanup,
    // rather than a separate try-with-resources local -- inProcessStore/inProcessFafnir are
    // instance fields here (assigned above), not effectively-final locals a try-with-resources
    // can close directly.
    server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client());
    server.start();
    String tlsBaseUrl = "https://localhost:" + server.port();
    HttpClient tlsClient =
        HttpClient.newBuilder().sslContext(SslContexts.forServerTrustOnly(caFile)).build();

    Map<String, Object> loginBody = new LinkedHashMap<>();
    loginBody.put("username", "nobody");
    loginBody.put("password", "pw");
    HttpResponse<String> loginResponse =
        tlsClient.send(
            HttpRequest.newBuilder(URI.create(tlsBaseUrl + "/auth/login"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        Json.write(loginBody), StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, loginResponse.statusCode());
    String setCookie = loginResponse.headers().firstValue("Set-Cookie").orElse("");
    String cookie = setCookie.substring(0, setCookie.indexOf(';'));

    HttpResponse<String> response =
        tlsClient.send(
            HttpRequest.newBuilder(
                    URI.create(tlsBaseUrl + "/metrics-history/CONTROLPLANE/127.0.0.1:8080"))
                .header("Cookie", cookie)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(403, response.statusCode());
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

    System.setProperty(PROTOCOL_PROPERTY, "tls");
    System.setProperty(CERT_FILE_PROPERTY, certFile.toString());
    System.setProperty(KEY_FILE_PROPERTY, keyFile.toString());
    System.setProperty(CA_FILE_PROPERTY, caFile.toString());
  }

  private Path writePem(String fileName, String pem) throws IOException {
    Path path = tempDir.resolve(fileName);
    Files.writeString(path, pem);
    return path;
  }

  private static KeyPair generateRsaKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }
}
