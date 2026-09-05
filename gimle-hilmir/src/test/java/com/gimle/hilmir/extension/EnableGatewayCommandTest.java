package com.gimle.hilmir.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.analyze.testsupport.HilmirTestJarBuilder;
import com.gimle.hilmir.release.FakeControlPlane;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnableGatewayCommandTest {

  @TempDir Path tempDir;
  private FakeControlPlane fake;

  @AfterEach
  void tearDown() {
    if (fake != null) {
      fake.close();
    }
  }

  private static PrintStream capture(ByteArrayOutputStream buffer) {
    return new PrintStream(buffer, true, StandardCharsets.UTF_8);
  }

  private Path modulesDirWithGatewayJar(String version) {
    Path modulesDir = tempDir.resolve("modules-" + version);
    HilmirTestJarBuilder.create()
        .withDescriptor(HilmirTestJarBuilder.minimalDescriptor("com.gimle.gateway", version))
        .build(modulesDir, "gimle-gateway-" + version + ".jar");
    return modulesDir;
  }

  @Test
  void fresh_enable_pushes_the_jar_and_deploys_revision_one() throws Exception {
    fake = new FakeControlPlane();
    Path modulesDir = modulesDirWithGatewayJar("1.0.0");
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    int exitCode =
        EnableGatewayCommand.run(
            List.of("--server", fake.address(), "--modules-dir", modulesDir.toString()),
            capture(out));

    assertEquals(0, exitCode);
    assertTrue(fake.hasArtifact("com.gimle.gateway", "1.0.0"));
    assertTrue(fake.hasWorkload("DaemonSet", "gimle-gateway"));
    assertEquals("8090", fake.configValue("gimle-system", "gateway.port"));
    assertEquals(
        "127.0.0.1:8080", fake.configValue("gimle-system", "gateway.controlPlaneEndpoint"));
    assertTrue(fake.configValue("gimle-hilmir", "hilmir.release.gimle-gateway.meta") != null);
    assertTrue(fake.configValue("gimle-hilmir", "hilmir.release.gimle-gateway.rev.1") != null);

    boolean pushed =
        fake.requests.stream()
            .anyMatch(
                r ->
                    r.method().equals("PUT")
                        && r.path().equals("/artifacts/com.gimle.gateway/1.0.0"));
    assertTrue(pushed);
  }

  @Test
  void skips_the_push_when_the_registry_already_has_the_identical_jar() throws Exception {
    fake = new FakeControlPlane();
    Path modulesDir = modulesDirWithGatewayJar("1.0.0");
    Path jar = GatewayJarLocator.findGatewayJar(modulesDir);
    fake.seedArtifact("com.gimle.gateway", "1.0.0", Files.readAllBytes(jar));

    EnableGatewayCommand.run(
        List.of("--server", fake.address(), "--modules-dir", modulesDir.toString()),
        capture(new ByteArrayOutputStream()));

    boolean pushed =
        fake.requests.stream()
            .anyMatch(
                r ->
                    r.method().equals("PUT")
                        && r.path().equals("/artifacts/com.gimle.gateway/1.0.0"));
    assertFalse(pushed);
  }

  @Test
  void re_enabling_after_a_version_bump_upgrades_instead_of_deploying_again() throws Exception {
    fake = new FakeControlPlane();
    EnableGatewayCommand.run(
        List.of(
            "--server", fake.address(),
            "--modules-dir", modulesDirWithGatewayJar("1.0.0").toString()),
        capture(new ByteArrayOutputStream()));
    assertTrue(fake.configValue("gimle-hilmir", "hilmir.release.gimle-gateway.rev.1") != null);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int exitCode =
        EnableGatewayCommand.run(
            List.of(
                "--server", fake.address(),
                "--modules-dir", modulesDirWithGatewayJar("1.1.0").toString()),
            capture(out));

    assertEquals(0, exitCode);
    assertTrue(fake.hasArtifact("com.gimle.gateway", "1.1.0"));
    assertTrue(fake.configValue("gimle-hilmir", "hilmir.release.gimle-gateway.rev.2") != null);
    assertTrue(out.toString(StandardCharsets.UTF_8).contains("upgraded"));
  }

  @Test
  void set_flags_named_after_the_real_config_keys_override_the_bundles_own_defaults()
      throws Exception {
    // The values a `--set` flag targets here are named identically to the config keys
    // GatewayHooks actually reads ("gateway.port"/"gateway.controlPlaneEndpoint"), the names an
    // operator
    // reading this command's own usage text or gimle-system's stored config would know -- not a
    // shorter internal alias that appears nowhere an operator would ever see it.
    fake = new FakeControlPlane();
    Path modulesDir = modulesDirWithGatewayJar("1.0.0");

    EnableGatewayCommand.run(
        List.of(
            "--server",
            fake.address(),
            "--modules-dir",
            modulesDir.toString(),
            "--set",
            "gateway.port=9100",
            "--set",
            "gateway.controlPlaneEndpoint=10.0.0.5:8080"),
        capture(new ByteArrayOutputStream()));

    assertEquals("9100", fake.configValue("gimle-system", "gateway.port"));
    assertEquals("10.0.0.5:8080", fake.configValue("gimle-system", "gateway.controlPlaneEndpoint"));
  }

  @Test
  void dry_run_makes_no_mutating_control_plane_call() throws Exception {
    fake = new FakeControlPlane();
    Path modulesDir = modulesDirWithGatewayJar("1.0.0");
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    int exitCode =
        EnableGatewayCommand.run(
            List.of(
                "--server", fake.address(), "--modules-dir", modulesDir.toString(), "--dry-run"),
            capture(out));

    assertEquals(0, exitCode);
    assertFalse(fake.hasArtifact("com.gimle.gateway", "1.0.0"));
    assertFalse(fake.hasWorkload("DaemonSet", "gimle-gateway"));
    assertTrue(
        fake.requests.stream()
            .noneMatch(r -> r.method().equals("PUT") || r.method().equals("DELETE")));
  }
}
