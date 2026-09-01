package com.gimle.pki;

import com.gimle.core.protocol.CsrPurpose;
import com.gimle.core.protocol.CsrRequestStatus;
import com.gimle.core.protocol.CsrResult;
import com.gimle.core.protocol.CsrSubmission;
import com.gimle.core.protocol.Json;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The renewal half of a node's own leaf-certificate lifecycle, shared between {@code ApiServer}
 * (calling its own loopback {@code /bootstrap/csr}) and {@code StoreMain} (calling a reachable
 * {@code ApiServer} replica's {@code /bootstrap/csr} over the network) -- extracted once {@code
 * StoreMain} needed the identical logic as a second caller: {@code gimle-mimir} replicas get
 * CA-signed leaf certs from the same single cluster CA every other component uses, via the same
 * renewal-over-mTLS mechanism, not an ad-hoc self-signed cert or a second CSR-signing authority.
 * Deliberately does not reload any listener itself -- {@code RaftTransport}/{@code
 * StoreTransport}/{@code ApiServer}'s own {@code HttpsServer} are each a different caller's
 * concern, so {@link CertificateRotationStatus#rotated()} tells the caller whether *any* reload is
 * needed.
 *
 * <p>Every check -- including one that fails -- is reported through the {@link
 * CertificateRotationMonitor} this rotator is constructed with, which is what turns a failure into
 * something an operator can see and alert on: a swallowed failure here is harmless only for as long
 * as the certificate it failed to renew stays valid, and silently keeping a still-valid certificate
 * is exactly how a surprise expiry outage is built.
 */
public final class OwnCertificateRotator {

  private static final Logger log = LoggerFactory.getLogger(OwnCertificateRotator.class);

  private final CertificateRotationMonitor monitor;

  public OwnCertificateRotator(CertificateRotationMonitor monitor) {
    if (monitor == null) {
      throw new IllegalArgumentException("monitor must not be null");
    }
    this.monitor = monitor;
  }

  /**
   * No-op in plaintext mode or when nothing is due yet. The returned status says which of those it
   * was, how much validity the certificate on disk still has, and how many checks in a row have
   * failed; the caller is responsible for reloading whatever listeners key off {@code
   * settings.certFile()}/{@code keyFile()} once {@link CertificateRotationStatus#rotated()} is
   * {@code true}.
   */
  public CertificateRotationStatus checkAndRotateIfDue(TlsSettings settings, URI csrEndpoint) {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return monitor.disabled();
    }
    X509Certificate current = null;
    try {
      current = loadOwnLeafCertificate(settings.certFile());
      if (!RenewalSchedule.of(current).isDue(Instant.now())) {
        return monitor.notDue(current);
      }
      if (csrEndpoint == null) {
        // A due certificate with nowhere to renew it is a misconfiguration that expires the
        // process on a fixed deadline, so it counts as a failed check rather than a quiet no-op.
        return monitor.failed(
            "the certificate is due for renewal but no CSR endpoint is configured", current);
      }
      log.info("own leaf certificate due for renewal, requesting rotation from {}", csrEndpoint);
      X509Certificate issued = rotate(settings, keyPairSubjectOf(current), csrEndpoint);
      return monitor.rotated(issued);
    } catch (RuntimeException | IOException e) {
      return monitor.failed(e.getMessage() == null ? e.toString() : e.getMessage(), current);
    }
  }

  private static X500Name keyPairSubjectOf(X509Certificate current) {
    // X500Name.getInstance(...getEncoded()), never new X500Name(...getName()): the latter
    // round-trips through X500Principal's RFC 2253 string rendering, which reorders a multi-RDN
    // subject (most-specific RDN first, i.e. CN before O) relative to the certificate's own ASN.1
    // encoding order (O before CN, per Subjects.withOrganization). Every node and operator subject
    // carries both O= and CN=, so a reordered CSR subject here would fail
    // ApiServer#handleRotationRequest's own byte-for-byte comparison against the presented
    // certificate's real encoding, rejecting the rotation outright.
    return X500Name.getInstance(current.getSubjectX500Principal().getEncoded());
  }

  private static X509Certificate rotate(TlsSettings settings, X500Name subject, URI csrEndpoint)
      throws IOException {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr = CertificateSigningRequests.generate(keyPair, subject);
    HttpClient client =
        HttpClient.newBuilder().sslContext(SslContexts.forMutualTls(settings)).build();
    HttpRequest request =
        HttpRequest.newBuilder(csrEndpoint)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    Json.write(
                        csrSubmissionToJson(
                            new CsrSubmission(CsrPurpose.NODE_CLIENT, Pem.encodeCsr(csr))))))
            .build();
    HttpResponse<String> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while requesting own certificate rotation", e);
    }
    if (response.statusCode() != 200) {
      throw new IOException(
          "own rotation request rejected with status "
              + response.statusCode()
              + ": "
              + response.body());
    }
    CsrResult result = csrResultFromJson(Json.asObject(Json.parse(response.body())));
    String issuedPem =
        result
            .certificatePem()
            .orElseThrow(
                () ->
                    new IOException(
                        "own rotation request returned status "
                            + result.status()
                            + " with no certificate"));
    Files.writeString(settings.certFile(), issuedPem, StandardCharsets.US_ASCII);
    Files.writeString(
        settings.keyFile(), Pem.encodePrivateKey(keyPair.getPrivate()), StandardCharsets.US_ASCII);
    restrictPermissions(settings.keyFile());
    return Pem.decodeCertificate(issuedPem);
  }

  /**
   * Restricts a freshly-rotated private key file to owner-read/write only wherever the filesystem
   * supports POSIX permissions (every real deployment target); on a filesystem that doesn't
   * (Windows, local development only), the key is left written but the restriction is skipped with
   * a logged warning rather than a hard failure, since {@code java.nio.file}'s own POSIX view is
   * simply unavailable there.
   */
  private static void restrictPermissions(Path path) throws IOException {
    if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    } else {
      log.warn(
          "filesystem at {} does not support POSIX permissions; private key file was written"
              + " without owner-only restriction (expected only in local Windows development --"
              + " every real deployment target restricts this)",
          path);
    }
  }

  private static X509Certificate loadOwnLeafCertificate(Path certFile) throws IOException {
    return Pem.decodeCertificate(Files.readString(certFile, StandardCharsets.US_ASCII));
  }

  private static KeyPair generateRsaKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(CertificateAuthority.KEY_SIZE_BITS);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("RSA key pair generation is unavailable", e);
    }
  }

  private static Map<String, Object> csrSubmissionToJson(CsrSubmission submission) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("purpose", submission.purpose().name());
    map.put("csrPem", submission.csrPem());
    submission.bootstrapToken().ifPresent(token -> map.put("bootstrapToken", token));
    return map;
  }

  private static CsrResult csrResultFromJson(Map<String, Object> json) {
    CsrRequestStatus status = CsrRequestStatus.valueOf((String) json.get("status"));
    Optional<String> requestId = Optional.ofNullable((String) json.get("requestId"));
    Optional<String> certificatePem = Optional.ofNullable((String) json.get("certificatePem"));
    Optional<String> caCertificatePem = Optional.ofNullable((String) json.get("caCertificatePem"));
    return new CsrResult(status, requestId, certificatePem, caCertificatePem);
  }
}
