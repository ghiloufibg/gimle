package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.gimle.controlplane.alert.AlertNotification;
import com.gimle.controlplane.alert.AlertReconciler;
import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.authz.BuiltinRoles;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.Json;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.core.tenant.Tenant;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.CertificateSigningRequests;
import com.gimle.pki.Pem;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
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
 * {@code GET /alertrules/{name}/firing} -- the durable verdict read, RBAC-gated the same way {@code
 * GET /alertrules/{name}} already is. Plaintext coverage (success and the not-yet-known case) lives
 * here alongside a real mTLS/RBAC denial test, the same split {@code
 * ApiServerNetworkPoliciesTest}/{@code ApiServerNetworkPoliciesAuthzTest} already established for
 * the sibling network-model resource, rather than a second file.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerAlertRulesFiringTest {

  private static final String PROTOCOL_PROPERTY = "gimle.transport.protocol";
  private static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";
  private static final String KEY_FILE_PROPERTY = "gimle.tls.keyFile";
  private static final String CA_FILE_PROPERTY = "gimle.tls.caFile";
  private static final String CA_KEY_FILE_PROPERTY = "gimle.tls.caKeyFile";

  @TempDir(cleanup = CleanupMode.NEVER)
  private Path tempDir;

  private InProcessStore inProcessStore;
  private InProcessFafnir inProcessFafnir;
  private ApiServer server;
  private HttpClient client;
  private String baseUrl;
  private Path caFile;

  @BeforeEach
  void startServer() throws Exception {
    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client());
    server.start();
    baseUrl = "http://localhost:" + server.port();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopServer() {
    server.close();
    inProcessFafnir.close();
    inProcessStore.close();
    System.clearProperty(PROTOCOL_PROPERTY);
    System.clearProperty(CERT_FILE_PROPERTY);
    System.clearProperty(KEY_FILE_PROPERTY);
    System.clearProperty(CA_FILE_PROPERTY);
    System.clearProperty(CA_KEY_FILE_PROPERTY);
  }

  private HttpResponse<String> send(HttpRequest request) throws Exception {
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static String alertRuleJson(String name, String tenantId) {
    return """
        {"name": "%s", "tenantId": "%s", "deploymentName": "checkout-service",
         "metric": "ERROR_RATE_PER_SECOND", "comparator": "GREATER_THAN", "threshold": 5.0,
         "webhookUrl": "https://hooks.example.com/alerts"}
        """
        .formatted(name, tenantId);
  }

  @Test
  @Timeout(10)
  void firing_state_is_not_yet_known_for_a_rule_that_has_never_been_evaluated() throws Exception {
    HttpResponse<String> post =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules"))
                .POST(HttpRequest.BodyPublishers.ofString(alertRuleJson("high-errors", "acme")))
                .build());
    assertEquals(200, post.statusCode());

    HttpResponse<String> firing =
        send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/alertrules/high-errors/firing?tenant=acme"))
                .GET()
                .build());

    assertEquals(200, firing.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(firing.body()));
    assertEquals("high-errors", body.get("name"));
    assertEquals(false, body.get("known"));
    assertFalse(body.containsKey("firing"), "firing must be absent, not a meaningless false");
  }

  @Test
  @Timeout(10)
  void firing_state_reflects_the_durable_verdict_once_one_is_recorded() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules"))
            .POST(HttpRequest.BodyPublishers.ofString(alertRuleJson("high-errors", "acme")))
            .build());
    // Stands in for AlertReconciler actually observing a crossed threshold and persisting the
    // transition -- ApiServer itself never computes this, only reads it back.
    server.alertRuleRegistry().putFiringState(Optional.of("acme"), "high-errors", true);

    HttpResponse<String> firing =
        send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/alertrules/high-errors/firing?tenant=acme"))
                .GET()
                .build());

    assertEquals(200, firing.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(firing.body()));
    assertEquals(true, body.get("known"));
    assertEquals(true, body.get("firing"));
  }

  @Test
  @Timeout(10)
  void firing_state_of_an_unknown_alert_rule_is_404() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules/nope/firing?tenant=acme"))
                .GET()
                .build());
    assertEquals(404, response.statusCode());
  }

  /**
   * A rule submitted without a {@code tenantId} must land on the same key its own deployment does.
   * Every deployment this API can create is keyed under the default tenant, so a rule left in the
   * untenanted namespace would watch a deployment that cannot exist there: it would see no
   * instance, average zero, and sit at "never evaluated" forever while its condition was in fact
   * continuously true.
   */
  @Test
  @Timeout(10)
  void a_rule_created_with_no_tenant_evaluates_against_the_default_tenants_deployment()
      throws Exception {
    HttpResponse<String> post =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules"))
                .POST(HttpRequest.BodyPublishers.ofString(untenantedAlertRuleJson("high-errors")))
                .build());
    assertEquals(200, post.statusCode());
    oneDefaultTenantInstanceReporting(8.0);

    List<AlertNotification> notifications = new ArrayList<>();
    new AlertReconciler(server.alertRuleRegistry(), inProcessStore.client(), notifications::add)
        .reconcileOnce();

    assertEquals(1, notifications.size(), "the rule's condition is crossed, so it must fire");
    HttpResponse<String> firing =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules/high-errors/firing"))
                .GET()
                .build());
    assertEquals(200, firing.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(firing.body()));
    assertEquals(true, body.get("known"), "a rule that has fired must no longer read as unknown");
    assertEquals(true, body.get("firing"));
  }

  private static String untenantedAlertRuleJson(String name) {
    return """
        {"name": "%s", "deploymentName": "checkout-service",
         "metric": "ERROR_RATE_PER_SECOND", "comparator": "GREATER_THAN", "threshold": 5.0,
         "webhookUrl": "https://hooks.example.com/alerts"}
        """
        .formatted(name);
  }

  /** One placed, heartbeating instance of {@code checkout-service} in the default tenant. */
  private void oneDefaultTenantInstanceReporting(double errorRatePerSecond) {
    ModuleId moduleId = new ModuleId("com.gimle.example.checkout", Version.parse("1.0.0"));
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutAssignment(
                new InstanceAssignment(
                    "checkout-service",
                    0,
                    "node-1",
                    moduleId,
                    "/artifacts/checkout.jar",
                    OptionalInt.empty(),
                    Optional.of(Tenant.DEFAULT_TENANT_ID))));
    inProcessStore
        .client()
        .putHeartbeat(
            new NodeHeartbeat(
                "node-1",
                new ResourceUsageSnapshot(0, 0, 0, 0),
                List.of(
                    InstanceObservation.builder(
                            "checkout-service", 0, moduleId, "ACTIVE", true, true)
                        .tenantId(Optional.of(Tenant.DEFAULT_TENANT_ID))
                        .errorRatePerSecond(errorRatePerSecond)
                        .build())));
  }

  @Test
  @Timeout(10)
  void a_caller_with_no_alert_rule_grant_may_not_read_firing_state() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    try (InProcessStore tlsStore = InProcessStore.start(tempDir.resolve("tls-store"));
        InProcessFafnir tlsFafnir =
            InProcessFafnir.start(tlsStore.client(), tempDir.resolve("tls-keys/secret.key"));
        ApiServer tlsServer = new ApiServer(tlsStore.client(), 0, tlsFafnir.client())) {
      tlsServer.start();
      String tlsBaseUrl = "https://localhost:" + tlsServer.port();
      HttpClient operatorClient = mutualTlsClient(ca, "O=gimle:operators,CN=root-operator");

      HttpResponse<String> post =
          operatorClient.send(
              HttpRequest.newBuilder(URI.create(tlsBaseUrl + "/alertrules"))
                  .POST(HttpRequest.BodyPublishers.ofString(alertRuleJson("high-errors", "acme")))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, post.statusCode());

      HttpClient noPermissionClient = mutualTlsClient(ca, "CN=no-permission-caller");
      HttpResponse<String> response =
          noPermissionClient.send(
              HttpRequest.newBuilder(
                      URI.create(tlsBaseUrl + "/alertrules/high-errors/firing?tenant=acme"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

      assertEquals(403, response.statusCode());
    }
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

  private Path writePem(String fileName, String pem) throws Exception {
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
