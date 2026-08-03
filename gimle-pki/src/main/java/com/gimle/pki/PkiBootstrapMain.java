package com.gimle.pki;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;

/**
 * {@code mvn gimle:tls-init}'s entry point (spawned by {@code TlsInitMojo} against this module's
 * own resolved classpath, mirroring {@code DeployMojo}'s shape). Generates, once, everything a
 * brand-new cluster needs to start in {@code gimle.transport.protocol=tls} mode: the self-signed
 * cluster CA, the control plane's own leaf certificate, and the first human operator's leaf
 * certificate. A node agent deliberately gets nothing here -- it obtains its own certificate later,
 * live, via {@code claudedocs/tls-transport-security-design.md} §4's CSR bootstrap flow, the same
 * reason {@code gimle-worker} never depends on this module at all.
 *
 * <p>Known limitation: the control plane's leaf SAN only carries DNS names (this module's {@link
 * CertificateSigningRequests} has no {@code iPAddress} SAN support), so a control plane reached by
 * bare IP literal (e.g. {@code https://127.0.0.1:8080}) will fail hostname verification even though
 * the handshake and CA trust chain are otherwise valid -- point clients at the SAN'd hostname (the
 * {@code --hostname} argument here, "localhost" by default) instead.
 */
public final class PkiBootstrapMain {

  private static final Duration CA_VALIDITY = Duration.ofDays(3650);
  private static final Duration LEAF_VALIDITY = Duration.ofDays(397);
  private static final int KEY_SIZE_BITS = 2048;

  private PkiBootstrapMain() {}

  public static void main(String[] args) throws IOException {
    if (args.length < 3) {
      System.err.println("usage: PkiBootstrapMain <outputDir> <caCommonName> <hostname>");
      System.exit(2);
      return;
    }
    Path outputDir = Path.of(args[0]);
    String caCommonName = args[1];
    String hostname = args[2];
    Files.createDirectories(outputDir);

    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=" + caCommonName), CA_VALIDITY);
    writeCa(outputDir, ca);

    issueLeaf(outputDir, ca, "controlplane", "CN=" + hostname, List.of(hostname, "localhost"));
    issueLeaf(outputDir, ca, "operator", "CN=initial-operator", List.of());

    System.out.println(
        "wrote cluster CA, control-plane, and initial-operator material to " + outputDir);
  }

  private static void writeCa(Path outputDir, CertificateAuthority ca) throws IOException {
    Files.writeString(
        outputDir.resolve("ca.crt"),
        Pem.encodeCertificate(ca.certificate()),
        StandardCharsets.US_ASCII);
    Files.writeString(
        outputDir.resolve("ca.key"),
        Pem.encodePrivateKey(ca.privateKey()),
        StandardCharsets.US_ASCII);
  }

  private static void issueLeaf(
      Path outputDir,
      CertificateAuthority ca,
      String fileNamePrefix,
      String subject,
      List<String> dnsNames)
      throws IOException {
    KeyPair keyPair = generateKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(keyPair, new X500Name(subject), dnsNames);
    X509Certificate certificate = ca.signCertificateRequest(csr, LEAF_VALIDITY);
    Files.writeString(
        outputDir.resolve(fileNamePrefix + ".crt"),
        Pem.encodeCertificate(certificate),
        StandardCharsets.US_ASCII);
    Files.writeString(
        outputDir.resolve(fileNamePrefix + ".key"),
        Pem.encodePrivateKey(keyPair.getPrivate()),
        StandardCharsets.US_ASCII);
  }

  private static KeyPair generateKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(KEY_SIZE_BITS);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("RSA key pair generation unavailable", e);
    }
  }
}
