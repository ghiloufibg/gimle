package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * {@code gimle get ingresses}, {@code delete ingress} and {@code apply -f} for {@code kind:
 * Ingress}, driven end to end through {@link GimleCli#run} against a real store + Fafnir + control
 * plane -- the same in-process wiring {@code SecretCommandTest} establishes.
 */
@ResourceLock(value = Resources.SYSTEM_PROPERTIES, mode = ResourceAccessMode.READ)
class IngressCommandTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessCluster cluster;
  private String serverAddress;
  private ByteArrayOutputStream outBuffer;
  private ByteArrayOutputStream errBuffer;

  @BeforeEach
  void startCluster() {
    cluster = InProcessCluster.start(tempDir);
    serverAddress = cluster.address();
    outBuffer = new ByteArrayOutputStream();
    errBuffer = new ByteArrayOutputStream();
  }

  @AfterEach
  void stopCluster() {
    cluster.close();
  }

  private int run(String... args) {
    return GimleCli.run(args, new PrintStream(outBuffer), new PrintStream(errBuffer));
  }

  private String stdout() {
    return outBuffer.toString(StandardCharsets.UTF_8);
  }

  private String stderr() {
    return errBuffer.toString(StandardCharsets.UTF_8);
  }

  private Path manifest(String fileName, String body) {
    try {
      return Files.writeString(tempDir.resolve(fileName), body);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Path serviceIngress(String fileName, String tenantId, String path, String version) {
    return manifest(
        fileName,
        "kind: Ingress\n"
            + "name: public\n"
            + "tenantId: "
            + tenantId
            + "\n"
            + (version == null ? "" : "version: " + version + "\n")
            + "routes:\n"
            + "  - {kind: SERVICE, path: "
            + path
            + ", serviceName: orders}\n");
  }

  private int apply(Path file) {
    return run("apply", "-f", file.toString(), "--server", serverAddress);
  }

  @Test
  void get_ingresses_with_no_name_lists_every_declared_ingress() {
    assertEquals(0, apply(serviceIngress("a.yaml", "acme", "/a", null)), errBuffer::toString);
    outBuffer.reset();

    int exitCode = run("get", "ingresses", "--server", serverAddress, "-o", "json");

    assertEquals(0, exitCode, errBuffer::toString);
    assertTrue(stdout().startsWith("["), stdout());
    assertTrue(stdout().contains("\"name\":\"public\""), stdout());
  }

  @Test
  void get_ingresses_filters_the_listing_by_tenant_rather_than_reading_the_flag_as_a_name() {
    assertEquals(0, apply(serviceIngress("a.yaml", "acme", "/a", null)), errBuffer::toString);
    outBuffer.reset();

    int exitCode =
        run("get", "ingresses", "--tenant", "globex", "--server", serverAddress, "-o", "json");

    assertEquals(0, exitCode, errBuffer::toString);
    assertEquals("[]", stdout().strip());
  }

  @Test
  void get_ingress_by_name_still_reports_the_single_object() {
    assertEquals(0, apply(serviceIngress("a.yaml", "acme", "/a", null)), errBuffer::toString);
    outBuffer.reset();

    int exitCode =
        run(
            "get",
            "ingress",
            "public",
            "--tenant",
            "acme",
            "--server",
            serverAddress,
            "-o",
            "json");

    assertEquals(0, exitCode, errBuffer::toString);
    assertTrue(stdout().startsWith("{"), stdout());
  }

  @Test
  void re_applying_a_manifest_carrying_a_version_someone_else_moved_past_is_refused() {
    assertEquals(0, apply(serviceIngress("v1.yaml", "acme", "/a", null)), errBuffer::toString);
    // A second operator's edit, landing while the first still holds version 1.
    assertEquals(0, apply(serviceIngress("v2.yaml", "acme", "/b", "1")), errBuffer::toString);

    int exitCode = apply(serviceIngress("stale.yaml", "acme", "/c", "1"));

    assertNotEquals(0, exitCode, stdout());
    assertTrue(stderr().contains("ingress/public"), stderr());
    assertTrue(stderr().contains("version 2"), stderr());
    outBuffer.reset();
    assertEquals(
        0,
        run(
            "get",
            "ingress",
            "public",
            "--tenant",
            "acme",
            "--server",
            serverAddress,
            "-o",
            "json"),
        errBuffer::toString);
    // The second operator's route survives: the stale apply replaced nothing.
    assertTrue(stdout().contains("\"path\":\"/b\""), stdout());
    assertFalse(stdout().contains("\"path\":\"/c\""), stdout());
  }

  /**
   * A manifest that declares no version at all is still a legitimate create-or-replace, and the
   * guard this command supplies from its own read must not turn one into a conflict.
   */
  @Test
  void an_apply_declaring_no_version_creates_and_then_replaces_the_ingress() {
    assertEquals(0, apply(serviceIngress("v1.yaml", "acme", "/a", null)), errBuffer::toString);

    assertEquals(0, apply(serviceIngress("v2.yaml", "acme", "/b", null)), errBuffer::toString);

    outBuffer.reset();
    assertEquals(
        0,
        run(
            "get",
            "ingress",
            "public",
            "--tenant",
            "acme",
            "--server",
            serverAddress,
            "-o",
            "json"),
        errBuffer::toString);
    assertTrue(stdout().contains("\"version\":2"), stdout());
    assertTrue(stdout().contains("\"path\":\"/b\""), stdout());
  }

  @Test
  void applying_a_fabric_route_with_an_unknown_param_type_is_refused_naming_the_valid_values() {
    Path file =
        manifest(
            "fabric.yaml",
            """
            kind: Ingress
            name: greeter
            tenantId: acme
            routes:
              - {kind: FABRIC, path: /greet, interfaceName: com.acme.Greeter, majorVersion: 1,
                 methodName: greet, paramType: STRINGG}
            """);

    int exitCode = apply(file);

    assertNotEquals(0, exitCode, stdout());
    assertTrue(stderr().contains("STRINGG"), stderr());
    assertTrue(stderr().contains("NONE, STRING, INT, LONG, DOUBLE, BOOLEAN"), stderr());
    outBuffer.reset();
    assertEquals(0, run("get", "ingresses", "--server", serverAddress, "-o", "json"));
    assertEquals("[]", stdout().strip());
  }
}
