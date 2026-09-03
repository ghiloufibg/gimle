package com.gimle.agent;

import com.gimle.core.protocol.CsrPurpose;
import com.gimle.core.protocol.CsrResult;
import com.gimle.core.protocol.CsrSubmission;
import com.gimle.core.protocol.Json;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.pki.CertificateSigningRequests;
import com.gimle.pki.Pem;
import com.gimle.pki.RenewalSchedule;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The certificate a worker JVM presents on the fabric's cross-machine mTLS hops, one per worker
 * this agent spawns rather than the agent's own node certificate handed down wholesale: a worker
 * hosts exactly one tenant's modules, and the receiving {@code FabricServer} reads that tenant off
 * the verified peer certificate ({@code O=gimle:tenant:<id>}) instead of trusting whatever tenant
 * the calling side wrote into the request. Issued through the same {@code POST /bootstrap/csr}
 * every other identity comes from, as a {@link CsrPurpose#WORKER_CLIENT} submission authenticated
 * by this agent's own node certificate -- the control plane signs it only for a tenant this node
 * currently holds an assignment for, so the node can never mint an identity for a tenant the
 * scheduler never placed here.
 *
 * <p>Renewal is a fresh issuance under the same subject, not the same-subject rotation branch the
 * agent's own certificate uses: that branch authenticates by the certificate being rotated, and a
 * worker never talks to the control plane itself. Key is always written before certificate, so
 * {@code gimle-worker}'s mtime-polling {@code FabricServerTlsWatcher} can never observe a fresh
 * certificate beside a stale key. Dormant in plaintext mode ({@link #fromConfig} is empty).
 */
final class WorkerCertificates {

  private static final Logger log = LoggerFactory.getLogger(WorkerCertificates.class);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final String CERT_FILE_NAME = "worker.crt";
  private static final String KEY_FILE_NAME = "worker.key";

  /** Signs one submission -- the control plane's {@code POST /bootstrap/csr} in production. */
  interface CsrSigner {
    CsrResult sign(CsrSubmission submission) throws IOException, InterruptedException;
  }

  /** The cert/key pair a spawned worker JVM is pointed at via {@code -Dgimle.tls.*}. */
  record Material(Path certFile, Path keyFile) {}

  private final CsrSigner signer;
  private final Path root;
  private final String advertisedHost;
  private final Supplier<Instant> clock;

  WorkerCertificates(CsrSigner signer, Path root, String advertisedHost, Supplier<Instant> clock) {
    this.signer = signer;
    this.root = root;
    this.advertisedHost = advertisedHost;
    this.clock = clock;
  }

  /**
   * Production wiring: submissions go to {@code baseUrl}'s {@code /bootstrap/csr} over {@code
   * httpClient} (this agent's own mTLS client, which is what authenticates them as this node's),
   * and material lands under a {@code workers/} directory beside the agent's own certificate file.
   * Empty in plaintext mode, where no worker has any TLS material at all.
   */
  static Optional<WorkerCertificates> fromConfig(
      HttpClient httpClient, URI baseUrl, String advertisedHost) {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return Optional.empty();
    }
    Path root = TlsSettings.fromConfig().certFile().toAbsolutePath().resolveSibling("workers");
    return Optional.of(
        new WorkerCertificates(
            submission -> postCsr(httpClient, baseUrl, submission),
            root,
            advertisedHost,
            Instant::now));
  }

  Material materialFor(String workerKey) {
    Path directory = root.resolve(fileSafe(workerKey));
    return new Material(directory.resolve(CERT_FILE_NAME), directory.resolve(KEY_FILE_NAME));
  }

  /**
   * The material a worker about to be spawned under {@code workerKey} should present: issued now if
   * nothing is on disk yet (first spawn) or what is there is already due for renewal (a respawn
   * long after the original issuance), left untouched otherwise.
   */
  Material ensureIssued(String nodeId, String workerKey, Optional<String> tenantId)
      throws IOException, InterruptedException {
    Material material = materialFor(workerKey);
    if (Files.isRegularFile(material.certFile())
        && Files.isRegularFile(material.keyFile())
        && !isDue(material)) {
      return material;
    }
    issue(nodeId, workerKey, tenantId, material);
    return material;
  }

  /**
   * One renewal pass over every worker currently supervised ({@code tenantByWorkerKey}), re-issuing
   * each certificate that is due. Best-effort per worker: a failed renewal is logged and retried on
   * a later pass, never fatal to the agent's tick, the same posture the agent's own rotation takes.
   * Returns the keys actually renewed.
   */
  Set<String> renewDue(String nodeId, Map<String, Optional<String>> tenantByWorkerKey) {
    Set<String> renewed = new LinkedHashSet<>();
    for (Map.Entry<String, Optional<String>> entry : tenantByWorkerKey.entrySet()) {
      Material material = materialFor(entry.getKey());
      if (!Files.isRegularFile(material.certFile()) || !isDue(material)) {
        continue;
      }
      try {
        issue(nodeId, entry.getKey(), entry.getValue(), material);
        renewed.add(entry.getKey());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return renewed;
      } catch (IOException | RuntimeException e) {
        log.warn(
            "worker certificate renewal for {} failed, will retry: {}",
            entry.getKey(),
            e.getMessage());
      }
    }
    return renewed;
  }

  private boolean isDue(Material material) {
    try {
      X509Certificate certificate =
          Pem.decodeCertificate(Files.readString(material.certFile(), StandardCharsets.US_ASCII));
      return RenewalSchedule.of(certificate).isDue(clock.get());
    } catch (IOException | RuntimeException e) {
      // Unreadable material is treated as due: re-issuing replaces whatever is wrong with it.
      log.warn("unreadable worker certificate at {}: {}", material.certFile(), e.getMessage());
      return true;
    }
  }

  private void issue(String nodeId, String workerKey, Optional<String> tenantId, Material material)
      throws IOException, InterruptedException {
    KeyPair keyPair = AgentMain.generateRsaKeyPair();
    // Built rather than parsed from a "CN=..." string: an instance key carries '#', which a DN
    // parser reads as the start of a hex-encoded value.
    X500Name subject =
        new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, nodeId + ":" + workerKey).build();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(keyPair, subject, List.of(advertisedHost));
    CsrResult result =
        signer.sign(
            new CsrSubmission(
                CsrPurpose.WORKER_CLIENT, Pem.encodeCsr(csr), Optional.empty(), tenantId));
    String certificatePem =
        result
            .certificatePem()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "worker certificate request for "
                            + workerKey
                            + " returned status "
                            + result.status()
                            + " with no certificate"));
    Files.createDirectories(root.resolve(fileSafe(workerKey)));
    Files.writeString(
        material.keyFile(), Pem.encodePrivateKey(keyPair.getPrivate()), StandardCharsets.US_ASCII);
    Files.writeString(material.certFile(), certificatePem, StandardCharsets.US_ASCII);
    log.info(
        "issued worker certificate for {} ({})",
        workerKey,
        tenantId.map(id -> "tenant " + id).orElse("untenanted"));
  }

  private static CsrResult postCsr(HttpClient httpClient, URI baseUrl, CsrSubmission submission)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUrl.resolve("/bootstrap/csr"))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    Json.write(AgentMain.csrSubmissionToJson(submission)), StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      throw new IOException(
          "worker certificate request rejected with status "
              + response.statusCode()
              + ": "
              + response.body());
    }
    return AgentMain.csrResultFromJson(Json.asObject(Json.parse(response.body())));
  }

  /**
   * An instance key ({@code <deployment>#<index>}) as a directory name: deployment names are
   * already validated identifiers, so this only ever has to neutralize a path separator.
   */
  static String fileSafe(String workerKey) {
    return workerKey.replaceAll("[^A-Za-z0-9._#-]", "_");
  }
}
