package com.gimle.hilmir.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.protocol.Json;
import com.gimle.hilmir.HilmirException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeployCommandTest {

  @TempDir Path tempDir;
  private FakeControlPlane fake;

  @AfterEach
  void tearDown() {
    if (fake != null) {
      fake.close();
    }
  }

  private static final String BUNDLE =
      """
      kind: Bundle
      name: greeter-suite
      version: 1.0.0
      values:
        apiToken: ""
      tenants:
        - id: acme
          quota: {maxMemoryBytes: 268435456, maxCpuMillicores: 1000, maxInstances: 10}
      config:
        - {tenant: acme, key: greeting.prefix, value: "Hello"}
      secrets:
        - {tenant: acme, key: api.token, value: "${values.apiToken}"}
      workloads:
        - manifest: |
            kind: Deployment
            name: greeter-provider
      """;

  private Path writeBundle() throws IOException {
    Path file = tempDir.resolve("bundle.yaml");
    Files.writeString(file, BUNDLE, StandardCharsets.UTF_8);
    return file;
  }

  private static PrintStream capture(ByteArrayOutputStream buffer) {
    return new PrintStream(buffer, true, StandardCharsets.UTF_8);
  }

  @Test
  void deploys_every_declared_resource_and_writes_revision_one() throws Exception {
    fake = new FakeControlPlane();
    Path bundleFile = writeBundle();
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    int exitCode =
        DeployCommand.run(
            List.of(
                "-f", bundleFile.toString(),
                "--server", fake.address(),
                "--set", "apiToken=secret123"),
            capture(out));

    assertEquals(0, exitCode);
    assertTrue(fake.hasTenant("acme"));
    assertEquals("Hello", fake.configValue("acme", "greeting.prefix"));
    assertTrue(fake.hasWorkload("Deployment", "greeter-provider"));

    FakeControlPlane.Recorded secretPut =
        fake.requests.stream()
            .filter(r -> r.method().equals("PUT") && r.path().equals("/secrets/acme/api.token"))
            .findFirst()
            .orElseThrow();
    String base64Value = (String) Json.asObject(Json.parse(secretPut.body())).get("value");
    assertEquals(
        "secret123", new String(Base64.getDecoder().decode(base64Value), StandardCharsets.UTF_8));

    // the ledger itself
    assertTrue(fake.configValue("gimle-hilmir", "hilmir.release.greeter-suite.meta") != null);
    assertTrue(fake.configValue("gimle-hilmir", "hilmir.release.greeter-suite.rev.1") != null);

    // apply order: tenant before config/secret before workload
    List<String> orderedPaths =
        fake.requests.stream().map(FakeControlPlane.Recorded::path).toList();
    assertTrue(
        orderedPaths.indexOf("/tenants/acme")
            < orderedPaths.indexOf("/config/acme/greeting.prefix"));
    assertTrue(
        orderedPaths.indexOf("/config/acme/greeting.prefix")
            < orderedPaths.indexOf("/deployments/greeter-provider"));
  }

  @Test
  void refuses_to_deploy_over_an_existing_release_of_the_same_name() throws Exception {
    fake = new FakeControlPlane();
    Path bundleFile = writeBundle();
    List<String> args =
        List.of(
            "-f", bundleFile.toString(),
            "--server", fake.address(),
            "--set", "apiToken=secret123");
    DeployCommand.run(args, capture(new ByteArrayOutputStream()));

    HilmirException e =
        assertThrows(
            HilmirException.class,
            () -> DeployCommand.run(args, capture(new ByteArrayOutputStream())));
    assertTrue(e.getMessage().contains("already exists"));
  }

  @Test
  void a_second_deploy_does_not_repeat_the_hilmir_bookkeeping_tenant_bootstrap() throws Exception {
    // M37: the release ledger's own gimle-hilmir tenant bootstrap runs on every release verb (see
    // ReleaseReconciler.deployFresh) -- a real control plane's plaintext single-real-tenant rule
    // refuses re-creating a tenant that isn't there yet once a second real tenant exists, so this
    // bootstrap must check for that tenant rather than unconditionally re-PUT-ing it every time.
    fake = new FakeControlPlane();
    DeployCommand.run(
        List.of(
            "-f", writeBundle().toString(),
            "--server", fake.address(),
            "--set", "apiToken=secret123"),
        capture(new ByteArrayOutputStream()));
    assertEquals(1, hilmirTenantPutCount());

    Path secondBundleFile = tempDir.resolve("second-bundle.yaml");
    Files.writeString(
        secondBundleFile,
        BUNDLE.replace("greeter-suite", "second-suite").replace("greeter-provider", "second-app"),
        StandardCharsets.UTF_8);
    DeployCommand.run(
        List.of(
            "-f", secondBundleFile.toString(),
            "--server", fake.address(),
            "--set", "apiToken=secret456"),
        capture(new ByteArrayOutputStream()));

    // Still exactly one -- the second release's own ensureTenant call found gimle-hilmir already
    // there and skipped the write, rather than re-issuing it.
    assertEquals(1, hilmirTenantPutCount());
  }

  private long hilmirTenantPutCount() {
    return fake.requests.stream()
        .filter(r -> r.method().equals("PUT") && r.path().equals("/tenants/gimle-hilmir"))
        .count();
  }

  @Test
  void dry_run_renders_the_plan_and_makes_zero_control_plane_calls() throws Exception {
    fake = new FakeControlPlane();
    Path bundleFile = writeBundle();
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    int exitCode =
        DeployCommand.run(
            List.of(
                "-f",
                bundleFile.toString(),
                "--server",
                fake.address(),
                "--set",
                "apiToken=secret123",
                "--dry-run"),
            capture(out));

    assertEquals(0, exitCode);
    assertTrue(fake.requests.isEmpty());
    assertTrue(out.toString(StandardCharsets.UTF_8).contains("greeter-suite"));
  }

  @Test
  void an_unresolved_value_reference_fails_before_any_http_call() throws Exception {
    fake = new FakeControlPlane();
    // No `values:` default for apiToken and no --set/--values override -- the reference genuinely
    // cannot resolve, unlike writeBundle()'s own bundle, which declares an empty-string default.
    Path bundleFile = tempDir.resolve("no-default.yaml");
    Files.writeString(
        bundleFile,
        """
        kind: Bundle
        name: greeter-suite
        version: 1.0.0
        secrets:
          - {tenant: acme, key: api.token, value: "${values.apiToken}"}
        """,
        StandardCharsets.UTF_8);

    assertThrows(
        GimleManifestException.class,
        () ->
            DeployCommand.run(
                List.of("-f", bundleFile.toString(), "--server", fake.address()),
                capture(new ByteArrayOutputStream())));
    assertTrue(fake.requests.isEmpty());
  }

  @Test
  void json_output_reports_the_release_name_and_revision() throws Exception {
    fake = new FakeControlPlane();
    Path bundleFile = writeBundle();
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    DeployCommand.run(
        List.of(
            "-f",
            bundleFile.toString(),
            "--server",
            fake.address(),
            "--set",
            "apiToken=secret123",
            "-o",
            "json"),
        capture(out));

    Map<String, Object> body =
        Json.asObject(Json.parse(out.toString(StandardCharsets.UTF_8).trim()));
    assertEquals("greeter-suite", body.get("release"));
    assertEquals(1L, ((Number) body.get("revision")).longValue());
  }

  @Test
  void wait_polls_until_the_workloads_instances_report_active() throws Exception {
    fake = new FakeControlPlane();
    Path bundleFile = writeBundle();
    fake.scheduleReadyAfterPolls("Deployment", "greeter-provider", 2, List.of(activeInstance()));
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    int exitCode =
        DeployCommand.run(
            List.of(
                "-f",
                bundleFile.toString(),
                "--server",
                fake.address(),
                "--set",
                "apiToken=secret123",
                "--wait"),
            capture(out));

    assertEquals(0, exitCode);
    assertTrue(out.toString(StandardCharsets.UTF_8).contains("ready"));
  }

  private static Map<String, Object> activeInstance() {
    return Map.of("observation", Map.of("lifecycleState", "ACTIVE"));
  }
}
