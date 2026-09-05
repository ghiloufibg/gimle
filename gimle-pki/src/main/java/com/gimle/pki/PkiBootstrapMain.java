package com.gimle.pki;

import com.gimle.core.authz.BuiltinRoles;
import com.gimle.core.authz.PasswordHashes;
import com.gimle.core.exception.GimleSecretsException;
import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
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
 * <p>The run also mints the cluster's one-time bootstrap console password. It is never printed into
 * output that could be captured: a terminal gets it directly, and any non-interactive run (a build,
 * a pipeline, anything with its output redirected) must name a {@code --password-file} to write it
 * to instead, or the run is refused before it generates anything at all. Silently printing it into
 * an inherited or redirected stream would put the cluster's initial administrator credential into a
 * build log, which is the one place this design exists to keep it out of.
 *
 * <p>Known limitation: every leaf minted here is named only by the positional {@code hostname...}
 * arguments, which are DNS names, so a process reached by bare IP literal (e.g. {@code
 * https://127.0.0.1:8080}) fails hostname verification even though the handshake and CA trust chain
 * are otherwise valid -- point clients at one of those hostnames instead, or pass the literal as a
 * hostname argument, since {@link CertificateSigningRequests} does type an IP literal as an {@code
 * iPAddress} SAN entry (the only kind an IP-dialed handshake ever matches).
 */
public final class PkiBootstrapMain {

  private static final Logger log = LoggerFactory.getLogger(PkiBootstrapMain.class);
  private static final Duration CA_VALIDITY = Duration.ofDays(3650);
  private static final Duration LEAF_VALIDITY = Duration.ofDays(397);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String PASSWORD_FILE_FLAG = "--password-file";

  private PkiBootstrapMain() {}

  public static void main(final String[] args) throws IOException {
    final Options options;
    try {
      options = Options.parse(args);
    } catch (final IllegalArgumentException e) {
      System.err.println(e.getMessage());
      System.err.println(
          "usage: PkiBootstrapMain [--password-file <file>] <outputDir> <caCommonName>"
              + " <hostname>...");
      System.exit(2);
      return;
    }
    try {
      run(options, System.out, standardOutputIsATerminal());
    } catch (final GimleSecretsException e) {
      System.err.println(e.getMessage());
      System.exit(2);
    }
  }

  /**
   * Everything {@link #main} does once its arguments parse, with the two things a test cannot
   * supply for itself -- where output goes, and whether that output is a real terminal -- passed in
   * rather than read from the JVM.
   */
  static void run(final Options options, final PrintStream out, final boolean interactiveTerminal)
      throws IOException {
    // Checked before a single file is written: a run with nowhere safe to put the password it is
    // about to mint must fail while there is still nothing on disk to clean up.
    requirePasswordDeliverable(options, interactiveTerminal);
    final Path outputDir = options.outputDir();
    Files.createDirectories(outputDir);

    final CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(
            new X500Name("CN=" + options.caCommonName()), CA_VALIDITY);
    writeCa(outputDir, ca);

    // One leaf per (role, hostname): every process's identity is attributable to its own
    // certificate Subject, not a borrowed one shared with every other machine hosting the same
    // role -- see the per-role comments below for why that matters most for Fafnir/Muninn/Andvari
    // specifically. Filenames disambiguate by hostname (<role>-<hostname>.crt/.key) since a
    // multi-machine topology mints more than one leaf per role.
    for (final String hostname : options.hostnames()) {
      // O=gimle:controlplane (unlike Fafnir/Muninn/Andvari below, which get no O= of their own):
      // the control plane's own scheduling-time artifact pull authenticates as this leaf when it
      // calls Andvari directly, and needs BuiltinRoles.GROUP_CONTROLPLANE's implicit ARTIFACT:read
      // grant (see Authorizer's own javadoc) -- without it, a fresh mTLS cluster's own control
      // plane could never pull an artifact it didn't already have cached, with no default
      // RoleBinding to close the gap.
      issueLeaf(
          outputDir,
          ca,
          "controlplane-" + hostname,
          "O=" + BuiltinRoles.GROUP_CONTROLPLANE + ",CN=" + hostname,
          List.of(hostname, "localhost"));
      // The store gets its own leaf rather than presenting the control plane's: borrowing it made
      // the store claim, on the wire, to be the very process that authenticates to it, so a peer
      // could not tell a store replica apart from a control-plane client and neither side's
      // identity meant anything. Its Subject carries no O= -- the store authorizes nothing on
      // group membership, it only needs to be identifiable as itself.
      issueLeaf(
          outputDir, ca, "store-" + hostname, "CN=" + hostname, List.of(hostname, "localhost"));
      // Fafnir gets its own distinct identity from cluster-bootstrap time: every action Fafnir
      // takes being attributable to its own certificate Subject, not a borrowed one, is directly
      // load-bearing for its audit story, since it's the one component whose entire job is being
      // the trust boundary for secret material.
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
      // The store gets its own identity for the same reason every other role does. Presenting the
      // control plane's leaf instead made a compromised store key a control-plane identity, and
      // left neither authorization nor audit able to tell the two apart.
      issueLeaf(
          outputDir, ca, "store-" + hostname, "CN=" + hostname, List.of(hostname, "localhost"));
    }
    issueLeaf(
        outputDir,
        ca,
        "operator",
        "O=" + BuiltinRoles.GROUP_OPERATORS + ",CN=initial-operator",
        List.of());
    final String bootstrapPassword = writeBootstrapAccount(outputDir);

    out.println(
        "wrote cluster CA, control-plane/store/fafnir/muninn/andvari material for "
            + options.hostnames().size()
            + " hostname(s), and initial-operator material to "
            + outputDir);
    out.println();
    deliverBootstrapPassword(bootstrapPassword, options.passwordFile(), out);
    out.println(
        "Log in to the console with username 'admin' and that password, then immediately run:");
    out.println("  gimle set rolebinding admin-binding --subject user:admin --role cluster-admin");
    out.println("using the initial-operator certificate to grant it real access.");
  }

  /**
   * The parsed command line: the {@code --password-file} flag in any position, then positionals.
   */
  record Options(
      Path outputDir, String caCommonName, List<String> hostnames, Optional<Path> passwordFile) {

    static Options parse(final String[] args) {
      final List<String> positional = new ArrayList<>();
      Path passwordFile = null;
      for (int i = 0; i < args.length; i++) {
        if (PASSWORD_FILE_FLAG.equals(args[i])) {
          if (i + 1 >= args.length) {
            throw new IllegalArgumentException(PASSWORD_FILE_FLAG + " needs a file path");
          }
          passwordFile = Path.of(args[++i]);
        } else {
          positional.add(args[i]);
        }
      }
      if (positional.size() < 3) {
        throw new IllegalArgumentException(
            "expected an output directory, a CA common name, and at least one hostname");
      }
      return new Options(
          Path.of(positional.get(0)),
          positional.get(1),
          List.copyOf(positional.subList(2, positional.size())),
          Optional.ofNullable(passwordFile));
    }
  }

  /**
   * True only when this process's own standard output really is a terminal a human is watching. A
   * redirected or inherited stream (a build spawning this as a subprocess, a pipeline capturing it,
   * a shell redirect into a file) reports false, which is exactly the case where printing the
   * password would persist it somewhere it was never meant to live.
   */
  private static boolean standardOutputIsATerminal() {
    final Console console = System.console();
    return console != null && console.isTerminal();
  }

  private static void requirePasswordDeliverable(
      final Options options, final boolean interactiveTerminal) {
    if (options.passwordFile().isPresent() || interactiveTerminal) {
      return;
    }
    throw GimleSecretsException.undeliverableBootstrapPassword(
        "refusing to generate cluster material: this run has nowhere safe to put the one-time"
            + " bootstrap admin password. Standard output is not a terminal, so printing it would"
            + " write the plaintext password into whatever log or file the output was redirected"
            + " to. Re-run with "
            + PASSWORD_FILE_FLAG
            + " <file> to have it written to an owner-only file instead, or run this from a"
            + " terminal.");
  }

  /**
   * An interactive run gets the password on screen and nowhere else; a run that named a file gets
   * it written there with owner-only permissions, and only the file's path on screen. Either way it
   * exists in exactly one place, chosen deliberately.
   */
  private static void deliverBootstrapPassword(
      final String password, final Optional<Path> passwordFile, final PrintStream out)
      throws IOException {
    if (passwordFile.isEmpty()) {
      out.println("bootstrap console account: username=admin password=" + password);
      out.println(
          "record this password now -- it is written nowhere at all and cannot be recovered"
              + " later.");
      return;
    }
    final Path file = passwordFile.get();
    final Path parent = file.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(file, password + System.lineSeparator(), StandardCharsets.US_ASCII);
    restrictPermissions(file);
    out.println(
        "bootstrap console account: username=admin, one-time password written to "
            + file
            + " (owner-readable only).");
    out.println(
        "read it and delete that file -- it is the only copy, and nothing can recover the password"
            + " once it is gone.");
  }

  /**
   * Writes a small bootstrap-only YAML file ({@code username}/{@code passwordHash}, hand-written
   * rather than pulling in a YAML dependency this module otherwise has no use for) that {@code
   * ApiServer} reads once at startup -- only while its {@code StateStore} has zero accounts -- and
   * Raft-proposes as a real {@code Account}. {@code gimle-pki} runs standalone, before any
   * control-plane process exists, so it cannot propose Raft state directly; this file is the
   * hand-off point, the same role {@code ca.key}/{@code operator.key} already play for certificate
   * material. Returns the generated plaintext password so the caller can deliver it exactly once.
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
      generator.initialize(CertificateAuthority.KEY_SIZE_BITS);
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
