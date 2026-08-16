package com.gimle.hilmir.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.sync.SyncCommand;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SyncCommand} lives in {@code com.gimle.hilmir.sync}, a separate package from every other
 * release verb's own tests -- this test class stays in {@code com.gimle.hilmir.release} anyway
 * (rather than a new, empty {@code com.gimle.hilmir.sync} test package) purely to reuse {@link
 * FakeControlPlane} package-private, the same fixture {@link DeployCommandTest}/{@link
 * UpgradeCommandTest}/{@link UndeployCommandTest} already use, without widening its visibility for
 * a single new caller.
 */
class SyncCommandTest {

  @TempDir Path tempDir;
  private FakeControlPlane fake;

  @AfterEach
  void tearDown() {
    if (fake != null) {
      fake.close();
    }
  }

  private static final String BUNDLE_ONE_WORKLOAD =
      """
      kind: Bundle
      name: %s
      version: 1.0.0
      tenants:
        - id: acme
          quota: {maxMemoryBytes: 1, maxCpuMillicores: 1, maxInstances: 1}
      config:
        - {tenant: acme, key: greeting.prefix, value: "Hello"}
      workloads:
        - manifest: |
            kind: Deployment
            name: greeter-provider
      """;

  private static final String BUNDLE_TWO_WORKLOADS =
      """
      kind: Bundle
      name: %s
      version: 1.0.0
      tenants:
        - id: acme
          quota: {maxMemoryBytes: 1, maxCpuMillicores: 1, maxInstances: 1}
      workloads:
        - manifest: |
            kind: Deployment
            name: greeter-provider
        - manifest: |
            kind: Deployment
            name: greeter-consumer
      """;

  private static final String BUNDLE_CHANGED_CONFIG =
      """
      kind: Bundle
      name: %s
      version: 2.0.0
      tenants:
        - id: acme
          quota: {maxMemoryBytes: 1, maxCpuMillicores: 1, maxInstances: 1}
      config:
        - {tenant: acme, key: greeting.prefix, value: "Bonjour"}
      workloads:
        - manifest: |
            kind: Deployment
            name: greeter-provider
      """;

  private static final String BUNDLE_UNKNOWN_KIND =
      """
      kind: Bundle
      name: %s
      version: 1.0.0
      workloads:
        - manifest: |
            kind: Bogus
            name: whatever
      """;

  private Path writeBundle(String template, String releaseName, String fileName)
      throws IOException {
    Path file = tempDir.resolve(fileName);
    Files.writeString(file, template.formatted(releaseName), StandardCharsets.UTF_8);
    return file;
  }

  private static PrintStream capture(ByteArrayOutputStream buffer) {
    return new PrintStream(buffer, true, StandardCharsets.UTF_8);
  }

  @Test
  void syncs_a_brand_new_bundle_as_a_fresh_deploy() throws Exception {
    fake = new FakeControlPlane();
    Path bundle = writeBundle(BUNDLE_ONE_WORKLOAD, "greeter-suite", "bundle.yaml");
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    int exitCode =
        SyncCommand.run(List.of("-f", bundle.toString(), "--server", fake.address()), capture(out));

    assertEquals(0, exitCode);
    assertTrue(fake.hasTenant("acme"));
    assertTrue(fake.hasWorkload("Deployment", "greeter-provider"));
    assertTrue(fake.configValue("gimle-hilmir", "hilmir.release.greeter-suite.rev.1") != null);
    assertTrue(out.toString(StandardCharsets.UTF_8).contains("deployed"));
  }

  @Test
  void a_bundle_unchanged_since_its_last_synced_revision_makes_no_mutating_call() throws Exception {
    fake = new FakeControlPlane();
    Path bundle = writeBundle(BUNDLE_ONE_WORKLOAD, "greeter-suite", "bundle.yaml");
    SyncCommand.run(
        List.of("-f", bundle.toString(), "--server", fake.address()),
        capture(new ByteArrayOutputStream()));
    int requestsAfterFirstSync = fake.requests.size();

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int exitCode =
        SyncCommand.run(List.of("-f", bundle.toString(), "--server", fake.address()), capture(out));

    assertEquals(0, exitCode);
    assertTrue(out.toString(StandardCharsets.UTF_8).contains("already converged"));
    // only the ledger reads (GET /config/gimle-hilmir) happen on the second pass -- no PUT/DELETE
    assertTrue(
        fake.requests.stream()
            .skip(requestsAfterFirstSync)
            .allMatch(r -> r.method().equals("GET")));
    assertTrue(fake.configValue("gimle-hilmir", "hilmir.release.greeter-suite.rev.2") == null);
  }

  @Test
  void a_bundle_whose_content_changed_upgrades_and_prunes_dropped_workloads() throws Exception {
    fake = new FakeControlPlane();
    Path v1 = writeBundle(BUNDLE_TWO_WORKLOADS, "greeter-suite", "v1.yaml");
    SyncCommand.run(
        List.of("-f", v1.toString(), "--server", fake.address()),
        capture(new ByteArrayOutputStream()));
    assertTrue(fake.hasWorkload("Deployment", "greeter-consumer"));

    Path v2 = writeBundle(BUNDLE_ONE_WORKLOAD, "greeter-suite", "v2.yaml");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int exitCode =
        SyncCommand.run(List.of("-f", v2.toString(), "--server", fake.address()), capture(out));

    assertEquals(0, exitCode);
    assertTrue(out.toString(StandardCharsets.UTF_8).contains("upgraded"));
    assertFalse(fake.hasWorkload("Deployment", "greeter-consumer"));
    assertTrue(fake.hasWorkload("Deployment", "greeter-provider"));
    assertTrue(fake.configValue("gimle-hilmir", "hilmir.release.greeter-suite.rev.2") != null);
  }

  @Test
  void dir_mode_reconciles_a_fresh_deploy_and_an_upgrade_in_one_invocation() throws Exception {
    fake = new FakeControlPlane();
    // pre-seed "existing-suite" at its v1 content directly, simulating a release from a prior sync
    Path preexisting = tempDir.resolve("seed.yaml");
    Files.writeString(
        preexisting, BUNDLE_ONE_WORKLOAD.formatted("existing-suite"), StandardCharsets.UTF_8);
    DeployCommand.run(
        List.of("-f", preexisting.toString(), "--server", fake.address()),
        capture(new ByteArrayOutputStream()));

    Path dir = tempDir.resolve("dir");
    Files.createDirectory(dir);
    Files.writeString(
        dir.resolve("a-new.yaml"),
        BUNDLE_ONE_WORKLOAD.formatted("new-suite"),
        StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("b-existing.yaml"),
        BUNDLE_CHANGED_CONFIG.formatted("existing-suite"),
        StandardCharsets.UTF_8);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int exitCode =
        SyncCommand.run(List.of("--dir", dir.toString(), "--server", fake.address()), capture(out));

    assertEquals(0, exitCode);
    String text = out.toString(StandardCharsets.UTF_8);
    assertTrue(text.contains("release/new-suite deployed"));
    assertTrue(text.contains("release/existing-suite upgraded"));
    assertTrue(fake.configValue("gimle-hilmir", "hilmir.release.new-suite.rev.1") != null);
    assertTrue(fake.configValue("gimle-hilmir", "hilmir.release.existing-suite.rev.2") != null);
    assertEquals("Bonjour", fake.configValue("acme", "greeting.prefix"));
  }

  @Test
  void dir_mode_rejects_two_files_declaring_the_same_bundle_name() throws Exception {
    fake = new FakeControlPlane();
    Path dir = tempDir.resolve("dir");
    Files.createDirectory(dir);
    Files.writeString(
        dir.resolve("a.yaml"), BUNDLE_ONE_WORKLOAD.formatted("dup-suite"), StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("b.yaml"), BUNDLE_TWO_WORKLOADS.formatted("dup-suite"), StandardCharsets.UTF_8);

    HilmirException e =
        assertThrows(
            HilmirException.class,
            () ->
                SyncCommand.run(
                    List.of("--dir", dir.toString(), "--server", fake.address()),
                    capture(new ByteArrayOutputStream())));

    assertTrue(e.getMessage().contains("dup-suite"));
    assertTrue(e.getMessage().contains("a.yaml"));
    assertTrue(e.getMessage().contains("b.yaml"));
    assertTrue(fake.requests.isEmpty());
  }

  @Test
  void neither_f_nor_dir_is_a_usage_error() {
    HilmirException e =
        assertThrows(
            HilmirException.class,
            () ->
                SyncCommand.run(
                    List.of("--server", "127.0.0.1:1"), capture(new ByteArrayOutputStream())));
    assertTrue(e.getMessage().contains("-f") && e.getMessage().contains("--dir"));
  }

  @Test
  void both_f_and_dir_is_a_usage_error() throws Exception {
    Path bundle = writeBundle(BUNDLE_ONE_WORKLOAD, "greeter-suite", "bundle.yaml");
    HilmirException e =
        assertThrows(
            HilmirException.class,
            () ->
                SyncCommand.run(
                    List.of(
                        "-f", bundle.toString(),
                        "--dir", tempDir.toString(),
                        "--server", "127.0.0.1:1"),
                    capture(new ByteArrayOutputStream())));
    assertTrue(e.getMessage().contains("-f") && e.getMessage().contains("--dir"));
  }

  @Test
  void dry_run_makes_zero_control_plane_calls() throws Exception {
    fake = new FakeControlPlane();
    Path bundle = writeBundle(BUNDLE_ONE_WORKLOAD, "greeter-suite", "bundle.yaml");
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    int exitCode =
        SyncCommand.run(
            List.of("-f", bundle.toString(), "--server", fake.address(), "--dry-run"),
            capture(out));

    assertEquals(0, exitCode);
    assertTrue(fake.requests.isEmpty());
    assertTrue(out.toString(StandardCharsets.UTF_8).contains("greeter-suite"));
  }

  @Test
  void a_per_bundle_apply_failure_in_dir_mode_does_not_abort_the_others() throws Exception {
    fake = new FakeControlPlane();
    Path dir = tempDir.resolve("dir");
    Files.createDirectory(dir);
    Files.writeString(
        dir.resolve("a-broken.yaml"),
        BUNDLE_UNKNOWN_KIND.formatted("broken-suite"),
        StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("b-healthy.yaml"),
        BUNDLE_ONE_WORKLOAD.formatted("healthy-suite"),
        StandardCharsets.UTF_8);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int exitCode =
        SyncCommand.run(List.of("--dir", dir.toString(), "--server", fake.address()), capture(out));

    assertNotEquals(0, exitCode);
    String text = out.toString(StandardCharsets.UTF_8);
    assertTrue(text.contains("broken-suite") && text.contains("FAILED"));
    assertTrue(text.contains("release/healthy-suite deployed"));
    assertTrue(fake.hasWorkload("Deployment", "greeter-provider"));
    assertTrue(fake.configValue("gimle-hilmir", "hilmir.release.healthy-suite.rev.1") != null);
    assertTrue(fake.configValue("gimle-hilmir", "hilmir.release.broken-suite.rev.1") == null);
  }

  @Test
  void prune_removes_a_release_no_longer_declared_by_any_synced_bundle() throws Exception {
    fake = new FakeControlPlane();
    Path orphanSeed = tempDir.resolve("orphan-seed.yaml");
    Files.writeString(
        orphanSeed, BUNDLE_ONE_WORKLOAD.formatted("orphan-suite"), StandardCharsets.UTF_8);
    DeployCommand.run(
        List.of("-f", orphanSeed.toString(), "--server", fake.address()),
        capture(new ByteArrayOutputStream()));
    assertTrue(fake.hasWorkload("Deployment", "greeter-provider"));

    Path dir = tempDir.resolve("dir");
    Files.createDirectory(dir);
    Files.writeString(
        dir.resolve("kept.yaml"),
        BUNDLE_ONE_WORKLOAD.formatted("kept-suite"),
        StandardCharsets.UTF_8);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int exitCode =
        SyncCommand.run(
            List.of("--dir", dir.toString(), "--server", fake.address(), "--prune"), capture(out));

    assertEquals(0, exitCode);
    assertTrue(out.toString(StandardCharsets.UTF_8).contains("orphan-suite pruned"));
    assertTrue(fake.configValue("gimle-hilmir", "hilmir.release.orphan-suite.meta") == null);
    assertTrue(fake.configValue("gimle-hilmir", "hilmir.release.kept-suite.meta") != null);
  }

  @Test
  void prune_without_dir_is_a_usage_error() throws Exception {
    Path bundle = writeBundle(BUNDLE_ONE_WORKLOAD, "greeter-suite", "bundle.yaml");
    HilmirException e =
        assertThrows(
            HilmirException.class,
            () ->
                SyncCommand.run(
                    List.of("-f", bundle.toString(), "--server", "127.0.0.1:1", "--prune"),
                    capture(new ByteArrayOutputStream())));
    assertTrue(e.getMessage().contains("--prune"));
  }
}
