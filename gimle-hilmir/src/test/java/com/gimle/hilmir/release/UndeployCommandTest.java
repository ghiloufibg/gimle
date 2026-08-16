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

class UndeployCommandTest {

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

  private Path writeBundle() throws IOException {
    Path file = tempDir.resolve("bundle.yaml");
    Files.writeString(file, BUNDLE, StandardCharsets.UTF_8);
    return file;
  }

  private static PrintStream capture(ByteArrayOutputStream buffer) {
    return new PrintStream(buffer, true, StandardCharsets.UTF_8);
  }

  @Test
  void undeploy_deletes_workloads_in_reverse_apply_order_then_the_tenant_then_the_ledger()
      throws Exception {
    fake = new FakeControlPlane();
    DeployCommand.run(
        List.of("-f", writeBundle().toString(), "--server", fake.address()),
        capture(new ByteArrayOutputStream()));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int exitCode =
        UndeployCommand.run(
            List.of("--release", "greeter-suite", "--server", fake.address()), capture(out));

    assertEquals(0, exitCode);
    assertFalse(fake.hasWorkload("Deployment", "greeter-provider"));
    assertFalse(fake.hasWorkload("Deployment", "greeter-consumer"));
    assertFalse(fake.hasTenant("acme"));
    assertTrue(fake.configValue("gimle-hilmir", "hilmir.release.greeter-suite.meta") == null);
    assertTrue(fake.configValue("gimle-hilmir", "hilmir.release.greeter-suite.rev.1") == null);

    List<String> deleteOrder =
        fake.requests.stream()
            .filter(r -> r.method().equals("DELETE") && r.path().startsWith("/deployments/"))
            .map(FakeControlPlane.Recorded::path)
            .toList();
    // applied provider then consumer -> deleted consumer then provider
    assertEquals(
        List.of("/deployments/greeter-consumer", "/deployments/greeter-provider"), deleteOrder);
  }

  @Test
  void keep_tenants_leaves_the_tenant_in_place() throws Exception {
    fake = new FakeControlPlane();
    DeployCommand.run(
        List.of("-f", writeBundle().toString(), "--server", fake.address()),
        capture(new ByteArrayOutputStream()));

    UndeployCommand.run(
        List.of("--release", "greeter-suite", "--server", fake.address(), "--keep-tenants"),
        capture(new ByteArrayOutputStream()));

    assertTrue(fake.hasTenant("acme"));
  }

  @Test
  void undeploy_fails_cleanly_for_a_nonexistent_release() throws Exception {
    fake = new FakeControlPlane();

    HilmirException e =
        assertThrows(
            HilmirException.class,
            () ->
                UndeployCommand.run(
                    List.of("--release", "no-such-release", "--server", fake.address()),
                    capture(new ByteArrayOutputStream())));
    assertTrue(e.getMessage().contains("no release named"));
  }
}
