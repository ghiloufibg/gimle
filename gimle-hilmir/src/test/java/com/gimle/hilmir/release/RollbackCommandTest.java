package com.gimle.hilmir.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import com.gimle.hilmir.HilmirException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RollbackCommandTest {

  @TempDir Path tempDir;
  private FakeControlPlane fake;

  @AfterEach
  void tearDown() {
    if (fake != null) {
      fake.close();
    }
  }

  private static final String BUNDLE_V1 =
      """
      kind: Bundle
      name: greeter-suite
      version: 1.0.0
      config:
        - {tenant: acme, key: greeting.prefix, value: "Hello"}
      workloads:
        - manifest: |
            kind: Deployment
            name: greeter-provider
      """;

  private static final String BUNDLE_V2 =
      """
      kind: Bundle
      name: greeter-suite
      version: 2.0.0
      config:
        - {tenant: acme, key: greeting.prefix, value: "Goodbye"}
      workloads:
        - manifest: |
            kind: Deployment
            name: greeter-provider
        - manifest: |
            kind: Deployment
            name: greeter-consumer
      """;

  private Path writeBundle(String contents, String name) throws IOException {
    Path file = tempDir.resolve(name);
    Files.writeString(file, contents, StandardCharsets.UTF_8);
    return file;
  }

  private static PrintStream capture(ByteArrayOutputStream buffer) {
    return new PrintStream(buffer, true, StandardCharsets.UTF_8);
  }

  private void deployThenUpgrade() throws Exception {
    DeployCommand.run(
        List.of("-f", writeBundle(BUNDLE_V1, "v1.yaml").toString(), "--server", fake.address()),
        capture(new ByteArrayOutputStream()));
    UpgradeCommand.run(
        List.of("-f", writeBundle(BUNDLE_V2, "v2.yaml").toString(), "--server", fake.address()),
        capture(new ByteArrayOutputStream()));
  }

  @Test
  void rollback_with_no_to_revision_restores_the_revision_before_the_current_one()
      throws Exception {
    fake = new FakeControlPlane();
    deployThenUpgrade();
    assertEquals("Goodbye", fake.configValue("acme", "greeting.prefix"));
    assertTrue(fake.hasWorkload("Deployment", "greeter-consumer"));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int exitCode =
        RollbackCommand.run(
            List.of("--release", "greeter-suite", "--server", fake.address()), capture(out));

    assertEquals(0, exitCode);
    assertEquals("Hello", fake.configValue("acme", "greeting.prefix"));
    // rollback prunes the resource revision 2 introduced but revision 1 never had
    assertTrue(
        !fake.hasWorkload("Deployment", "greeter-consumer")
            || fake.requests.stream()
                .anyMatch(
                    r ->
                        r.method().equals("DELETE")
                            && r.path().equals("/deployments/greeter-consumer")));

    // rollback writes a NEW revision (3), never rewrites revision 1 or 2 in place
    assertTrue(fake.configValue("gimle-hilmir", "hilmir.release.greeter-suite.rev.1") != null);
    assertTrue(fake.configValue("gimle-hilmir", "hilmir.release.greeter-suite.rev.2") != null);
    assertTrue(fake.configValue("gimle-hilmir", "hilmir.release.greeter-suite.rev.3") != null);
    Map<String, Object> meta =
        Json.asObject(
            Json.parse(fake.configValue("gimle-hilmir", "hilmir.release.greeter-suite.meta")));
    assertEquals(3L, ((Number) meta.get("currentRevision")).longValue());
  }

  @Test
  void rollback_to_an_explicit_revision_reads_that_exact_revisions_snapshot() throws Exception {
    fake = new FakeControlPlane();
    deployThenUpgrade();

    RollbackCommand.run(
        List.of(
            "--release", "greeter-suite",
            "--to-revision", "1",
            "--server", fake.address()),
        capture(new ByteArrayOutputStream()));

    assertEquals("Hello", fake.configValue("acme", "greeting.prefix"));
  }

  @Test
  void rollback_records_which_revision_it_restored() throws Exception {
    fake = new FakeControlPlane();
    deployThenUpgrade();

    RollbackCommand.run(
        List.of("--release", "greeter-suite", "--server", fake.address()),
        capture(new ByteArrayOutputStream()));

    Map<String, Object> revisionThree =
        Json.asObject(
            Json.parse(fake.configValue("gimle-hilmir", "hilmir.release.greeter-suite.rev.3")));
    assertEquals(1L, ((Number) revisionThree.get("rollbackOfRevision")).longValue());
  }

  @Test
  void rollback_fails_cleanly_for_a_release_with_no_earlier_revision() throws Exception {
    fake = new FakeControlPlane();
    DeployCommand.run(
        List.of("-f", writeBundle(BUNDLE_V1, "v1.yaml").toString(), "--server", fake.address()),
        capture(new ByteArrayOutputStream()));

    HilmirException e =
        assertThrows(
            HilmirException.class,
            () ->
                RollbackCommand.run(
                    List.of("--release", "greeter-suite", "--server", fake.address()),
                    capture(new ByteArrayOutputStream())));
    assertTrue(e.getMessage().contains("--to-revision"));
  }

  @Test
  void rollback_fails_cleanly_for_a_nonexistent_release() throws Exception {
    fake = new FakeControlPlane();

    assertThrows(
        HilmirException.class,
        () ->
            RollbackCommand.run(
                List.of("--release", "no-such-release", "--server", fake.address()),
                capture(new ByteArrayOutputStream())));
  }
}
