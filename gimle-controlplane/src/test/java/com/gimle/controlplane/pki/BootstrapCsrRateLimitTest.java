package com.gimle.controlplane.pki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.api.ApiServer;
import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.authz.BuiltinRoles;
import com.gimle.core.protocol.Json;
import com.gimle.core.tls.SslContexts;
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
 * Rate limiting on {@code POST /bootstrap/csr}: the one route reachable with no credential at all,
 * and the one doing real asymmetric-crypto work per request. Both halves matter -- an abusive
 * caller must be cut off, and a whole fleet bootstrapping in the same instant (every node from one
 * address, as a NAT'd or single-machine fleet appears) must not be, since a joining agent treats a
 * rejected submission as fatal rather than retrying it.
 */
// System.setProperty mutates a JVM-global; excludes this class from running concurrently with
// any other class holding the same lock, under class-level parallel execution (root pom.xml).
@ResourceLock(Resources.SYSTEM_PROPERTIES)
// Real ApiServer + real HttpClient on a loopback ephemeral port (see ApiServerTest for why):
// excluded from running concurrently with any other class doing the same.
@ResourceLock("gimle-controlplane-api-server-http")
class BootstrapCsrRateLimitTest {

  private static final String PROTOCOL_PROPERTY = "gimle.transport.protocol";
  private static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";
  private static final String KEY_FILE_PROPERTY = "gimle.tls.keyFile";
  private static final String CA_FILE_PROPERTY = "gimle.tls.caFile";
  private static final String CA_KEY_FILE_PROPERTY = "gimle.tls.caKeyFile";
  private static final String BURST_PER_ADDRESS_PROPERTY = "gimle.controlplane.csr.burstPerAddress";
  private static final String CLUSTER_BURST_PROPERTY = "gimle.controlplane.csr.burst";
  private static final String CLUSTER_REFILL_PROPERTY = "gimle.controlplane.csr.refillMillis";

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
    System.clearProperty(BURST_PER_ADDRESS_PROPERTY);
    System.clearProperty(CLUSTER_BURST_PROPERTY);
    System.clearProperty(CLUSTER_REFILL_PROPERTY);
  }

  @Test
  void a_whole_fleet_bootstrapping_at_once_from_one_address_is_never_throttled() throws Exception {
    // No limit properties set: the shipped defaults are what a real cluster bring-up meets.
    withServer(
        (client, baseUrl, csr) -> {
          for (int node = 0; node < 50; node++) {
            assertEquals(202, submit(client, baseUrl, csr), "node " + node);
          }
        });
  }

  @Test
  void submissions_past_the_per_address_burst_are_refused_with_a_retry_after() throws Exception {
    System.setProperty(BURST_PER_ADDRESS_PROPERTY, "5");
    withServer(
        (client, baseUrl, csr) -> {
          for (int attempt = 0; attempt < 5; attempt++) {
            assertEquals(202, submit(client, baseUrl, csr), "attempt " + attempt);
          }

          HttpResponse<String> refused = submitResponse(client, baseUrl, csr);

          assertEquals(429, refused.statusCode());
          long retryAfter =
              Long.parseLong(refused.headers().firstValue("Retry-After").orElseThrow());
          assertTrue(retryAfter >= 1, "Retry-After should name a positive wait, was " + retryAfter);
        });
  }

  /**
   * The per-address bucket alone would let a caller spread a flood over many source addresses, so a
   * second bucket bounds every caller together. Exercised by leaving the per-address budget wide
   * open and squeezing only the shared one -- a single test client can't dial from many addresses.
   */
  @Test
  void submissions_past_the_cluster_wide_burst_are_refused_even_below_the_address_burst()
      throws Exception {
    System.setProperty(BURST_PER_ADDRESS_PROPERTY, "1000");
    System.setProperty(CLUSTER_BURST_PROPERTY, "3");
    // Long enough that no token refills between two sequential HTTP round trips, so the fourth
    // submission is refused because the burst is spent rather than because the test raced.
    System.setProperty(CLUSTER_REFILL_PROPERTY, "600000");
    withServer(
        (client, baseUrl, csr) -> {
          for (int attempt = 0; attempt < 3; attempt++) {
            assertEquals(202, submit(client, baseUrl, csr), "attempt " + attempt);
          }

          assertEquals(429, submit(client, baseUrl, csr));
        });
  }

  @Test
  void a_throttled_submission_leaves_an_already_admitted_request_pollable() throws Exception {
    System.setProperty(BURST_PER_ADDRESS_PROPERTY, "1");
    withServer(
        (client, baseUrl, csr) -> {
          String requestId = (String) submitJson(client, baseUrl, csr).get("requestId");
          assertEquals(429, submit(client, baseUrl, csr));

          // The one admitted submission is still pollable: throttling the second request must not
          // disturb state the first one legitimately created.
          HttpRequest poll =
              HttpRequest.newBuilder(URI.create(baseUrl + "/bootstrap/csr/" + requestId))
                  .GET()
                  .build();
          HttpResponse<String> response =
              client.send(poll, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
          assertEquals(200, response.statusCode());
          assertEquals("PENDING", Json.asObject(Json.parse(response.body())).get("status"));
        });
  }

  /** What a test body needs: a trust-only client, the server's base URL, and one reusable CSR. */
  private interface CsrScenario {
    void run(HttpClient client, String baseUrl, PKCS10CertificationRequest csr) throws Exception;
  }

  private void withServer(CsrScenario scenario) throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);

    InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    InProcessFafnir inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    try (inProcessStore;
        inProcessFafnir;
        ApiServer server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      server.start();
      // One key pair and CSR reused by every submission in a scenario: the server re-parses and
      // re-verifies it each time (which is the work being rate-limited), and generating a fresh
      // 2048-bit key per request would dominate the test's own runtime for no added coverage.
      PKCS10CertificationRequest csr =
          CertificateSigningRequests.generate(
              generateRsaKeyPair(), new X500Name("CN=joining-operator"));
      scenario.run(trustOnlyClient(), "https://localhost:" + server.port(), csr);
    }
  }

  private static int submit(HttpClient client, String baseUrl, PKCS10CertificationRequest csr)
      throws IOException, InterruptedException {
    return submitResponse(client, baseUrl, csr).statusCode();
  }

  private static Map<String, Object> submitJson(
      HttpClient client, String baseUrl, PKCS10CertificationRequest csr)
      throws IOException, InterruptedException {
    return Json.asObject(Json.parse(submitResponse(client, baseUrl, csr).body()));
  }

  private static HttpResponse<String> submitResponse(
      HttpClient client, String baseUrl, PKCS10CertificationRequest csr)
      throws IOException, InterruptedException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("purpose", "OPERATOR_CLIENT");
    body.put("csrPem", Pem.encodeCsr(csr));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/bootstrap/csr"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpClient trustOnlyClient() {
    SSLContext context = SslContexts.forServerTrustOnly(caFile);
    return HttpClient.newBuilder().sslContext(context).build();
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
