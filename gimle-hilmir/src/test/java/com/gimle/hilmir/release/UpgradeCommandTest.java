package com.gimle.hilmir.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.HilmirException;
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

class UpgradeCommandTest {

  @TempDir Path tempDir;
  private FakeControlPlane fake;

  @AfterEach
  void tearDown() {
    if (fake != null) {
      fake.close();
    }
  }

  private static final String BUNDLE_WITH_TWO_WORKLOADS =
      """
      kind: Bundle
      name: greeter-suite
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

  private static final String BUNDLE_WITH_ONE_WORKLOAD =
      """
      kind: Bundle
      name: greeter-suite
      version: 2.0.0
      tenants:
        - id: acme
          quota: {maxMemoryBytes: 1, maxCpuMillicores: 1, maxInstances: 1}
      workloads:
        - manifest: |
            kind: Deployment
            name: greeter-provider
      """;

  private Path writeBundle(String contents, String name) throws IOException {
    Path file = tempDir.resolve(name);
    Files.writeString(file, contents, StandardCharsets.UTF_8);
    return file;
  }

  private static PrintStream capture(ByteArrayOutputStream buffer) {
    return new PrintStream(buffer, true, StandardCharsets.UTF_8);
  }

  @Test
  void upgrade_prunes_a_workload_the_previous_revision_had_that_the_new_bundle_drops()
      throws Exception {
    fake = new FakeControlPlane();
    Path v1 = writeBundle(BUNDLE_WITH_TWO_WORKLOADS, "v1.yaml");
    DeployCommand.run(
        List.of("-f", v1.toString(), "--server", fake.address()),
        capture(new ByteArrayOutputStream()));
    assertTrue(fake.hasWorkload("Deployment", "greeter-consumer"));

    Path v2 = writeBundle(BUNDLE_WITH_ONE_WORKLOAD, "v2.yaml");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int exitCode =
        UpgradeCommand.run(List.of("-f", v2.toString(), "--server", fake.address()), capture(out));

    assertEquals(0, exitCode);
    assertFalse(fake.hasWorkload("Deployment", "greeter-consumer"));
    assertTrue(fake.hasWorkload("Deployment", "greeter-provider"));
    assertTrue(
        fake.requests.stream()
            .anyMatch(
                r ->
                    r.method().equals("DELETE")
                        && r.path().equals("/deployments/greeter-consumer")));
    assertTrue(fake.configValue("gimle-hilmir", "hilmir.release.greeter-suite.rev.2") != null);
  }

  private static final String BUNDLE_WITH_RENAMED_SECRET =
      """
      kind: Bundle
      name: greeter-suite
      version: 2.0.0
      tenants:
        - id: acme
          quota: {maxMemoryBytes: 1, maxCpuMillicores: 1, maxInstances: 1}
      config:
        - {tenant: acme, key: greeting, value: hei}
      secrets:
        - {tenant: acme, key: api-token-v2, value: s3cret}
      workloads:
        - manifest: |
            kind: Deployment
            name: greeter-provider
      """;

  /**
   * The orphan this closes is worse than a leftover row: a config lookup falls back to the vault
   * for the same key, so a secret a release stopped declaring goes on silently answering reads
   * meant for the config entry the operator can actually see.
   */
  @Test
  void upgrade_prunes_a_secret_and_a_config_key_the_new_bundle_no_longer_declares()
      throws Exception {
    fake = new FakeControlPlane();
    String v1Text =
        """
        kind: Bundle
        name: greeter-suite
        version: 1.0.0
        tenants:
          - id: acme
            quota: {maxMemoryBytes: 1, maxCpuMillicores: 1, maxInstances: 1}
        config:
          - {tenant: acme, key: greeting, value: hello}
          - {tenant: acme, key: retired, value: gone}
        secrets:
          - {tenant: acme, key: api-token, value: s3cret}
        workloads:
          - manifest: |
              kind: Deployment
              name: greeter-provider
        """;
    Path v1 = writeBundle(v1Text, "v1.yaml");
    DeployCommand.run(
        List.of("-f", v1.toString(), "--server", fake.address()),
        capture(new ByteArrayOutputStream()));

    Path v2 = writeBundle(BUNDLE_WITH_RENAMED_SECRET, "v2.yaml");
    assertEquals(
        0,
        UpgradeCommand.run(
            List.of("-f", v2.toString(), "--server", fake.address()),
            capture(new ByteArrayOutputStream())));

    assertTrue(deleted("/secrets/acme/api-token"), fake.requests.toString());
    assertTrue(deleted("/config/acme/retired"), fake.requests.toString());
    // The key both revisions declare is left exactly where it is.
    assertFalse(deleted("/config/acme/greeting"), fake.requests.toString());
    assertFalse(deleted("/secrets/acme/api-token-v2"), fake.requests.toString());
  }

  private boolean deleted(String path) {
    return fake.requests.stream()
        .anyMatch(r -> r.method().equals("DELETE") && r.path().equals(path));
  }

  @Test
  void upgrade_requires_an_existing_release() throws Exception {
    fake = new FakeControlPlane();
    Path v1 = writeBundle(BUNDLE_WITH_ONE_WORKLOAD, "v1.yaml");

    HilmirException e =
        assertThrows(
            HilmirException.class,
            () ->
                UpgradeCommand.run(
                    List.of("-f", v1.toString(), "--server", fake.address()),
                    capture(new ByteArrayOutputStream())));
    assertTrue(e.getMessage().contains("no release named"));
  }

  @Test
  void upgrade_dry_run_computes_the_prune_list_but_issues_no_mutating_call() throws Exception {
    fake = new FakeControlPlane();
    Path v1 = writeBundle(BUNDLE_WITH_TWO_WORKLOADS, "v1.yaml");
    DeployCommand.run(
        List.of("-f", v1.toString(), "--server", fake.address()),
        capture(new ByteArrayOutputStream()));
    int requestsAfterDeploy = fake.requests.size();

    Path v2 = writeBundle(BUNDLE_WITH_ONE_WORKLOAD, "v2.yaml");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    UpgradeCommand.run(
        List.of("-f", v2.toString(), "--server", fake.address(), "--dry-run"), capture(out));

    assertTrue(out.toString(StandardCharsets.UTF_8).contains("greeter-consumer"));
    // dry-run only reads existing ledger state, never mutates
    assertTrue(fake.requests.size() >= requestsAfterDeploy);
    assertTrue(
        fake.requests.stream().skip(requestsAfterDeploy).allMatch(r -> r.method().equals("GET")));
    assertTrue(fake.hasWorkload("Deployment", "greeter-consumer"));
  }
}
