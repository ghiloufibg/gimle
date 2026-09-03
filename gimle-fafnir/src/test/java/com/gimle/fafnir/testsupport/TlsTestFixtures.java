package com.gimle.fafnir.testsupport;

import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.CertificateSigningRequests;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.SSLContext;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;

/**
 * Self-signed CA, leaf-certificate issuance, and PEM file writing for {@code gimle-fafnir}'s
 * TLS/mTLS tests -- the same fixture shape duplicated verbatim across {@code FafnirServerTlsTest},
 * {@code FafnirSecretsAuthzTest}, and {@code FafnirObservabilityTest} before this extraction,
 * mirroring {@link InProcessStore}'s own precedent for factoring out test-fixture duplication
 * within this module.
 */
public final class TlsTestFixtures {

  public static final String PROTOCOL_PROPERTY = "gimle.transport.protocol";
  public static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";
  public static final String KEY_FILE_PROPERTY = "gimle.tls.keyFile";
  public static final String CA_FILE_PROPERTY = "gimle.tls.caFile";

  private final Path tempDir;

  public TlsTestFixtures(Path tempDir) {
    this.tempDir = tempDir;
  }

  /** Clears the {@code gimle.transport.protocol}/{@code gimle.tls.*} system properties. */
  public static void clearTransportProperties() {
    System.clearProperty(PROTOCOL_PROPERTY);
    System.clearProperty(CERT_FILE_PROPERTY);
    System.clearProperty(KEY_FILE_PROPERTY);
    System.clearProperty(CA_FILE_PROPERTY);
  }

  /** A fresh self-signed test CA, valid for one day. */
  public static CertificateAuthority selfSignedCa() throws Exception {
    return CertificateAuthority.generateSelfSignedCa(
        new X500Name("CN=test-ca"), Duration.ofDays(1));
  }

  /**
   * Issues a {@code CN=fafnir} leaf signed by {@code ca}, writes it alongside its key and the CA
   * certificate under {@link #tempDir}, and points the {@code gimle.transport.protocol}/{@code
   * gimle.tls.*} system properties at those files -- what a real TLS-enabled server reads at
   * startup.
   */
  public void configureServerTls(CertificateAuthority ca) throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(
            keyPair, new X500Name("CN=fafnir"), List.of("localhost"));
    X509Certificate leaf = ca.signCertificateRequest(csr, Duration.ofDays(1));
    Path certFile = writePem("fafnir-cert.pem", "CERTIFICATE", leaf.getEncoded());
    Path keyFile = writePem("fafnir-key.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
    Path caFile = writePem("fafnir-ca.pem", "CERTIFICATE", ca.certificate().getEncoded());

    System.setProperty(PROTOCOL_PROPERTY, "tls");
    System.setProperty(CERT_FILE_PROPERTY, certFile.toString());
    System.setProperty(KEY_FILE_PROPERTY, keyFile.toString());
    System.setProperty(CA_FILE_PROPERTY, caFile.toString());
  }

  /**
   * A leaf certificate paired with an {@link HttpClient} presenting it -- for a caller (like a
   * revocation test) that needs both the certificate's own serial number and a live connection
   * using it, not just one or the other.
   */
  public record IssuedLeaf(X509Certificate certificate, HttpClient client) {}

  /** Same as {@link #clientWithLeaf}, but also hands back the raw certificate it presents. */
  public IssuedLeaf issueLeafWithCertificate(CertificateAuthority ca, String commonName)
      throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(keyPair, new X500Name("CN=" + commonName));
    X509Certificate leaf = ca.signCertificateRequest(csr, Duration.ofDays(1));
    return issuedLeafOf(commonName, keyPair, leaf, ca);
  }

  /**
   * Same as {@link #controlPlaneClientWithLeaf}, but also hands back the raw certificate it
   * presents -- for a revocation test proving a revoked control-plane leaf can no longer vouch for
   * a forwarded principal either.
   */
  public IssuedLeaf issueControlPlaneLeafWithCertificate(CertificateAuthority ca, String commonName)
      throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(keyPair, new X500Name("CN=" + commonName));
    X509Certificate leaf =
        ca.signCertificateRequest(
            csr, new X500Name("O=gimle:controlplane,CN=" + commonName), Duration.ofDays(1));
    return issuedLeafOf(commonName, keyPair, leaf, ca);
  }

  private IssuedLeaf issuedLeafOf(
      String commonName, KeyPair keyPair, X509Certificate leaf, CertificateAuthority ca)
      throws Exception {
    Path certFile = writePem(commonName + "-cert.pem", "CERTIFICATE", leaf.getEncoded());
    Path keyFile =
        writePem(commonName + "-key.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
    Path caFile = writePem(commonName + "-ca.pem", "CERTIFICATE", ca.certificate().getEncoded());
    SSLContext sslContext = SslContexts.forMutualTls(new TlsSettings(certFile, keyFile, caFile));
    return new IssuedLeaf(leaf, HttpClient.newBuilder().sslContext(sslContext).build());
  }

  /** Issues a {@code CN=commonName} leaf signed by {@code ca} and its {@link TlsSettings}. */
  public TlsSettings issueLeaf(CertificateAuthority ca, String commonName) throws Exception {
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

  /** An {@link HttpClient} presenting a {@code CN=commonName} leaf signed by {@code ca}. */
  public HttpClient clientWithLeaf(CertificateAuthority ca, String commonName) throws Exception {
    SSLContext sslContext = SslContexts.forMutualTls(issueLeaf(ca, commonName));
    return HttpClient.newBuilder().sslContext(sslContext).build();
  }

  /**
   * An {@link HttpClient} presenting a leaf stamped {@code O=gimle:nodes} (the server-side-only
   * stamp real CSR issuance applies, see {@code Subjects.withOrganization}) -- exercises the
   * node-authorization path rather than {@link #clientWithLeaf}'s ordinary-RBAC one.
   */
  public HttpClient nodeClientWithLeaf(CertificateAuthority ca, String nodeId) throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(keyPair, new X500Name("CN=" + nodeId));
    X509Certificate leaf =
        ca.signCertificateRequest(
            csr, new X500Name("O=gimle:nodes,CN=" + nodeId), Duration.ofDays(1));
    Path certFile = writePem(nodeId + "-cert.pem", "CERTIFICATE", leaf.getEncoded());
    Path keyFile = writePem(nodeId + "-key.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
    Path caFile = writePem(nodeId + "-ca.pem", "CERTIFICATE", ca.certificate().getEncoded());

    SSLContext sslContext = SslContexts.forMutualTls(new TlsSettings(certFile, keyFile, caFile));
    return HttpClient.newBuilder().sslContext(sslContext).build();
  }

  /**
   * An {@link HttpClient} presenting a leaf stamped {@code O=gimle:controlplane} (the group {@code
   * PkiBootstrapMain} stamps onto the control plane's own leaf certificate) -- the one peer
   * identity {@code FafnirServer#resolvePrincipal} trusts to have a forwarded-principal header
   * honored, simulating {@code ApiServer}'s own {@code /secrets/*} proxy hop.
   */
  public HttpClient controlPlaneClientWithLeaf(CertificateAuthority ca, String commonName)
      throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(keyPair, new X500Name("CN=" + commonName));
    X509Certificate leaf =
        ca.signCertificateRequest(
            csr, new X500Name("O=gimle:controlplane,CN=" + commonName), Duration.ofDays(1));
    Path certFile = writePem(commonName + "-cert.pem", "CERTIFICATE", leaf.getEncoded());
    Path keyFile =
        writePem(commonName + "-key.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
    Path caFile = writePem(commonName + "-ca.pem", "CERTIFICATE", ca.certificate().getEncoded());

    SSLContext sslContext = SslContexts.forMutualTls(new TlsSettings(certFile, keyFile, caFile));
    return HttpClient.newBuilder().sslContext(sslContext).build();
  }

  /** Writes a PEM-encoded {@code label} block for {@code derBytes} under {@link #tempDir}. */
  public Path writePem(String fileName, String label, byte[] derBytes) throws IOException {
    Path path = tempDir.resolve(fileName);
    Files.writeString(path, pem(label, derBytes));
    return path;
  }

  /** Overwrites an existing PEM file in place -- what a real certificate rotation does. */
  public void overwritePem(Path path, String label, byte[] derBytes) throws IOException {
    Files.writeString(path, pem(label, derBytes));
  }

  private static String pem(String label, byte[] derBytes) {
    String base64 =
        Base64.getMimeEncoder(64, System.lineSeparator().getBytes(StandardCharsets.US_ASCII))
            .encodeToString(derBytes);
    return "-----BEGIN "
        + label
        + "-----"
        + System.lineSeparator()
        + base64
        + System.lineSeparator()
        + "-----END "
        + label
        + "-----"
        + System.lineSeparator();
  }

  public static KeyPair generateRsaKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }
}
