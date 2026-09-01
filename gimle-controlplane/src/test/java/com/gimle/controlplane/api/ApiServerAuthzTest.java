package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.authz.Account;
import com.gimle.core.authz.BuiltinRoles;
import com.gimle.core.authz.PasswordHashes;
import com.gimle.core.authz.Permission;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Role;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.authz.Verb;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.Json;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.store.DaemonSetAssignment;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.StateStore;
import com.gimle.module.testsupport.TestModuleBuilder;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
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
 * The RBAC behaviors that only exist at the real HTTP/mTLS layer -- the parts not already covered
 * by unit-level {@code AuthorizerTest} or the existing PKI flow tests ({@code
 * HumanOperatorCsrTest}, {@code NodeBootstrapCsrTest}, {@code CertificateRotationTest}, all updated
 * for RBAC alongside this test class).
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerAuthzTest {

  private static final String PROTOCOL_PROPERTY = "gimle.transport.protocol";
  private static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";
  private static final String KEY_FILE_PROPERTY = "gimle.tls.keyFile";
  private static final String CA_FILE_PROPERTY = "gimle.tls.caFile";
  private static final String CA_KEY_FILE_PROPERTY = "gimle.tls.caKeyFile";
  private static final String AUDIT_READ_RESOURCE_KINDS_PROPERTY =
      "gimle.controlplane.audit.readResourceKinds";

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
    System.clearProperty(AUDIT_READ_RESOURCE_KINDS_PROPERTY);
  }

  /**
   * The privilege-escalation regression this test guards against: a {@code NODE_CLIENT} CSR whose
   * own Subject already self-declares {@code O=gimle:operators} must still be signed with {@code
   * O=gimle:nodes} -- proof the server-side reconstruction wins, not the CSR's own claim.
   */
  @Test
  void a_node_csr_cannot_self_declare_the_operators_group() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
        InProcessFafnir inProcessFafnir =
            InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
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
   * A {@code TENANT_CLIENT} submission is an already-credentialed caller minting a
   * tenant-membership certificate: an operator gets it signed synchronously with the server-stamped
   * {@code O=gimle:tenant:<id>} (never the CSR's own subject claim), while a caller with no
   * approval permission is refused outright -- the certificate that would let a TLS-terminating
   * proxy trust a caller's tenant must itself only ever come from someone trusted to say so.
   */
  @Test
  void a_tenant_client_certificate_is_minted_by_an_operator_and_carries_the_tenant_group()
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
      HttpClient operatorClient = mutualTlsClient(ca, "O=gimle:operators,CN=root-operator");

      KeyPair workloadKeyPair = generateRsaKeyPair();
      // Self-declares the operators group -- the same escalation shape the node-join test proves
      // is discarded; only the server-stamped tenant group may survive issuance.
      PKCS10CertificationRequest csr =
          CertificateSigningRequests.generate(
              workloadKeyPair, new X500Name("O=gimle:operators,CN=acme-workload"));

      Map<String, Object> submission = new LinkedHashMap<>();
      submission.put("purpose", "TENANT_CLIENT");
      submission.put("csrPem", Pem.encodeCsr(csr));
      submission.put("tenantId", "acme");
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(baseUrl + "/bootstrap/csr"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(Json.write(submission)))
              .build();
      HttpResponse<String> response =
          operatorClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, response.statusCode());
      String certPem = (String) Json.asObject(Json.parse(response.body())).get("certificatePem");
      assertNotNull(certPem);
      String subject = Pem.decodeCertificate(certPem).getSubjectX500Principal().getName();
      assertTrue(subject.contains("O=gimle:tenant:acme"), subject);
      assertTrue(!subject.contains("O=gimle:operators"), subject);

      // An unauthenticated caller has no identity to authorize the mint with.
      HttpResponse<String> unauthenticated =
          trustOnlyClient()
              .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(401, unauthenticated.statusCode());
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
        InProcessFafnir inProcessFafnir =
            InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
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
        new Role(
            "tenant-reader",
            java.util.Set.of(Permission.unscoped(ResourceKind.TENANT, Verb.READ))));
    store.putRoleBinding(new RoleBinding("b1", RoleBinding.userSubject("admin"), "tenant-reader"));

    InProcessFafnir inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    try (inProcessStore;
        inProcessFafnir;
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
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

      // 5. Logout tells the browser to drop the cookie (Max-Age=0) *and* revokes the token
      // server-side: the same cookie value, replayed afterward, is rejected even though its HMAC
      // signature still verifies and it has not hit its ordinary 12-hour expiry -- otherwise a
      // captured/stolen token would keep working for the rest of its natural lifetime regardless
      // of the user's own logout.
      HttpResponse<String> logout =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/auth/logout"))
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .header("Cookie", sessionCookie)
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, logout.statusCode());
      assertTrue(logout.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=0"));

      HttpResponse<String> afterLogout =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/auth/session")).GET().build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(401, afterLogout.statusCode());

      // The old, already-issued cookie itself is now rejected server-side, not just absent from
      // this fresh request -- the actual proof logout revoked it rather than merely telling this
      // one client to forget it.
      HttpResponse<String> replayedOldCookie =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/auth/session"))
                  .header("Cookie", sessionCookie)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(401, replayedOldCookie.statusCode());
    }
  }

  /**
   * Repeated failed logins against the same username eventually get throttled to a 429 with a
   * {@code Retry-After} header -- even a subsequently-correct password doesn't bypass it, since the
   * point is slowing down a guessing attempt, not just rejecting wrong guesses.
   */
  @Test
  void repeated_failed_logins_are_throttled_with_429_and_a_retry_after_header() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    StateStore store = inProcessStore.store();
    store.putAccount(new Account("admin", PasswordHashes.hash("s3cret-password".toCharArray())));

    InProcessFafnir inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    try (inProcessStore;
        inProcessFafnir;
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      HttpClient client =
          HttpClient.newBuilder().sslContext(SslContexts.forServerTrustOnly(caFile)).build();

      // LoginThrottle's default failureThreshold is 3: the first two failures are recorded but
      // impose no delay; the third crosses the threshold and starts throttling.
      for (int i = 0; i < 3; i++) {
        HttpResponse<String> response =
            client.send(
                loginRequest(baseUrl, "admin", "wrong-password"),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(401, response.statusCode());
      }

      HttpResponse<String> throttled =
          client.send(
              loginRequest(baseUrl, "admin", "s3cret-password"),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(429, throttled.statusCode());
      assertTrue(throttled.headers().firstValue("Retry-After").isPresent());
      // Same generic body as an ordinary failed login -- must not let a caller distinguish
      // "throttled" from "wrong credentials" by response content.
      assertEquals("too many attempts; try again later", throttled.body());
    }
  }

  /**
   * {@code CONFIG} and {@code SECRET} are enforced as fully independent permissions: a role holding
   * only one must be denied write/delete on the other kind of entry, and {@code
   * /config/{tenantId}}'s list response must be filtered per-entry rather than gated uniformly.
   */
  @Test
  void config_and_secret_permissions_are_independently_enforced_and_filtered() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    StateStore store = inProcessStore.store();
    byte[] passwordHash = PasswordHashes.hash("pw".toCharArray());
    store.putAccount(new Account("config-user", passwordHash));
    store.putAccount(new Account("secret-user", passwordHash));
    store.putRole(
        new Role(
            "config-only",
            java.util.Set.of(
                Permission.unscoped(ResourceKind.CONFIG, Verb.READ),
                Permission.unscoped(ResourceKind.CONFIG, Verb.WRITE),
                Permission.unscoped(ResourceKind.CONFIG, Verb.DELETE))));
    store.putRole(
        new Role(
            "secret-only",
            java.util.Set.of(
                Permission.unscoped(ResourceKind.SECRET, Verb.READ),
                Permission.unscoped(ResourceKind.SECRET, Verb.WRITE),
                Permission.unscoped(ResourceKind.SECRET, Verb.DELETE))));
    store.putRoleBinding(
        new RoleBinding("b1", RoleBinding.userSubject("config-user"), "config-only"));
    store.putRoleBinding(
        new RoleBinding("b2", RoleBinding.userSubject("secret-user"), "secret-only"));
    store.putTenant(new Tenant("tenant-1", new ResourceQuota(1_000_000_000L, 4000, 10)));

    InProcessFafnir inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    try (inProcessStore;
        inProcessFafnir;
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      HttpClient client =
          HttpClient.newBuilder().sslContext(SslContexts.forServerTrustOnly(caFile)).build();

      String configCookie = login(client, baseUrl, "config-user", "pw");
      String secretCookie = login(client, baseUrl, "secret-user", "pw");

      // A CONFIG-only caller can write a plaintext entry but not an encrypted one.
      assertEquals(
          200, putConfig(client, baseUrl, configCookie, "tenant-1", "plain-key", "p", false));
      assertEquals(
          403, putConfig(client, baseUrl, configCookie, "tenant-1", "secret-key", "s", true));

      // A SECRET-only caller can write an encrypted entry but not a plaintext one.
      assertEquals(
          200, putConfig(client, baseUrl, secretCookie, "tenant-1", "secret-key", "s", true));
      assertEquals(
          403, putConfig(client, baseUrl, secretCookie, "tenant-1", "plain-key-2", "p2", false));

      // The list response is filtered per-entry: each caller sees only their own kind.
      List<Map<String, Object>> configView = listConfig(client, baseUrl, configCookie, "tenant-1");
      assertEquals(1, configView.size());
      assertEquals("plain-key", configView.get(0).get("key"));

      List<Map<String, Object>> secretView = listConfig(client, baseUrl, secretCookie, "tenant-1");
      assertEquals(1, secretView.size());
      assertEquals("secret-key", secretView.get(0).get("key"));
      assertEquals("s", secretView.get(0).get("value"));

      // Deleting the other kind's entry is forbidden, not merely a no-op.
      assertEquals(403, deleteConfig(client, baseUrl, configCookie, "tenant-1", "secret-key"));
      assertEquals(403, deleteConfig(client, baseUrl, secretCookie, "tenant-1", "plain-key"));

      // A nonexistent key deletes idempotently (200, matching every other resource kind's own
      // delete-of-a-never-existed-name convention) rather than 404 -- with nothing stored to read
      // an `encrypted` flag from, authorization defaults to CONFIG, which configCookie holds.
      assertEquals(200, deleteConfig(client, baseUrl, configCookie, "tenant-1", "does-not-exist"));

      // Each caller can delete their own kind.
      assertEquals(200, deleteConfig(client, baseUrl, configCookie, "tenant-1", "plain-key"));
      assertEquals(200, deleteConfig(client, baseUrl, secretCookie, "tenant-1", "secret-key"));
    }
  }

  /**
   * Every {@code WRITE}/{@code DELETE} decision {@code requireAuthorized} makes lands in the
   * durable audit trail, allowed and denied alike -- but {@code READ} and a bare {@code 401} (no
   * principal resolved at all) deliberately don't.
   */
  @Test
  void write_and_delete_decisions_are_audited_allowed_and_denied_but_reads_and_401s_are_not()
      throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    StateStore store = inProcessStore.store();
    byte[] passwordHash = PasswordHashes.hash("pw".toCharArray());
    store.putAccount(new Account("writer", passwordHash));
    store.putAccount(new Account("reader-only", passwordHash));
    store.putRole(
        new Role(
            "config-writer",
            java.util.Set.of(
                Permission.unscoped(ResourceKind.CONFIG, Verb.READ),
                Permission.unscoped(ResourceKind.CONFIG, Verb.WRITE),
                Permission.unscoped(ResourceKind.CONFIG, Verb.DELETE))));
    store.putRole(
        new Role(
            "config-reader",
            java.util.Set.of(Permission.unscoped(ResourceKind.CONFIG, Verb.READ))));
    store.putRoleBinding(new RoleBinding("b1", RoleBinding.userSubject("writer"), "config-writer"));
    store.putRoleBinding(
        new RoleBinding("b2", RoleBinding.userSubject("reader-only"), "config-reader"));

    InProcessFafnir inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    try (inProcessStore;
        inProcessFafnir;
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      HttpClient client =
          HttpClient.newBuilder().sslContext(SslContexts.forServerTrustOnly(caFile)).build();

      String writerCookie = login(client, baseUrl, "writer", "pw");
      String readerCookie = login(client, baseUrl, "reader-only", "pw");

      // An unauthenticated request (no cookie at all) 401s -- no principal to attribute it to.
      HttpRequest unauthenticated =
          HttpRequest.newBuilder(URI.create(baseUrl + "/config/tenant-1/no-cookie-key"))
              .header("Content-Type", "application/json")
              .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":\"x\",\"encrypted\":false}"))
              .build();
      assertEquals(
          401, client.send(unauthenticated, HttpResponse.BodyHandlers.discarding()).statusCode());
      assertTrue(listAuditEvents(store).isEmpty());

      // A read produces no audit event, even though it's fully authorized.
      listConfig(client, baseUrl, writerCookie, "tenant-1");
      assertTrue(listAuditEvents(store).isEmpty());

      // An allowed WRITE is audited.
      assertEquals(200, putConfig(client, baseUrl, writerCookie, "tenant-1", "k1", "v1", false));
      List<AuditEvent> afterAllowedWrite = listAuditEvents(store);
      assertEquals(1, afterAllowedWrite.size());
      assertEquals("writer", afterAllowedWrite.get(0).principal());
      assertEquals("CONFIG", afterAllowedWrite.get(0).resourceKind());
      assertEquals("WRITE", afterAllowedWrite.get(0).verb());
      assertTrue(afterAllowedWrite.get(0).allowed());

      // A denied WRITE (reader-only has no CONFIG:WRITE) is audited too -- a denial is exactly as
      // auditable as a grant.
      assertEquals(403, putConfig(client, baseUrl, readerCookie, "tenant-1", "k2", "v2", false));
      List<AuditEvent> afterDeniedWrite = listAuditEvents(store);
      assertEquals(2, afterDeniedWrite.size());
      AuditEvent denied = afterDeniedWrite.get(0);
      assertEquals("reader-only", denied.principal());
      assertFalse(denied.allowed());

      // An allowed DELETE is also audited.
      assertEquals(200, deleteConfig(client, baseUrl, writerCookie, "tenant-1", "k1"));
      assertEquals(3, listAuditEvents(store).size());
      assertEquals("DELETE", listAuditEvents(store).get(0).verb());
    }
  }

  /**
   * FUNC-24 regression: deleting a Role used to leave every {@code RoleBinding} naming it dangling
   * -- a silent-reactivation trap the moment a new Role was later created under the same name,
   * since {@code RoleBinding.roleName} is a plain string, not an immutable ID. {@code
   * StateMutation.RemoveRole} now cascades the removal atomically (covered directly against the
   * store in {@code StateStoreTest}/{@code AuthorizerTest}); this pins the HTTP-layer half: the
   * response reports exactly which bindings were revoked, and each cascaded removal gets its own
   * audit event attributed to the caller who deleted the Role.
   */
  @Test
  void deleting_a_role_over_http_cascades_its_bindings_and_reports_and_audits_the_removal()
      throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    StateStore store = inProcessStore.store();
    store.putAccount(new Account("admin", PasswordHashes.hash("pw".toCharArray())));
    store.putRole(
        new Role(
            "role-admin",
            java.util.Set.of(
                Permission.unscoped(ResourceKind.ROLE, Verb.WRITE),
                Permission.unscoped(ResourceKind.ROLE, Verb.DELETE))));
    store.putRoleBinding(
        new RoleBinding("admin-binding", RoleBinding.userSubject("admin"), "role-admin"));
    store.putRole(
        new Role(
            "reviewer", java.util.Set.of(Permission.unscoped(ResourceKind.DEPLOYMENT, Verb.READ))));
    store.putRoleBinding(new RoleBinding("b1", RoleBinding.userSubject("alice"), "reviewer"));
    store.putRoleBinding(new RoleBinding("b2", RoleBinding.groupSubject("reviewers"), "reviewer"));

    InProcessFafnir inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    try (inProcessStore;
        inProcessFafnir;
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      HttpClient client =
          HttpClient.newBuilder().sslContext(SslContexts.forServerTrustOnly(caFile)).build();
      String cookie = login(client, baseUrl, "admin", "pw");

      HttpRequest deleteRequest =
          HttpRequest.newBuilder(URI.create(baseUrl + "/roles/reviewer"))
              .header("Cookie", cookie)
              .DELETE()
              .build();
      HttpResponse<String> response =
          client.send(deleteRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, response.statusCode());
      Map<String, Object> responseBody = Json.asObject(Json.parse(response.body()));
      List<Object> removedRoleBindings = Json.asArray(responseBody.get("removedRoleBindings"));
      assertEquals(java.util.Set.of("b1", "b2"), java.util.Set.copyOf(removedRoleBindings));

      // The store itself reflects the cascade, not just the response body.
      assertTrue(store.getRole("reviewer").isEmpty());
      assertTrue(store.getRoleBinding("b1").isEmpty());
      assertTrue(store.getRoleBinding("b2").isEmpty());

      // Every audit event attributes to "admin" -- the caller who deleted the Role -- including
      // the two cascaded ROLE_BINDING removals, not just the ROLE:DELETE decision itself.
      List<AuditEvent> events = listAuditEvents(store);
      assertEquals(3, events.size());
      assertTrue(events.stream().allMatch(e -> e.principal().equals("admin")));
      assertEquals(1, events.stream().filter(e -> e.resourceKind().equals("ROLE")).count());
      List<AuditEvent> bindingEvents =
          events.stream().filter(e -> e.resourceKind().equals("ROLE_BINDING")).toList();
      assertEquals(2, bindingEvents.size());
      assertTrue(bindingEvents.stream().allMatch(AuditEvent::allowed));
      assertTrue(bindingEvents.stream().allMatch(e -> e.verb().equals("DELETE")));
      assertEquals(
          java.util.Set.of("b1", "b2"),
          bindingEvents.stream()
              .map(e -> e.targetId().orElseThrow())
              .collect(java.util.stream.Collectors.toSet()));
    }
  }

  /**
   * {@code gimle.controlplane.audit.readResourceKinds} opts a resource kind into READ-decision
   * auditing too, both allowed and denied -- but only for the kind(s) named, and a bare 401 stays
   * unaudited regardless, exactly like the WRITE/DELETE path already pinned above. Uses {@code
   * DEPLOYMENT} (not {@code CONFIG}, unlike the WRITE/DELETE test above) because {@code GET
   * /config/*} is the one list endpoint in this class that bypasses {@link
   * ApiServer#requireAuthorized} entirely for its own per-entry access-control reasons (see {@code
   * handleListConfig}'s own javadoc) -- {@code GET /deployments} is a uniform, {@code
   * requireAuthorized}-gated READ like every other resource kind's list endpoint.
   */
  @Test
  void configured_read_resource_kinds_are_audited_allowed_and_denied_reads() throws Exception {
    System.setProperty(AUDIT_READ_RESOURCE_KINDS_PROPERTY, "DEPLOYMENT");

    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    StateStore store = inProcessStore.store();
    byte[] passwordHash = PasswordHashes.hash("pw".toCharArray());
    store.putAccount(new Account("reader", passwordHash));
    store.putAccount(new Account("no-permissions", passwordHash));
    store.putRole(
        new Role(
            "deployment-reader",
            java.util.Set.of(Permission.unscoped(ResourceKind.DEPLOYMENT, Verb.READ))));
    store.putRoleBinding(
        new RoleBinding("b1", RoleBinding.userSubject("reader"), "deployment-reader"));
    // Deliberately no role binding for "no-permissions" -- every request it makes below is denied.

    InProcessFafnir inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    try (inProcessStore;
        inProcessFafnir;
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      HttpClient client =
          HttpClient.newBuilder().sslContext(SslContexts.forServerTrustOnly(caFile)).build();

      String readerCookie = login(client, baseUrl, "reader", "pw");
      String noPermissionsCookie = login(client, baseUrl, "no-permissions", "pw");

      // An allowed READ on the opted-in kind (DEPLOYMENT) is now audited.
      assertEquals(200, getDeployments(client, baseUrl, readerCookie).statusCode());
      List<AuditEvent> afterAllowedRead = listAuditEvents(store);
      assertEquals(1, afterAllowedRead.size());
      assertEquals("reader", afterAllowedRead.get(0).principal());
      assertEquals("DEPLOYMENT", afterAllowedRead.get(0).resourceKind());
      assertEquals("READ", afterAllowedRead.get(0).verb());
      assertTrue(afterAllowedRead.get(0).allowed());

      // A READ on a kind that isn't opted in (NODE) still produces nothing, proving the opt-in is
      // per-resource-kind, not "audit every READ now that the property is set at all".
      assertEquals(403, getNodes(client, baseUrl, noPermissionsCookie).statusCode());
      assertEquals(1, listAuditEvents(store).size(), "NODE is not an opted-in kind");

      // A denied READ on the opted-in kind (no-permissions has no DEPLOYMENT:READ) is audited too.
      assertEquals(403, getDeployments(client, baseUrl, noPermissionsCookie).statusCode());
      List<AuditEvent> afterDeniedRead = listAuditEvents(store);
      assertEquals(2, afterDeniedRead.size());
      AuditEvent deniedRead = afterDeniedRead.get(0);
      assertEquals("no-permissions", deniedRead.principal());
      assertEquals("DEPLOYMENT", deniedRead.resourceKind());
      assertEquals("READ", deniedRead.verb());
      assertFalse(deniedRead.allowed());

      // A bare 401 (no principal at all) is still never audited, opt-in or not.
      HttpRequest unauthenticated =
          HttpRequest.newBuilder(URI.create(baseUrl + "/deployments")).GET().build();
      assertEquals(
          401, client.send(unauthenticated, HttpResponse.BodyHandlers.discarding()).statusCode());
      assertEquals(2, listAuditEvents(store).size());
    }
  }

  /**
   * A {@code group:} {@link RoleBinding} subject only ever matched a certificate-authenticated
   * principal, never a session-cookie one -- every session principal was built with hardcoded empty
   * groups. This pins the fix: a session-authenticated principal's groups now come from a live
   * {@code Account.groups()} read, so a {@code group:} binding grants access to any account
   * currently carrying that group, and revokes it the moment the account's groups change -- proving
   * groups are read fresh per request rather than baked into the session token at login.
   */
  @Test
  void group_role_binding_authorizes_a_session_authenticated_principal() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    StateStore store = inProcessStore.store();
    byte[] passwordHash = PasswordHashes.hash("pw".toCharArray());
    store.putAccount(new Account("group-member", passwordHash, java.util.Set.of("qa-team")));
    store.putAccount(new Account("no-group", passwordHash));
    store.putRole(
        new Role(
            "deployment-reader",
            java.util.Set.of(Permission.unscoped(ResourceKind.DEPLOYMENT, Verb.READ))));
    store.putRoleBinding(
        new RoleBinding("b1", RoleBinding.groupSubject("qa-team"), "deployment-reader"));

    InProcessFafnir inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    try (inProcessStore;
        inProcessFafnir;
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      HttpClient client =
          HttpClient.newBuilder().sslContext(SslContexts.forServerTrustOnly(caFile)).build();

      String groupMemberCookie = login(client, baseUrl, "group-member", "pw");
      String noGroupCookie = login(client, baseUrl, "no-group", "pw");

      // The group binding grants access to the account carrying that group...
      assertEquals(200, getDeployments(client, baseUrl, groupMemberCookie).statusCode());
      // ...and denies it to an account with no group membership at all.
      assertEquals(403, getDeployments(client, baseUrl, noGroupCookie).statusCode());

      // Groups are read live from the store per request, not baked into the session token: removing
      // the group from the account revokes access on the very next request, no re-login needed.
      store.putAccount(new Account("group-member", passwordHash));
      assertEquals(403, getDeployments(client, baseUrl, groupMemberCookie).statusCode());

      // ...and granting the group to a previously ungrouped account's existing session grants
      // access
      // the same way, live.
      store.putAccount(new Account("no-group", passwordHash, java.util.Set.of("qa-team")));
      assertEquals(200, getDeployments(client, baseUrl, noGroupCookie).statusCode());
    }
  }

  private static HttpResponse<Void> getDeployments(HttpClient client, String baseUrl, String cookie)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments"))
            .header("Cookie", cookie)
            .GET()
            .build();
    return client.send(request, HttpResponse.BodyHandlers.discarding());
  }

  private static HttpResponse<Void> getNodes(HttpClient client, String baseUrl, String cookie)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/nodes"))
            .header("Cookie", cookie)
            .GET()
            .build();
    return client.send(request, HttpResponse.BodyHandlers.discarding());
  }

  private static List<AuditEvent> listAuditEvents(StateStore store) {
    return store.listAuditEvents(
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
  }

  /**
   * Key rotation end to end: a secret written before rotation stays readable afterward
   * (re-encrypted under the new key, transparently to the caller), and a secret written after
   * rotation round-trips too.
   */
  @Test
  void a_secret_survives_key_rotation_and_new_secrets_use_the_rotated_key() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    StateStore store = inProcessStore.store();
    store.putAccount(new Account("admin", PasswordHashes.hash("pw".toCharArray())));
    store.putRole(
        new Role(
            "secret-admin",
            java.util.Set.of(
                Permission.unscoped(ResourceKind.SECRET, Verb.READ),
                Permission.unscoped(ResourceKind.SECRET, Verb.WRITE))));
    store.putRoleBinding(new RoleBinding("b1", RoleBinding.userSubject("admin"), "secret-admin"));
    // The rotation walk only visits registered Tenants (see #rotateSecretsKey's own javadoc for
    // why), so this tenant must actually be registered for the walk to reach its config entries.
    store.putTenant(new Tenant("tenant-1", new ResourceQuota(1024, 500, 10)));

    InProcessFafnir inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/fafnir-secret.key"));
    try (inProcessStore;
        inProcessFafnir;
        ApiServer server =
            new ApiServer(
                inProcessStore.client(),
                0,
                tempDir.resolve("keys/session-secret.key"),
                inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      HttpClient client =
          HttpClient.newBuilder().sslContext(SslContexts.forServerTrustOnly(caFile)).build();
      String cookie = login(client, baseUrl, "admin", "pw");

      assertEquals(
          200, putConfig(client, baseUrl, cookie, "tenant-1", "before", "value-before", true));

      HttpRequest rotateRequest =
          HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/rotate-key"))
              .header("Cookie", cookie)
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      HttpResponse<String> rotateResponse =
          client.send(rotateRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, rotateResponse.statusCode());
      assertEquals(1L, Json.asObject(Json.parse(rotateResponse.body())).get("activeKeyId"));

      assertEquals(
          200, putConfig(client, baseUrl, cookie, "tenant-1", "after", "value-after", true));

      List<Map<String, Object>> entries = listConfig(client, baseUrl, cookie, "tenant-1");
      Map<String, Object> byKey = new LinkedHashMap<>();
      for (Map<String, Object> entry : entries) {
        byKey.put((String) entry.get("key"), entry.get("value"));
      }
      assertEquals("value-before", byKey.get("before"));
      assertEquals("value-after", byKey.get("after"));
    }
  }

  /**
   * Closes the tenant-scope gap {@link ApiServer#dispatchResourceRequest}'s own javadoc used to
   * document as a known, deliberate omission: a {@code DEPLOYMENT:WRITE} permission scoped to one
   * tenant now actually authorizes that tenant's own deployment writes -- and only that tenant's,
   * never another tenant's or an untenanted one -- rather than being permanently unusable for every
   * workload route (a scoped tenant parameter can only ever match a permission's own identical
   * scope, and every workload route hardcoded {@code Optional.empty()} before this fix, which only
   * an unscoped permission ever matches).
   */
  @Test
  void a_tenant_scoped_write_permission_authorizes_only_that_tenants_deployments()
      throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    StateStore store = inProcessStore.store();
    store.putTenant(new Tenant("acme", new ResourceQuota(1_000_000_000L, 4000, 10)));
    store.putTenant(new Tenant("other", new ResourceQuota(1_000_000_000L, 4000, 10)));
    store.putAccount(new Account("acme-writer", PasswordHashes.hash("pw".toCharArray())));
    store.putRole(
        new Role(
            "acme-deployment-writer",
            java.util.Set.of(Permission.scoped(ResourceKind.DEPLOYMENT, Verb.WRITE, "acme"))));
    store.putRoleBinding(
        new RoleBinding("b1", RoleBinding.userSubject("acme-writer"), "acme-deployment-writer"));

    InProcessFafnir inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    try (inProcessStore;
        inProcessFafnir;
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      HttpClient client =
          HttpClient.newBuilder().sslContext(SslContexts.forServerTrustOnly(caFile)).build();
      String cookie = login(client, baseUrl, "acme-writer", "pw");

      Path jar = buildFixtureJar("com.gimle.fixture.authz.acme");
      assertEquals(
          200,
          putDeployment(
              client,
              baseUrl,
              cookie,
              "acme-dep",
              "com.gimle.fixture.authz.acme",
              jar,
              Optional.of("acme")));
      assertEquals(
          403,
          putDeployment(
              client,
              baseUrl,
              cookie,
              "other-dep",
              "com.gimle.fixture.authz.acme",
              jar,
              Optional.of("other")));
      assertEquals(
          403,
          putDeployment(
              client,
              baseUrl,
              cookie,
              "untenanted-dep",
              "com.gimle.fixture.authz.acme",
              jar,
              Optional.empty()));
    }
  }

  /**
   * The GET/DELETE half of the same fix: authorized against the resource's own currently stored
   * {@code tenantId}, resolved by {@link ApiServer#dispatchResourceRequest} before dispatching --
   * not the submitter's own choice of tenant, since there is no submitted manifest to read one from
   * on these two verbs.
   */
  @Test
  void tenant_scoped_read_and_delete_are_bounded_by_the_deployments_own_stored_tenant()
      throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    StateStore store = inProcessStore.store();
    store.putDeployment(deployment("acme-dep", Optional.of("acme")));
    store.putDeployment(deployment("other-dep", Optional.of("other")));
    store.putAccount(new Account("acme-reader", PasswordHashes.hash("pw".toCharArray())));
    store.putRole(
        new Role(
            "acme-deployment-reader",
            java.util.Set.of(
                Permission.scoped(ResourceKind.DEPLOYMENT, Verb.READ, "acme"),
                Permission.scoped(ResourceKind.DEPLOYMENT, Verb.DELETE, "acme"))));
    store.putRoleBinding(
        new RoleBinding("b1", RoleBinding.userSubject("acme-reader"), "acme-deployment-reader"));

    InProcessFafnir inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    try (inProcessStore;
        inProcessFafnir;
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      HttpClient client =
          HttpClient.newBuilder().sslContext(SslContexts.forServerTrustOnly(caFile)).build();
      String cookie = login(client, baseUrl, "acme-reader", "pw");

      assertEquals(200, getDeployment(client, baseUrl, cookie, "acme-dep").statusCode());
      assertEquals(403, getDeployment(client, baseUrl, cookie, "other-dep").statusCode());
      // A nonexistent name resolves to Optional.empty(), which only an unscoped permission
      // covers -- a scoped-only caller is forbidden, not told 404, so it can't distinguish
      // "doesn't exist" from "exists under a tenant I can't see" by probing names.
      assertEquals(403, getDeployment(client, baseUrl, cookie, "does-not-exist").statusCode());

      assertEquals(403, deleteDeployment(client, baseUrl, cookie, "other-dep").statusCode());
      assertEquals(200, deleteDeployment(client, baseUrl, cookie, "acme-dep").statusCode());
    }
  }

  /**
   * The collection-listing half of tenant scoping: a caller holding only a tenant-scoped read grant
   * -- here the built-in {@code tenant-view:<id>} template, the exact shape that was locked out of
   * every list endpoint when those handlers demanded an unscoped grant -- gets a 200 with exactly
   * its own tenant's items, never a 403 and never another tenant's (or an untenanted) item. A
   * caller with no read grant at all still gets the plain 403.
   */
  @Test
  void a_tenant_view_binding_lists_only_its_own_tenants_deployments() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    StateStore store = inProcessStore.store();
    store.putDeployment(deployment("acme-dep", Optional.of("acme")));
    store.putDeployment(deployment("other-dep", Optional.of("other")));
    store.putDeployment(deployment("untenanted-dep", Optional.empty()));
    byte[] passwordHash = PasswordHashes.hash("pw".toCharArray());
    store.putAccount(new Account("acme-viewer", passwordHash));
    store.putAccount(new Account("no-permissions", passwordHash));
    // tenant-view:acme is a built-in template, never stored -- only the binding exists.
    store.putRoleBinding(
        new RoleBinding("b1", RoleBinding.userSubject("acme-viewer"), "tenant-view:acme"));

    InProcessFafnir inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    try (inProcessStore;
        inProcessFafnir;
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      HttpClient client =
          HttpClient.newBuilder().sslContext(SslContexts.forServerTrustOnly(caFile)).build();

      String viewerCookie = login(client, baseUrl, "acme-viewer", "pw");
      HttpRequest listRequest =
          HttpRequest.newBuilder(URI.create(baseUrl + "/deployments"))
              .header("Cookie", viewerCookie)
              .GET()
              .build();
      HttpResponse<String> listed =
          client.send(listRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, listed.statusCode());
      assertTrue(listed.body().contains("acme-dep"), listed.body());
      assertFalse(listed.body().contains("other-dep"), listed.body());
      assertFalse(listed.body().contains("untenanted-dep"), listed.body());

      // The single-resource read the same template grants, for the same tenant only.
      assertEquals(200, getDeployment(client, baseUrl, viewerCookie, "acme-dep").statusCode());
      assertEquals(403, getDeployment(client, baseUrl, viewerCookie, "other-dep").statusCode());

      String noPermissionsCookie = login(client, baseUrl, "no-permissions", "pw");
      assertEquals(403, getDeployments(client, baseUrl, noPermissionsCookie).statusCode());
    }
  }

  /**
   * A same-named resource under a different tenant is a wholly independent one, not the same
   * resource "moved" -- each lives under its own {@code (tenantId, name)} store key (see {@code
   * StateStore}'s own tenant scoping), so a write scoped to one tenant is sufficient by itself to
   * create or update that tenant's own "shared", regardless of what any other tenant's identically-
   * named "shared" holds, and never touches it.
   */
  @Test
  void a_write_permission_scoped_to_one_tenant_creates_its_own_independent_same_named_deployment()
      throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    StateStore store = inProcessStore.store();
    store.putTenant(new Tenant("acme", new ResourceQuota(1_000_000_000L, 4000, 10)));
    store.putDeployment(deployment("shared", Optional.of("beta")));
    store.putAccount(new Account("acme-writer", PasswordHashes.hash("pw".toCharArray())));
    store.putRole(
        new Role(
            "acme-deployment-writer",
            java.util.Set.of(Permission.scoped(ResourceKind.DEPLOYMENT, Verb.WRITE, "acme"))));
    store.putRoleBinding(
        new RoleBinding("b1", RoleBinding.userSubject("acme-writer"), "acme-deployment-writer"));

    InProcessFafnir inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    try (inProcessStore;
        inProcessFafnir;
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      HttpClient client =
          HttpClient.newBuilder().sslContext(SslContexts.forServerTrustOnly(caFile)).build();
      String cookie = login(client, baseUrl, "acme-writer", "pw");

      Path jar = buildFixtureJar("com.gimle.fixture.authz.reclaim");
      // acme-writer holds DEPLOYMENT:WRITE:acme but not :beta -- that alone is sufficient to
      // create acme's own "shared", since it's a distinct store key from beta's.
      assertEquals(
          200,
          putDeployment(
              client,
              baseUrl,
              cookie,
              "shared",
              "com.gimle.fixture.authz.reclaim",
              jar,
              Optional.of("acme")));
      assertEquals(
          Optional.of("beta"),
          store.getDeployment(Optional.of("beta"), "shared").flatMap(DeploymentSpec::tenantId));
      assertEquals(
          Optional.of("acme"),
          store.getDeployment(Optional.of("acme"), "shared").flatMap(DeploymentSpec::tenantId));
    }
  }

  /**
   * A broad, unscoped {@code TENANT:WRITE}/{@code TENANT:DELETE} grant is exactly the shape a human
   * operator might hand out for legitimate day-to-day tenant administration -- and exactly the
   * shape that must still not reach {@code gimle-system}, since it carries no proof of the
   * bootstrap-level operator credential this reserved tenant actually requires.
   */
  @Test
  void a_broad_unscoped_tenant_permission_still_cannot_touch_gimle_system() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    StateStore store = inProcessStore.store();
    store.putAccount(new Account("tenant-admin", PasswordHashes.hash("pw".toCharArray())));
    store.putRole(
        new Role(
            "unscoped-tenant-admin",
            java.util.Set.of(
                Permission.unscoped(ResourceKind.TENANT, Verb.WRITE),
                Permission.unscoped(ResourceKind.TENANT, Verb.DELETE))));
    store.putRoleBinding(
        new RoleBinding("b1", RoleBinding.userSubject("tenant-admin"), "unscoped-tenant-admin"));

    InProcessFafnir inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    try (inProcessStore;
        inProcessFafnir;
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      HttpClient client =
          HttpClient.newBuilder().sslContext(SslContexts.forServerTrustOnly(caFile)).build();
      String cookie = login(client, baseUrl, "tenant-admin", "pw");

      assertEquals(403, putTenant(client, baseUrl, cookie, "gimle-system").statusCode());
      assertEquals(403, deleteTenant(client, baseUrl, cookie, "gimle-system").statusCode());
      // The same unscoped grant still works on an ordinary tenant name -- the veto is specific to
      // gimle-system, not a general breakage of TENANT:WRITE/DELETE.
      assertEquals(200, putTenant(client, baseUrl, cookie, "ordinary-tenant").statusCode());
      assertEquals(200, deleteTenant(client, baseUrl, cookie, "ordinary-tenant").statusCode());
    }
  }

  /**
   * The workload-admission half of the same guard, proven for all five kinds individually since
   * each routes through {@code dispatchResourceRequest}'s shared PUT branch differently (Job/
   * DaemonSet/StatefulSet skip {@code TenantQuotaPlugin} entirely, CronJob resolves its own tenant
   * via a sub-route name resolver) -- a broad, unscoped write grant per kind must still be unable
   * to plant a workload inside gimle-system.
   */
  @Test
  void a_broad_unscoped_workload_write_permission_cannot_submit_into_gimle_system()
      throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    StateStore store = inProcessStore.store();
    store.putAccount(new Account("workload-admin", PasswordHashes.hash("pw".toCharArray())));
    store.putRole(
        new Role(
            "unscoped-workload-admin",
            java.util.Set.of(
                Permission.unscoped(ResourceKind.DEPLOYMENT, Verb.WRITE),
                Permission.unscoped(ResourceKind.JOB, Verb.WRITE),
                Permission.unscoped(ResourceKind.DAEMONSET, Verb.WRITE),
                Permission.unscoped(ResourceKind.STATEFULSET, Verb.WRITE))));
    store.putRoleBinding(
        new RoleBinding(
            "b1", RoleBinding.userSubject("workload-admin"), "unscoped-workload-admin"));

    InProcessFafnir inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    try (inProcessStore;
        inProcessFafnir;
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      HttpClient client =
          HttpClient.newBuilder().sslContext(SslContexts.forServerTrustOnly(caFile)).build();
      String cookie = login(client, baseUrl, "workload-admin", "pw");
      Path jar = buildFixtureJar("com.gimle.fixture.authz.reserved");

      assertEquals(
          403,
          putDeployment(
              client,
              baseUrl,
              cookie,
              "sys-dep",
              "com.gimle.fixture.authz.reserved",
              jar,
              Optional.of("gimle-system")));
      assertEquals(403, putJob(client, baseUrl, cookie, "sys-job", Optional.of("gimle-system")));
      assertEquals(
          403, putDaemonSet(client, baseUrl, cookie, "sys-ds", Optional.of("gimle-system")));
      assertEquals(
          403, putStatefulSet(client, baseUrl, cookie, "sys-sts", Optional.of("gimle-system")));
      assertEquals(403, putCronJob(client, baseUrl, cookie, "sys-cj", Optional.of("gimle-system")));
    }
  }

  /**
   * The credential tier that *is* meant to reach gimle-system: an operator-group certificate
   * succeeds at both tenant CRUD and submitting every workload kind into it, proving the guard
   * vetoes ordinary grants specifically, not gimle-system itself.
   */
  @Test
  void an_operator_group_caller_can_manage_gimle_system_and_its_workloads() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
        InProcessFafnir inProcessFafnir =
            InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      HttpClient operatorClient = mutualTlsClient(ca, "O=gimle:operators,CN=root-operator");
      Path jar = buildFixtureJar("com.gimle.fixture.authz.operator");

      assertEquals(
          200,
          operatorPutTenant(operatorClient, baseUrl, "gimle-system").statusCode(),
          "an operator can still rewrite the reserved tenant's own quota");
      assertEquals(
          200,
          operatorPutDeployment(
              operatorClient,
              baseUrl,
              "sys-dep",
              "com.gimle.fixture.authz.operator",
              jar,
              "gimle-system"),
          "deployment");
      assertEquals(
          200,
          operatorPutJob(
              operatorClient,
              baseUrl,
              "sys-job",
              "com.gimle.fixture.authz.operator",
              jar,
              "gimle-system"),
          "job");
      assertEquals(
          200,
          operatorPutDaemonSet(
              operatorClient,
              baseUrl,
              "sys-ds",
              "com.gimle.fixture.authz.operator",
              jar,
              "gimle-system"),
          "daemonset");
      assertEquals(
          200,
          operatorPutStatefulSet(
              operatorClient,
              baseUrl,
              "sys-sts",
              "com.gimle.fixture.authz.operator",
              jar,
              "gimle-system"),
          "statefulset");
      assertEquals(
          200, operatorPutCronJob(operatorClient, baseUrl, "sys-cj", "gimle-system"), "cronjob");
      assertEquals(
          200,
          operatorDeleteTenant(operatorClient, baseUrl, "gimle-system").statusCode(),
          "an operator can delete the reserved tenant itself if it chooses to");
    }
  }

  private static HttpResponse<String> putTenant(
      HttpClient client, String baseUrl, String cookie, String id)
      throws IOException, InterruptedException {
    String body =
        "{\"quota\":{\"maxMemoryBytes\":1024,\"maxCpuMillicores\":500,\"maxInstances\":5}}";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/" + id))
            .header("Content-Type", "application/json")
            .header("Cookie", cookie)
            .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> deleteTenant(
      HttpClient client, String baseUrl, String cookie, String id)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/" + id))
            .header("Cookie", cookie)
            .DELETE()
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> operatorPutTenant(
      HttpClient operatorClient, String baseUrl, String id)
      throws IOException, InterruptedException {
    // Generous, not the tiny quota putTenant/deleteTenant use above -- this same tenant is about
    // to receive a real, quota-checked Deployment submission below.
    String body =
        "{\"quota\":{\"maxMemoryBytes\":1000000000,\"maxCpuMillicores\":4000,\"maxInstances\":100}}";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/" + id))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    return operatorClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> operatorDeleteTenant(
      HttpClient operatorClient, String baseUrl, String id)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/" + id)).DELETE().build();
    return operatorClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static int putJob(
      HttpClient client, String baseUrl, String cookie, String name, Optional<String> tenantId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/jobs/" + name))
            .header("Cookie", cookie)
            .PUT(
                HttpRequest.BodyPublishers.ofString(
                    jobYaml(name, tenantId), StandardCharsets.UTF_8))
            .build();
    return client
        .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        .statusCode();
  }

  /**
   * Unlike {@link #putJob}'s own {@link #jobYaml} (fine for an unenforceable, e.g. {@code default},
   * tenant -- {@link com.gimle.controlplane.admission.TenantQuotaPlugin} never reads its bogus
   * artifact path at all there), {@code gimle-system} is a real, quota-enforced tenant, so this
   * needs a real, readable jar the same way {@link #operatorPutDeployment} already does -- an
   * unreadable path would otherwise reject with 409 before ever reaching the RBAC decision this
   * test exists to exercise.
   */
  private static int operatorPutJob(
      HttpClient operatorClient,
      String baseUrl,
      String name,
      String moduleName,
      Path jar,
      String tenantId)
      throws IOException, InterruptedException {
    String yaml =
        """
        kind: Job
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        backoffLimit: 3
        tenantId: %s
        """
            .formatted(name, moduleName, jar.toAbsolutePath(), tenantId);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/jobs/" + name))
            .PUT(HttpRequest.BodyPublishers.ofString(yaml, StandardCharsets.UTF_8))
            .build();
    return operatorClient
        .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        .statusCode();
  }

  private static String jobYaml(String name, Optional<String> tenantId) {
    String tenantLine = tenantId.map(id -> "tenantId: " + id + "\n").orElse("");
    return """
        kind: Job
        name: %s
        module:
          name: com.gimle.example.cleanup
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
        backoffLimit: 3
        %s"""
        .formatted(name, tenantLine);
  }

  private static int putDaemonSet(
      HttpClient client, String baseUrl, String cookie, String name, Optional<String> tenantId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets/" + name))
            .header("Cookie", cookie)
            .PUT(
                HttpRequest.BodyPublishers.ofString(
                    daemonSetYaml(name, tenantId), StandardCharsets.UTF_8))
            .build();
    return client
        .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        .statusCode();
  }

  /**
   * Like {@link #operatorPutJob}, {@code gimle-system} is a real, quota-enforced tenant, so this
   * needs a real, readable jar rather than {@link #daemonSetYaml}'s own bogus artifact path.
   */
  private static int operatorPutDaemonSet(
      HttpClient operatorClient,
      String baseUrl,
      String name,
      String moduleName,
      Path jar,
      String tenantId)
      throws IOException, InterruptedException {
    String yaml =
        """
        kind: DaemonSet
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        tenantId: %s
        """
            .formatted(name, moduleName, jar.toAbsolutePath(), tenantId);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets/" + name))
            .PUT(HttpRequest.BodyPublishers.ofString(yaml, StandardCharsets.UTF_8))
            .build();
    return operatorClient
        .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        .statusCode();
  }

  private static String daemonSetYaml(String name, Optional<String> tenantId) {
    String tenantLine = tenantId.map(id -> "tenantId: " + id + "\n").orElse("");
    return """
        kind: DaemonSet
        name: %s
        module:
          name: com.gimle.example.node-exporter
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/node-exporter-1.0.0.jar
        %s"""
        .formatted(name, tenantLine);
  }

  private static int putStatefulSet(
      HttpClient client, String baseUrl, String cookie, String name, Optional<String> tenantId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/statefulsets/" + name))
            .header("Cookie", cookie)
            .PUT(
                HttpRequest.BodyPublishers.ofString(
                    statefulSetYaml(name, tenantId), StandardCharsets.UTF_8))
            .build();
    return client
        .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        .statusCode();
  }

  /**
   * Like {@link #operatorPutJob}, {@code gimle-system} is a real, quota-enforced tenant, so this
   * needs a real, readable jar rather than {@link #statefulSetYaml}'s own bogus artifact path.
   */
  private static int operatorPutStatefulSet(
      HttpClient operatorClient,
      String baseUrl,
      String name,
      String moduleName,
      Path jar,
      String tenantId)
      throws IOException, InterruptedException {
    String yaml =
        """
        kind: StatefulSet
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        replicas: 1
        tenantId: %s
        """
            .formatted(name, moduleName, jar.toAbsolutePath(), tenantId);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/statefulsets/" + name))
            .PUT(HttpRequest.BodyPublishers.ofString(yaml, StandardCharsets.UTF_8))
            .build();
    return operatorClient
        .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        .statusCode();
  }

  private static String statefulSetYaml(String name, Optional<String> tenantId) {
    String tenantLine = tenantId.map(id -> "tenantId: " + id + "\n").orElse("");
    return """
        kind: StatefulSet
        name: %s
        module:
          name: com.gimle.example.orders
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
        replicas: 1
        %s"""
        .formatted(name, tenantLine);
  }

  private static int putCronJob(
      HttpClient client, String baseUrl, String cookie, String name, Optional<String> tenantId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/" + name))
            .header("Cookie", cookie)
            .PUT(
                HttpRequest.BodyPublishers.ofString(
                    cronJobYaml(name, tenantId), StandardCharsets.UTF_8))
            .build();
    return client
        .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        .statusCode();
  }

  private static int operatorPutCronJob(
      HttpClient operatorClient, String baseUrl, String name, String tenantId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/" + name))
            .PUT(
                HttpRequest.BodyPublishers.ofString(
                    cronJobYaml(name, Optional.of(tenantId)), StandardCharsets.UTF_8))
            .build();
    return operatorClient
        .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        .statusCode();
  }

  private static String cronJobYaml(String name, Optional<String> tenantId) {
    String tenantLine = tenantId.map(id -> "tenantId: " + id + "\n").orElse("");
    return """
        kind: CronJob
        name: %s
        schedule: "0 2 * * *"
        jobTemplate:
          module:
            name: com.gimle.example.cleanup
            version: 1.0.0
          artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
          backoffLimit: 3
        %s"""
        .formatted(name, tenantLine);
  }

  private static int operatorPutDeployment(
      HttpClient operatorClient,
      String baseUrl,
      String name,
      String moduleName,
      Path jar,
      String tenantId)
      throws IOException, InterruptedException {
    String yaml =
        """
        kind: Deployment
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        replicas: 1
        tenantId: %s
        """
            .formatted(name, moduleName, jar.toAbsolutePath(), tenantId);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name))
            .PUT(HttpRequest.BodyPublishers.ofString(yaml, StandardCharsets.UTF_8))
            .build();
    return operatorClient
        .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        .statusCode();
  }

  private DeploymentSpec deployment(String name, Optional<String> tenantId) {
    return new DeploymentSpec(
        name,
        new ModuleId(name, Version.parse("1.0.0")),
        tempDir.resolve(name + ".jar").toAbsolutePath().toString(),
        1,
        PlacementConstraints.NONE,
        Optional.empty(),
        tenantId,
        Optional.empty(),
        Optional.empty());
  }

  private Path buildFixtureJar(String uniqueName) {
    return TestModuleBuilder.module("module " + uniqueName + " {\n}\n")
        .withDescriptor(TestModuleBuilder.minimalDescriptor(uniqueName, "1.0.0"))
        .build(tempDir, uniqueName + ".jar");
  }

  private static int putDeployment(
      HttpClient client,
      String baseUrl,
      String cookie,
      String name,
      String moduleName,
      Path jar,
      Optional<String> tenantId)
      throws IOException, InterruptedException {
    String tenantLine = tenantId.map(id -> "tenantId: " + id + "\n").orElse("");
    String yaml =
        """
        kind: Deployment
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        replicas: 1
        %s"""
            .formatted(name, moduleName, jar.toAbsolutePath(), tenantLine);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name))
            .header("Cookie", cookie)
            .PUT(HttpRequest.BodyPublishers.ofString(yaml, StandardCharsets.UTF_8))
            .build();
    return client
        .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        .statusCode();
  }

  private static HttpResponse<String> getDeployment(
      HttpClient client, String baseUrl, String cookie, String name)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name))
            .header("Cookie", cookie)
            .GET()
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> deleteDeployment(
      HttpClient client, String baseUrl, String cookie, String name)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name))
            .header("Cookie", cookie)
            .DELETE()
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /**
   * The self-subject access review answers from the identical {@link
   * com.gimle.mimir.authz.Authorizer} walk enforcement uses -- proven here through a per-tenant
   * built-in template binding, so this also covers template resolution at the real HTTP layer.
   */
  @Test
  void can_i_answers_for_the_calling_principal_without_performing_anything() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    StateStore store = inProcessStore.store();
    store.putAccount(new Account("grace", PasswordHashes.hash("pw".toCharArray())));
    store.putRoleBinding(
        new RoleBinding("b1", RoleBinding.userSubject("grace"), "tenant-edit:acme"));

    InProcessFafnir inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    try (inProcessStore;
        inProcessFafnir;
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      HttpClient client =
          HttpClient.newBuilder().sslContext(SslContexts.forServerTrustOnly(caFile)).build();
      String cookie = login(client, baseUrl, "grace", "pw");

      assertTrue(canI(client, baseUrl, cookie, "resource=DEPLOYMENT&verb=WRITE&tenant=acme"));
      assertTrue(canI(client, baseUrl, cookie, "resource=SECRET&verb=READ&tenant=acme"));
      assertFalse(canI(client, baseUrl, cookie, "resource=DEPLOYMENT&verb=WRITE&tenant=umbrella"));
      assertFalse(canI(client, baseUrl, cookie, "resource=NETWORK_POLICY&verb=WRITE&tenant=acme"));

      // A malformed review is a 400 naming the problem, and an unauthenticated one a 401 --
      // asking about yourself needs an identity, just not any permission.
      HttpResponse<String> badResource =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(baseUrl + "/authz/can-i?resource=NOT_A_KIND&verb=READ"))
                  .header("Cookie", cookie)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(400, badResource.statusCode());

      HttpResponse<String> anonymous =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(baseUrl + "/authz/can-i?resource=DEPLOYMENT&verb=READ"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(401, anonymous.statusCode());
    }
  }

  /**
   * The permission vocabulary is served from the live enums, so a picker driven by it can never
   * offer fewer kinds than the {@code Authorizer} on the other end accepts. Authenticated but
   * ungated: the caller here holds no role binding at all and still gets the full answer, since
   * there is nothing principal-specific in it to withhold.
   */
  @Test
  void authz_vocabulary_serves_every_resource_kind_and_verb_this_build_enforces() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    inProcessStore
        .store()
        .putAccount(new Account("nobody", PasswordHashes.hash("pw".toCharArray())));

    InProcessFafnir inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    try (inProcessStore;
        inProcessFafnir;
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      HttpClient client =
          HttpClient.newBuilder().sslContext(SslContexts.forServerTrustOnly(caFile)).build();
      String cookie = login(client, baseUrl, "nobody", "pw");

      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/authz/vocabulary"))
                  .header("Cookie", cookie)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, response.statusCode());
      Map<String, Object> body = Json.asObject(Json.parse(response.body()));
      assertEquals(
          Arrays.stream(ResourceKind.values()).map(Enum::name).toList(),
          Json.asArray(body.get("resourceKinds")));
      assertEquals(
          Arrays.stream(Verb.values()).map(Enum::name).toList(), Json.asArray(body.get("verbs")));

      // Read-only, and still an identity-gated route like its /authz/can-i neighbour.
      HttpResponse<String> written =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/authz/vocabulary"))
                  .header("Cookie", cookie)
                  .POST(HttpRequest.BodyPublishers.ofString("{}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(405, written.statusCode());

      HttpResponse<String> anonymous =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/authz/vocabulary")).GET().build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(401, anonymous.statusCode());
    }
  }

  /**
   * The portable revocation flow end to end: a valid, in-date operator certificate authenticates
   * until its serial lands on the store-backed denylist, is refused with a bare 401 from the very
   * next request on (no authorization ever runs for it), reappears in the revocation listing, and
   * authenticates again once un-revoked -- revocation is a reversible store entry, not a destroyed
   * credential.
   */
  @Test
  void a_revoked_certificate_stops_authenticating_until_unrevoked() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
        InProcessFafnir inProcessFafnir =
            InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();

      KeyPair keyPair = generateRsaKeyPair();
      PKCS10CertificationRequest csr =
          CertificateSigningRequests.generate(
              keyPair, new X500Name("O=gimle:operators,CN=revocable-operator"));
      X509Certificate leaf = ca.signCertificateRequest(csr, Duration.ofDays(1));
      Path certFile = writePem("revocable-cert.pem", Pem.encodeCertificate(leaf));
      Path keyFile = writePem("revocable-key.pem", Pem.encodePrivateKey(keyPair.getPrivate()));
      HttpClient revocable =
          HttpClient.newBuilder()
              .sslContext(SslContexts.forMutualTls(new TlsSettings(certFile, keyFile, caFile)))
              .build();
      String serial = leaf.getSerialNumber().toString(16);
      HttpClient admin = mutualTlsClient(ca, "O=gimle:operators,CN=root-operator");

      assertEquals(200, get(revocable, baseUrl + "/tenants").statusCode());

      HttpResponse<String> revoke =
          admin.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/certificates/revoked/" + serial))
                  .PUT(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, revoke.statusCode());

      assertEquals(401, get(revocable, baseUrl + "/tenants").statusCode());
      HttpResponse<String> listing = get(admin, baseUrl + "/certificates/revoked");
      assertEquals(200, listing.statusCode());
      assertTrue(listing.body().contains(serial));

      HttpResponse<String> unrevoke =
          admin.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/certificates/revoked/" + serial))
                  .DELETE()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, unrevoke.statusCode());
      assertEquals(200, get(revocable, baseUrl + "/tenants").statusCode());
    }
  }

  /**
   * The ServiceAccount-analogue flow end to end over real mTLS: the owning node's agent identity
   * mints a token for its assigned deployment (a foreign node is refused), the token alone -- no
   * client certificate at all -- resolves the workload principal, which is denied everything until
   * an operator binds it a role, then reads exactly its own tenant's resources; garbage tokens
   * resolve nothing.
   */
  @Test
  void a_workload_token_carries_deny_by_default_rbac_identity() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    StateStore store = inProcessStore.store();
    store.putTenant(new Tenant("acme", new ResourceQuota(1_000_000_000L, 10_000, 100)));
    store.putDeployment(
        new DeploymentSpec(
            "orders-service",
            new ModuleId("com.example.orders", Version.parse("1.0.0")),
            "/tmp/orders.jar",
            1,
            PlacementConstraints.NONE,
            Optional.empty(),
            Optional.of("acme")));
    // Tenanted to match the deployment above -- workloadTenantId resolves the mint's tenant off
    // the assignment's own tenantId, not the deployment spec's, so an untenanted assignment here
    // would make this deployment look untenanted to the mint endpoint.
    store.putAssignment(
        new InstanceAssignment(
            "orders-service",
            0,
            "node-a",
            InstanceAssignment.UNSPECIFIED_MODULE,
            "",
            OptionalInt.empty(),
            Optional.of("acme")));

    InProcessFafnir inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    try (inProcessStore;
        inProcessFafnir;
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      HttpClient owningNode = mutualTlsClient(ca, "O=gimle:nodes,CN=node-a");
      HttpClient foreignNode = mutualTlsClient(ca, "O=gimle:nodes,CN=node-b");
      HttpClient tokenOnly =
          HttpClient.newBuilder().sslContext(SslContexts.forServerTrustOnly(caFile)).build();

      String mintBody = "{\"deploymentName\": \"orders-service\", \"nodeId\": \"node-a\"}";
      // A foreign node may not mint for node-a's assignment; the owning node may.
      assertEquals(403, mint(foreignNode, baseUrl, mintBody).statusCode());
      HttpResponse<String> minted = mint(owningNode, baseUrl, mintBody);
      assertEquals(200, minted.statusCode());
      String token = String.valueOf(Json.asObject(Json.parse(minted.body())).get("token"));

      // The bare token resolves the workload principal -- authenticated but deny-by-default.
      assertEquals(
          403, bearerGet(tokenOnly, baseUrl, "/deployments/orders-service", token).statusCode());
      // Garbage resolves nothing at all.
      assertEquals(
          401,
          bearerGet(tokenOnly, baseUrl, "/deployments/orders-service", "junk:beef").statusCode());

      store.putRoleBinding(
          new RoleBinding(
              "wb1", RoleBinding.userSubject("svc:acme:orders-service"), "tenant-view:acme"));

      HttpResponse<String> read =
          bearerGet(tokenOnly, baseUrl, "/deployments/orders-service", token);
      assertEquals(200, read.statusCode());
    }
  }

  /**
   * ADD-7: minting used to resolve {@code deploymentName} against {@code storeClient.getDeployment}
   * alone, so a tenanted DaemonSet (or Job/StatefulSet) instance's own mint attempt always 404'd --
   * permanently blocking every {@code relayControlPlaneRead} call a non-Deployment workload ever
   * made (e.g. {@code gimle-gateway}'s own DaemonSet-hosted {@code SERVICE} route resolution).
   * Plaintext mode is enough to exercise the fix -- the workload-kind lookup itself, not the mTLS
   * authorization branch {@link #a_workload_token_carries_deny_by_default_rbac_identity} already
   * covers.
   */
  @Test
  void a_workload_token_can_be_minted_for_a_tenanted_daemonset_not_only_a_deployment()
      throws Exception {
    InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    StateStore store = inProcessStore.store();
    store.putTenant(new Tenant("acme", new ResourceQuota(1_000_000_000L, 10_000, 100)));
    ModuleId gatewayModuleId = new ModuleId("com.gimle.gateway", Version.parse("1.0.0"));
    store.putDaemonSetSpec(
        new DaemonSetSpec(
            "gimle-gateway",
            gatewayModuleId,
            "/tmp/gateway.jar",
            PlacementConstraints.NONE,
            Optional.of("acme"),
            Optional.empty()));
    // workloadTenantId resolves the mint's tenant off the live assignment's own tenantId, not the
    // spec's, so a placement must exist here too, carrying the same tenant as the spec above.
    store.putDaemonSetAssignment(
        new DaemonSetAssignment(
            "gimle-gateway", "node-a", gatewayModuleId, "/tmp/gateway.jar", Optional.of("acme")));

    try (InProcessStore ignored = inProcessStore;
        InProcessFafnir inProcessFafnir =
            InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "http://localhost:" + server.port();
      HttpClient client = HttpClient.newHttpClient();

      HttpResponse<String> minted =
          mint(client, baseUrl, "{\"deploymentName\": \"gimle-gateway\", \"nodeId\": \"node-a\"}");

      assertEquals(200, minted.statusCode());
      assertNotNull(Json.asObject(Json.parse(minted.body())).get("token"));
    }
  }

  /**
   * A caller-declared {@code ?tenant=} on a single-resource GET/DELETE must win over the bare-name
   * search across tenants -- the same {@code kubectl --namespace} semantics {@code apply} already
   * gets right for writes. Two tenants sharing a bare deployment name is exactly the case where a
   * bare-name-only lookup silently picks the wrong one: this proves GET returns each tenant's own
   * copy when asked, and DELETE removes only the tenant explicitly named, leaving the other intact.
   */
  @Test
  void an_explicit_tenant_query_parameter_disambiguates_get_and_delete_by_bare_name()
      throws Exception {
    InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    StateStore store = inProcessStore.store();
    store.putTenant(new Tenant("acme", new ResourceQuota(1_000_000_000L, 4000, 10)));

    try (InProcessStore ignored = inProcessStore;
        InProcessFafnir inProcessFafnir =
            InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "http://localhost:" + server.port();
      HttpClient client = HttpClient.newHttpClient();
      Path jar = buildFixtureJar("com.gimle.fixture.authz.disambiguate");

      assertEquals(
          200,
          putDeployment(
              client,
              baseUrl,
              "",
              "shared-name",
              "com.gimle.fixture.authz.disambiguate",
              jar,
              Optional.of("acme")));
      assertEquals(
          200,
          putDeployment(
              client,
              baseUrl,
              "",
              "shared-name",
              "com.gimle.fixture.authz.disambiguate",
              jar,
              Optional.empty()));

      HttpResponse<String> getAcme = get(client, baseUrl + "/deployments/shared-name?tenant=acme");
      assertEquals(200, getAcme.statusCode());
      assertTrue(getAcme.body().contains("\"tenantId\":\"acme\""), getAcme.body());

      HttpResponse<String> getDefault =
          get(client, baseUrl + "/deployments/shared-name?tenant=default");
      assertEquals(200, getDefault.statusCode());
      assertTrue(getDefault.body().contains("\"tenantId\":\"default\""), getDefault.body());

      assertEquals(
          200,
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create(baseUrl + "/deployments/shared-name?tenant=acme"))
                      .DELETE()
                      .build(),
                  HttpResponse.BodyHandlers.discarding())
              .statusCode());

      assertEquals(
          200,
          get(client, baseUrl + "/deployments/shared-name?tenant=default").statusCode(),
          "the default tenant's own copy must survive deleting only acme's");
      assertEquals(
          404,
          get(client, baseUrl + "/deployments/shared-name?tenant=acme").statusCode(),
          "acme's own copy must be gone");
    }
  }

  private static HttpResponse<String> mint(HttpClient client, String baseUrl, String body)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/workload-tokens"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> bearerGet(
      HttpClient client, String baseUrl, String path, String token) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static boolean canI(HttpClient client, String baseUrl, String cookie, String queryString)
      throws IOException, InterruptedException {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/authz/can-i?" + queryString))
                .header("Cookie", cookie)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, response.statusCode());
    return Boolean.TRUE.equals(Json.asObject(Json.parse(response.body())).get("allowed"));
  }

  private static String login(HttpClient client, String baseUrl, String username, String password)
      throws IOException, InterruptedException {
    HttpResponse<String> response =
        client.send(
            loginRequest(baseUrl, username, password),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, response.statusCode());
    String setCookie = response.headers().firstValue("Set-Cookie").orElse("");
    return setCookie.substring(0, setCookie.indexOf(';'));
  }

  private static int putConfig(
      HttpClient client,
      String baseUrl,
      String cookie,
      String tenantId,
      String key,
      String value,
      boolean encrypted)
      throws IOException, InterruptedException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("value", value);
    body.put("encrypted", encrypted);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/config/" + tenantId + "/" + key))
            .header("Content-Type", "application/json")
            .header("Cookie", cookie)
            .PUT(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
            .build();
    return client
        .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        .statusCode();
  }

  private static int deleteConfig(
      HttpClient client, String baseUrl, String cookie, String tenantId, String key)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/config/" + tenantId + "/" + key))
            .header("Cookie", cookie)
            .DELETE()
            .build();
    return client
        .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        .statusCode();
  }

  private static List<Map<String, Object>> listConfig(
      HttpClient client, String baseUrl, String cookie, String tenantId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/config/" + tenantId))
            .header("Cookie", cookie)
            .GET()
            .build();
    HttpResponse<String> response =
        client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, response.statusCode());
    return Json.asObjectList(Json.parse(response.body()));
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
    // O=gimle:controlplane, matching how PkiBootstrapMain actually mints a real control plane's
    // own leaf certificate -- Fafnir/Andvari's independent peer-cert check (GIMLE-690) on the
    // X-Gimle-Forwarded-Principal header requires this test's own server identity to carry the
    // same group tag a genuine control-plane replica would.
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
