package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
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
 * A {@code gimle:nodes} identity's read access to {@code GET /networkpolicies} and {@code GET
 * /services}: unlike {@code /endpoints/{name}}'s per-tenant scoping (see {@link
 * ApiServerEndpointsAuthzTest}), a node needs the full, cluster-wide set of both to do its own job
 * -- {@code NetworkPolicyRelay} ships every policy down to its supervised workers regardless of
 * which tenants this node currently has instances for, and a Bifrost-enabled agent's {@code
 * ServiceSource} needs to know about every Service it might front a local proxy for -- so this
 * covers a node with zero assignments still getting a 200, and write/delete on either staying
 * denied. Discovered live: a freshly-bootstrapped mTLS agent's first {@code NetworkPolicyRelay}
 * poll returned 403 because no RBAC path granted a node identity any access to either resource kind
 * at all.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerNodeServiceAndNetworkPolicyAuthzTest {

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
  @Timeout(10)
  void a_node_with_no_assignments_at_all_may_still_list_networkpolicies_and_services()
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
      // Deliberately no store setup at all -- no deployment, no assignment for "node-1" anywhere
      // -- proving this grant is unconditional, not a disguised tenant-scoping check.
      HttpClient client = nodeClient(ca, "node-1");

      HttpResponse<String> policies =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies")).GET().build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, policies.statusCode());

      HttpResponse<String> services =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/services")).GET().build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, services.statusCode());
    }
  }

  @Test
  @Timeout(10)
  void a_node_may_never_write_a_networkpolicy_or_a_service() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
        InProcessFafnir inProcessFafnir =
            InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      String baseUrl = "https://localhost:" + server.port();
      HttpClient client = nodeClient(ca, "node-1");

      HttpResponse<String> policyWrite =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies"))
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"name\":\"np1\",\"tenantId\":\"acme\","
                              + "\"allowedCallerTenantIds\":[]}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(403, policyWrite.statusCode());

      HttpResponse<String> serviceWrite =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"name\":\"svc1\",\"tenantId\":\"acme\",\"port\":8080}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(403, serviceWrite.statusCode());
    }
  }

  /**
   * Regression-proof, mirroring {@code ApiServerEndpointsAuthzTest}'s own equivalent: the ordinary
   * RBAC path used by every non-node caller is unaffected by the node-scoped branch added ahead of
   * it.
   */
  @Test
  @Timeout(10)
  void ordinary_rbac_callers_are_unaffected_by_the_node_scoped_branch() throws Exception {
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
      HttpResponse<String> operatorResponse =
          operatorClient.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies")).GET().build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, operatorResponse.statusCode());

      HttpClient noPermissionClient = mutualTlsClient(ca, "CN=no-permission-caller");
      HttpResponse<String> deniedResponse =
          noPermissionClient.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies")).GET().build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(403, deniedResponse.statusCode());
    }
  }

  private HttpClient nodeClient(CertificateAuthority ca, String nodeId) throws Exception {
    return mutualTlsClient(ca, "O=gimle:nodes,CN=" + nodeId);
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
