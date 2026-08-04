package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.authz.Account;
import com.gimle.core.authz.PasswordHashes;
import com.gimle.core.protocol.Json;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.mimir.store.StateStore;
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
 * The RBAC behaviors that only exist at the real HTTP/mTLS layer -- {@code claudedocs/
 * authn-authz-design.md} §11's test list, the parts not already covered by unit-level {@code
 * AuthorizerTest} or the existing PKI flow tests ({@code HumanOperatorCsrTest}, {@code
 * NodeBootstrapCsrTest}, {@code CertificateRotationTest}, all updated for RBAC in the same change
 * this test class lands with).
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerAuthzTest {

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

  /**
   * The specific privilege-escalation regression {@code claudedocs/authn-authz-design.md} §2a
   * exists to prevent: a {@code NODE_CLIENT} CSR whose own Subject already self-declares {@code
   * O=gimle:operators} must still be signed with {@code O=gimle:nodes} -- proof the server-side
   * reconstruction wins, not the CSR's own claim.
   */
  @Test
  void a_node_csr_cannot_self_declare_the_operators_group() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
        ApiServer server = new ApiServer(inProcessStore.client(), 0)) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();

      HttpClient operatorClient = mutualTlsClient(ca, "O=gimle:operators,CN=existing-operator");
      String token = issueBootstrapToken(operatorClient, baseUrl);

      KeyPair agentKeyPair = generateRsaKeyPair();
      // Self-declares O=gimle:operators, purpose is still NODE_CLIENT -- the escalation attempt.
      PKCS10CertificationRequest csr =
          CertificateSigningRequests.generate(
              agentKeyPair, new X500Name("O=gimle:operators,CN=malicious-node"));
      HttpClient trustOnlyClient = trustOnlyClient();

      Map<String, Object> result =
          submitCsr(trustOnlyClient, baseUrl, "NODE_CLIENT", csr, token, 200);
      String certPem = (String) result.get("certificatePem");
      assertNotNull(certPem);

      X509Certificate signed = Pem.decodeCertificate(certPem);
      String subject = signed.getSubjectX500Principal().getName();
      assertTrue(subject.contains("O=gimle:nodes"), "expected O=gimle:nodes, got: " + subject);
      assertTrue(
          !subject.contains("O=gimle:operators"), "must not carry O=gimle:operators: " + subject);
    }
  }

  /**
   * {@code /bootstrap/csr/{id}/approve} now requires {@code CERTIFICATE_REQUEST:APPROVE}, not
   * merely "any valid certificate" -- a certificate carrying no group at all (issued directly by
   * the test CA, bypassing the real join flow, which always stamps one of the two built-in groups)
   * has an identity but no permission, and must be denied with 403, not the 401 an unauthenticated
   * caller would get.
   */
  @Test
  void approving_with_a_certificate_carrying_no_group_is_forbidden_not_unauthorized()
      throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
        ApiServer server = new ApiServer(inProcessStore.client(), 0)) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();

      HttpClient trustOnlyClient = trustOnlyClient();
      KeyPair pendingKeyPair = generateRsaKeyPair();
      PKCS10CertificationRequest pendingCsr =
          CertificateSigningRequests.generate(pendingKeyPair, new X500Name("CN=new-operator"));
      Map<String, Object> submitResult =
          submitOperatorCsr(trustOnlyClient, baseUrl, pendingCsr, 202);
      String requestId = (String) submitResult.get("requestId");

      // Issued directly by the test's own CA -- CN= only, no O=, something the real join flow can
      // no longer produce (every real leaf now gets a server-stamped group).
      HttpClient noGroupClient = mutualTlsClient(ca, "CN=no-group-caller");
      HttpRequest approveRequest =
          HttpRequest.newBuilder(URI.create(baseUrl + "/bootstrap/csr/" + requestId + "/approve"))
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      HttpResponse<String> approveResponse =
          noGroupClient.send(
              approveRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(403, approveResponse.statusCode());
    }
  }

  /**
   * The console's whole login story, end to end: an unauthenticated {@code /auth/session} check
   * (401), a successful login sets a session cookie, that cookie alone (no client certificate)
   * authorizes a later request, and {@code /auth/logout} invalidates it for anything after.
   */
  @Test
  void login_session_and_logout_round_trip_with_no_client_certificate_at_all() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    StateStore store = inProcessStore.store();
    byte[] passwordHash = PasswordHashes.hash("s3cret-password".toCharArray());
    store.putAccount(new Account("admin", passwordHash));
    // The one custom RoleBinding this test needs so the logged-in session can actually reach
    // something -- "user:admin" bound to the built-in cluster-admin role's name would require
    // that Role to exist as a stored object too, which it deliberately isn't (BuiltinRoles.
    // CLUSTER_ADMIN is a constant, not stored) -- so bind admin to a small custom role instead,
    // scoped to exactly what this test needs to prove the cookie actually authorizes something.
    store.putRole(
        new com.gimle.core.authz.Role(
            "tenant-reader",
            java.util.Set.of(
                com.gimle.core.authz.Permission.unscoped(
                    com.gimle.core.authz.ResourceKind.TENANT, com.gimle.core.authz.Verb.READ))));
    store.putRoleBinding(
        new com.gimle.core.authz.RoleBinding(
            "b1", com.gimle.core.authz.RoleBinding.userSubject("admin"), "tenant-reader"));

    try (inProcessStore;
        ApiServer server = new ApiServer(inProcessStore.client(), 0)) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      // No cookieHandler: java.net.http.HttpClient's built-in CookieManager doesn't reliably
      // round-trip a SameSite=Strict cookie in practice, and this test is exercising ApiServer's
      // own cookie handling, not the JDK's cookie-jar parsing -- the session cookie value is
      // threaded through explicitly instead, exactly as a browser's own cookie jar would deliver
      // it (this test only cares that the *value* ApiServer set is the one it later accepts).
      HttpClient client =
          HttpClient.newBuilder().sslContext(SslContexts.forServerTrustOnly(caFile)).build();

      // 1. Not logged in yet.
      HttpResponse<String> beforeLogin =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/auth/session")).GET().build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(401, beforeLogin.statusCode());

      // 2. Wrong password rejected.
      HttpResponse<String> badLogin =
          client.send(
              loginRequest(baseUrl, "admin", "wrong-password"),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(401, badLogin.statusCode());

      // 3. Correct login succeeds and the cookie authorizes /auth/session afterward.
      HttpResponse<String> login =
          client.send(
              loginRequest(baseUrl, "admin", "s3cret-password"),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, login.statusCode());
      String setCookie = login.headers().firstValue("Set-Cookie").orElse("");
      assertTrue(setCookie.contains("gimle_session="));
      String sessionCookie = setCookie.substring(0, setCookie.indexOf(';'));

      HttpResponse<String> session =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/auth/session"))
                  .header("Cookie", sessionCookie)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, session.statusCode());
      assertEquals("admin", Json.asObject(Json.parse(session.body())).get("username"));

      // 4. The custom RoleBinding actually authorizes a real resource, not just /auth/session.
      HttpResponse<String> tenants =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/tenants"))
                  .header("Cookie", sessionCookie)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, tenants.statusCode());

      // 5. Logout tells the browser to drop the cookie (Max-Age=0) -- it does not revoke the
      // token server-side (documented, deliberate: the whole point of a stateless signed token is
      // needing no revocation list). A request presenting no cookie at all afterward is
      // unauthenticated, same as before any login ever happened.
      HttpResponse<String> logout =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/auth/logout"))
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, logout.statusCode());
      assertTrue(logout.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=0"));

      HttpResponse<String> afterLogout =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/auth/session")).GET().build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(401, afterLogout.statusCode());
    }
  }

  private static HttpRequest loginRequest(String baseUrl, String username, String password) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("username", username);
    body.put("password", password);
    return HttpRequest.newBuilder(URI.create(baseUrl + "/auth/login"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
        .build();
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

  private static Map<String, Object> submitCsr(
      HttpClient client,
      String baseUrl,
      String purpose,
      PKCS10CertificationRequest csr,
      String bootstrapToken,
      int expectedStatus)
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
    HttpResponse<String> response =
        client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(expectedStatus, response.statusCode());
    return Json.asObject(Json.parse(response.body()));
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
