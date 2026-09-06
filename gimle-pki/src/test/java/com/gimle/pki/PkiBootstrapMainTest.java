package com.gimle.pki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.authz.BuiltinRoles;
import com.gimle.core.authz.PasswordHashes;
import com.gimle.core.authz.Principal;
import com.gimle.core.exception.GimleSecretsException;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link PkiBootstrapMain}'s multi-hostname leaf minting -- one {@code <role>-<hostname>}
 * cert/key pair per (role, hostname) pair, distinct from every other hostname's own leaf -- and
 * where the one-time bootstrap password is allowed to end up.
 */
class PkiBootstrapMainTest {

  @TempDir Path outputDir;

  @Test
  void mints_one_leaf_per_role_per_hostname() throws IOException {
    bootstrapToPasswordFile("h1", "h2");

    for (String role : List.of("controlplane", "fafnir", "muninn", "andvari")) {
      for (String hostname : List.of("h1", "h2")) {
        assertTrue(
            Files.exists(outputDir.resolve(role + "-" + hostname + ".crt")),
            role + "-" + hostname + ".crt should exist");
        assertTrue(
            Files.exists(outputDir.resolve(role + "-" + hostname + ".key")),
            role + "-" + hostname + ".key should exist");
      }
    }
    assertTrue(Files.exists(outputDir.resolve("ca.crt")));
    assertTrue(Files.exists(outputDir.resolve("ca.key")));
    assertTrue(Files.exists(outputDir.resolve("operator.crt")));
    assertTrue(Files.exists(outputDir.resolve("operator.key")));
    assertTrue(Files.exists(outputDir.resolve("bootstrap-account.yaml")));
  }

  @Test
  void a_hostnames_own_leaf_is_sand_to_that_hostname_and_localhost_only() throws Exception {
    bootstrapToPasswordFile("h1", "h2");

    X509Certificate h1ControlPlane = readCertificate(outputDir.resolve("controlplane-h1.crt"));
    Collection<List<?>> sans = h1ControlPlane.getSubjectAlternativeNames();
    List<String> dnsNames =
        sans.stream().map(entry -> (String) entry.get(1)).collect(Collectors.toList());

    assertTrue(dnsNames.contains("h1"));
    assertTrue(dnsNames.contains("localhost"));
    assertFalse(dnsNames.contains("h2"), "h1's own leaf must not carry h2 in its SAN list");
  }

  /**
   * ADD-10: without an {@code O=} of its own, the control plane's own scheduling-time artifact pull
   * had no group any {@code Authorizer} grant could ever match, so a fresh mTLS cluster's own
   * control plane could never pull an artifact it didn't already have cached.
   */
  @Test
  void the_control_plane_leaf_carries_the_controlplane_group_but_other_roles_do_not()
      throws Exception {
    bootstrapToPasswordFile("h1");

    X509Certificate controlPlane = readCertificate(outputDir.resolve("controlplane-h1.crt"));
    Principal principal = Subjects.principalFrom(controlPlane);
    assertEquals("h1", principal.name());
    assertEquals(Set.of(BuiltinRoles.GROUP_CONTROLPLANE), principal.groups());

    X509Certificate fafnir = readCertificate(outputDir.resolve("fafnir-h1.crt"));
    assertEquals(Set.of(), Subjects.principalFrom(fafnir).groups());
  }

  /**
   * The store used to present the control plane's own leaf, so on the wire a store replica was
   * indistinguishable from the very process that authenticates to it and neither side's identity
   * meant anything.
   */
  @Test
  void the_store_gets_its_own_leaf_rather_than_borrowing_the_control_planes() throws Exception {
    bootstrapToPasswordFile("h1");

    X509Certificate store = readCertificate(outputDir.resolve("store-h1.crt"));
    assertTrue(Files.exists(outputDir.resolve("store-h1.key")));
    Principal principal = Subjects.principalFrom(store);
    assertEquals("h1", principal.name());
    // No group of its own: the store authorizes nothing on group membership, and carrying the
    // control plane's group would hand it that role's grants for free.
    assertEquals(Set.of(), principal.groups());
    X509Certificate controlPlane = readCertificate(outputDir.resolve("controlplane-h1.crt"));
    assertNotEquals(
        controlPlane.getSubjectX500Principal(),
        store.getSubjectX500Principal(),
        "the store must not present the control plane's own Subject");
  }

  @Test
  void every_hostnames_leaves_for_one_role_are_signed_by_the_same_shared_ca() throws Exception {
    bootstrapToPasswordFile("h1", "h2");

    X509Certificate ca = readCertificate(outputDir.resolve("ca.crt"));
    X509Certificate h1 = readCertificate(outputDir.resolve("fafnir-h1.crt"));
    X509Certificate h2 = readCertificate(outputDir.resolve("fafnir-h2.crt"));

    h1.verify(ca.getPublicKey());
    h2.verify(ca.getPublicKey());
    assertEquals(ca.getSubjectX500Principal(), h1.getIssuerX500Principal());
  }

  @Test
  void a_non_interactive_run_writes_the_password_to_the_named_file_and_never_to_its_output()
      throws Exception {
    ByteArrayOutputStream captured = new ByteArrayOutputStream();

    PkiBootstrapMain.run(options(Optional.of(passwordFile())), new PrintStream(captured), false);

    String password = Files.readString(passwordFile(), StandardCharsets.US_ASCII).strip();
    assertFalse(password.isBlank());
    String output = captured.toString(StandardCharsets.UTF_8);
    assertFalse(output.contains(password), "the password must never reach a capturable stream");
    assertTrue(output.contains(passwordFile().toString()), "output should name the file instead");
  }

  /** The file has to hold the password the cluster will actually accept, not merely some string. */
  @Test
  void the_written_password_verifies_against_the_account_files_own_hash() throws Exception {
    PkiBootstrapMain.run(options(Optional.of(passwordFile())), discardingOut(), false);

    String password = Files.readString(passwordFile(), StandardCharsets.US_ASCII).strip();
    String yaml = Files.readString(outputDir.resolve("bootstrap-account.yaml"));
    String encodedHash =
        yaml.lines()
            .filter(line -> line.startsWith("passwordHash:"))
            .findFirst()
            .orElseThrow()
            .substring("passwordHash:".length())
            .strip();

    assertTrue(
        PasswordHashes.verify(password.toCharArray(), Base64.getDecoder().decode(encodedHash)));
  }

  @Test
  void the_password_file_is_restricted_to_its_owner() throws Exception {
    PkiBootstrapMain.run(options(Optional.of(passwordFile())), discardingOut(), false);

    if (passwordFile().getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertEquals(
          "rw-------",
          PosixFilePermissions.toString(Files.getPosixFilePermissions(passwordFile())));
    }
  }

  /**
   * The whole point of the refusal: a build, a pipeline, or any redirected run would otherwise
   * print the cluster's initial administrator credential into a log that outlives the command.
   */
  @Test
  void a_non_interactive_run_with_no_password_file_generates_nothing_and_explains_itself() {
    GimleSecretsException e =
        assertThrows(
            GimleSecretsException.class,
            () -> PkiBootstrapMain.run(options(Optional.empty()), discardingOut(), false));

    assertTrue(e.getMessage().contains("--password-file"), e.getMessage());
    assertFalse(Files.exists(outputDir.resolve("ca.crt")), "nothing may be generated on refusal");
    assertFalse(Files.exists(outputDir.resolve("bootstrap-account.yaml")));
  }

  @Test
  void an_interactive_run_prints_the_password_and_writes_no_file_at_all() throws Exception {
    ByteArrayOutputStream captured = new ByteArrayOutputStream();

    PkiBootstrapMain.run(options(Optional.empty()), new PrintStream(captured), true);

    String output = captured.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("username=admin password="), output);
    assertFalse(Files.exists(passwordFile()));
  }

  private PkiBootstrapMain.Options options(Optional<Path> passwordFile) {
    return new PkiBootstrapMain.Options(outputDir, "test-ca", List.of("h1"), passwordFile);
  }

  private Path passwordFile() {
    return outputDir.resolve("bootstrap-password.txt");
  }

  private void bootstrapToPasswordFile(String... hostnames) throws IOException {
    List<String> args =
        new ArrayList<>(
            List.of("--password-file", passwordFile().toString(), outputDir.toString(), "test-ca"));
    args.addAll(List.of(hostnames));
    PkiBootstrapMain.main(args.toArray(new String[0]));
  }

  private static PrintStream discardingOut() {
    return new PrintStream(ByteArrayOutputStream.nullOutputStream());
  }

  private static X509Certificate readCertificate(Path file) throws Exception {
    try (FileInputStream in = new FileInputStream(file.toFile())) {
      return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
    }
  }
}
