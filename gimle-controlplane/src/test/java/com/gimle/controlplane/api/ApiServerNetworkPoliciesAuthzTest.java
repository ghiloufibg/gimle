package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.authz.BuiltinRoles;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.mimir.raft.StateMutation;
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
import java.util.List;
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
 * {@code /networkpolicies*}'s real mTLS/RBAC layer, the way {@code ApiServerEndpointsAuthzTest}
 * already exercises it for its own route -- the ordinary plaintext CRUD coverage lives in {@link
 * ApiServerNetworkPoliciesTest}, since {@link com.gimle.core.authz.ResourceKind#NETWORK_POLICY}
 * only matters once a real client certificate is in play (see {@code ApiServer#requireAuthorized}:
 * a plaintext exchange is always authorized).
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerNetworkPoliciesAuthzTest {

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

  private static String tenantWidePolicyJson(String name, String tenantId) {
    return """
        {"name": "%s", "tenantId": "%s", "allowedCallerTenantIds": []}
        """
        .formatted(name, tenantId);
  }

  @Test
  @Timeout(10)
  void an_operator_may_declare_a_network_policy() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
        InProcessFafnir inProcessFafnir =
            InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      // A policy's own owning tenant must exist before it may be declared, exactly as the tenants
      // its allow list names must.
      inProcessStore
          .client()
          .propose(
              new StateMutation.PutTenant(
                  new Tenant("acme", new ResourceQuota(1024L * 1024, 1000, 5))));
      String baseUrl = "https://localhost:" + server.port();
      HttpClient operatorClient = mutualTlsClient(ca, "O=gimle:operators,CN=root-operator");

      HttpResponse<String> response =
          operatorClient.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies"))
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          tenantWidePolicyJson("deny-by-default", "acme")))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

      assertEquals(200, response.statusCode());
    }
  }

  @Test
  @Timeout(10)
  void a_caller_with_no_network_policy_grant_may_neither_write_nor_read() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
        InProcessFafnir inProcessFafnir =
            InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      HttpClient noPermissionClient = mutualTlsClient(ca, "CN=no-permission-caller");

      HttpResponse<String> writeResponse =
          noPermissionClient.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies"))
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          tenantWidePolicyJson("deny-by-default", "acme")))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(403, writeResponse.statusCode());

      HttpResponse<String> readResponse =
          noPermissionClient.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies")).GET().build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(403, readResponse.statusCode());
    }
  }

  @Test
  @Timeout(10)
  void a_caller_may_not_delete_a_network_policy_belonging_to_a_tenant_it_has_no_grant_for()
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
      operatorClient.send(
          HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies"))
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      tenantWidePolicyJson("deny-by-default", "acme")))
              .build(),
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

      HttpClient noPermissionClient = mutualTlsClient(ca, "CN=no-permission-caller");
      HttpResponse<String> response =
          noPermissionClient.send(
              HttpRequest.newBuilder(
                      URI.create(baseUrl + "/networkpolicies/deny-by-default?tenant=acme"))
                  .DELETE()
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
