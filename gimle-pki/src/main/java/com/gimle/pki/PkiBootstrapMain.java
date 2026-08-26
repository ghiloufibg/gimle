package com.gimle.pki;

import com.gimle.core.authz.BuiltinRoles;
import com.gimle.core.authz.PasswordHashes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code mvn gimle:tls-init}'s entry point (spawned by {@code TlsInitMojo} against this module's
 * own resolved classpath, mirroring {@code DeployMojo}'s shape). Generates, once, everything a
 * brand-new cluster needs to start in {@code gimle.transport.protocol=tls} mode: the self-signed
 * cluster CA, the control plane's own leaf certificate, Fafnir's and Muninn's own leaf
 * certificates, and the first human operator's leaf certificate. A node agent deliberately gets
 * nothing here -- it obtains its own certificate later, live, via the CSR bootstrap flow ({@link
 * CertificateSigningRequests}/{@link OwnCertificateRotator}), the same reason {@code gimle-worker}
 * never depends on this module at all.
 *
 * <p>Known limitation: every leaf's SAN only carries DNS names (this module's {@link
 * CertificateSigningRequests} has no {@code iPAddress} SAN support), so a process reached by bare
 * IP literal (e.g. {@code https://127.0.0.1:8080}) will fail hostname verification even though the
 * handshake and CA trust chain are otherwise valid -- point clients at one of the SAN'd hostnames
 * (the positional {@code hostname...} arguments here) instead.
 */
public final class PkiBootstrapMain {

  private static final Logger log = LoggerFactory.getLogger(PkiBootstrapMain.class);
  private static final Duration CA_VALIDITY = Duration.ofDays(3650);
  private static final Duration LEAF_VALIDITY = Duration.ofDays(397);
  private static final int KEY_SIZE_BITS = 2048;
  private static final SecureRandom RANDOM = new SecureRandom();

  private PkiBootstrapMain() {}

  public static void main(String[] args) throws IOException {
    if (args.length < 3) {
      System.err.println("usage: PkiBootstrapMain <outputDir> <caCommonName> <hostname>...");
      System.exit(2);
      return;
    }
    Path outputDir = Path.of(args[0]);
    String caCommonName = args[1];
    List<String> hostnames = List.of(args).subList(2, args.length);
    Files.createDirectories(outputDir);

    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=" + caCommonName), CA_VALIDITY);
    writeCa(outputDir, ca);

    // One leaf per (role, hostname): every process's identity is attributable to its own
    // certificate Subject, not a borrowed one shared with every other machine hosting the same
    // role -- see the per-role comments below for why that matters most for Fafnir/Muninn/Andvari
    // specifically. Filenames disambiguate by hostname (<role>-<hostname>.crt/.key) since a
    // multi-machine topology mints more than one leaf per role.
    for (String hostname : hostnames) {
      issueLeaf(
          outputDir,
          ca,
          "controlplane-" + hostname,
          "CN=" + hostname,
          List.of(hostname, "localhost"));
      // Fafnir gets its own distinct identity from cluster-bootstrap time, a deliberate improvement
      // over gimle-mimir's own current stand-in (which still borrows the control plane's leaf in
      // local dev, per that class's own code comment): every action Fafnir takes being attributable
      // to its own certificate Subject, not a borrowed one, is directly load-bearing for its audit
      // story, since it's the one component whose entire job is being the trust boundary for secret
      // material.
      issueLeaf(
          outputDir, ca, "fafnir-" + hostname, "CN=" + hostname, List.of(hostname, "localhost"));
      // Muninn gets its own distinct identity for the identical reason Fafnir does: it re-runs its
      // own independent Authorizer.authorize(...) check on every proxied read rather than trusting
      // ApiServer's forwarded-principal claim as proof by itself, and that defense-in-depth check
      // needs to be attributable to Muninn's own certificate Subject, not a borrowed one.
      issueLeaf(
          outputDir, ca, "muninn-" + hostname, "CN=" + hostname, List.of(hostname, "localhost"));
      // Andvari gets its own distinct identity for the same reason Fafnir and Muninn do: it re-runs
      // its own independent Authorizer.authorize(...) check on artifact pushes/deletes rather than
      // trusting a forwarded claim, and pushing executable module jars is supply-chain-adjacent --
      // every such decision must be attributable to Andvari's own certificate Subject.
      issueLeaf(
          outputDir, ca, "andvari-" + hostname, "CN=" + hostname, List.of(hostname, "localhost"));
    }
    issueLeaf(
        outputDir,
        ca,
        "operator",
        "O=" + BuiltinRoles.GROUP_OPERATORS + ",CN=initial-operator",
        List.of());
    String bootstrapPassword = writeBootstrapAccount(outputDir);

    System.out.println(
        "wrote cluster CA, control-plane/fafnir/muninn/andvari material for "
            + hostnames.size()
            + " hostname(s), and initial-operator material to "
            + outputDir);
    System.out.println();
    System.out.println("bootstrap console account: username=admin password=" + bootstrapPassword);
    System.out.println(
        "record this password now -- it is never written to disk in plaintext and cannot be "
            + "recovered later. Log in to the console with it, then immediately run:");
    System.out.println(
        "  gimle set rolebinding admin-binding --subject user:admin --role cluster-admin");
    System.out.println("using the initial-operator certificate to grant it real access.");
  }

  /**
   * Writes a small bootstrap-only YAML file ({@code username}/{@code passwordHash}, hand-written
   * rather than pulling in a YAML dependency this module otherwise has no use for) that {@code
   * ApiServer} reads once at startup -- only while its {@code StateStore} has zero accounts -- and
   * Raft-proposes as a real {@code Account}. {@code gimle-pki} runs standalone, before any
   * control-plane process exists, so it cannot propose Raft state directly; this file is the
   * hand-off point, the same role {@code ca.key}/{@code operator.key} already play for certificate
   * material. Returns the generated plaintext password so the caller can print it exactly once.
   */
  private static String writeBootstrapAccount(Path outputDir) throws IOException {
    byte[] passwordBytes = new byte[24];
    RANDOM.nextBytes(passwordBytes);
    String password = Base64.getUrlEncoder().withoutPadding().encodeToString(passwordBytes);
    byte[] passwordHash = PasswordHashes.hash(password.toCharArray());
    String yaml =
        "username: admin\npasswordHash: " + Base64.getEncoder().encodeToString(passwordHash) + "\n";
    Files.writeString(outputDir.resolve("bootstrap-account.yaml"), yaml, StandardCharsets.US_ASCII);
    return password;
  }

  private static void writeCa(Path outputDir, CertificateAuthority ca) throws IOException {
    Files.writeString(
        outputDir.resolve("ca.crt"),
        Pem.encodeCertificate(ca.certificate()),
        StandardCharsets.US_ASCII);
    Path caKeyFile = outputDir.resolve("ca.key");
    Files.writeString(caKeyFile, Pem.encodePrivateKey(ca.privateKey()), StandardCharsets.US_ASCII);
    restrictPermissions(caKeyFile);
  }

  private static void issueLeaf(
      Path outputDir,
      CertificateAuthority ca,
      String fileNamePrefix,
      String subject,
      List<String> hostnames)
      throws IOException {
    KeyPair keyPair = generateKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(keyPair, new X500Name(subject), hostnames);
    X509Certificate certificate = ca.signCertificateRequest(csr, LEAF_VALIDITY);
    Files.writeString(
        outputDir.resolve(fileNamePrefix + ".crt"),
        Pem.encodeCertificate(certificate),
        StandardCharsets.US_ASCII);
    Path leafKeyFile = outputDir.resolve(fileNamePrefix + ".key");
    Files.writeString(
        leafKeyFile, Pem.encodePrivateKey(keyPair.getPrivate()), StandardCharsets.US_ASCII);
    restrictPermissions(leafKeyFile);
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

  /**
   * Restricts a freshly-written private key file to owner-read/write only wherever the filesystem
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
}
