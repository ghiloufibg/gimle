package com.gimle.hilmir.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.analyze.testsupport.HilmirTestJarBuilder;
import com.gimle.hilmir.release.FakeControlPlane;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DisableGatewayCommandTest {

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

  private Path modulesDirWithGatewayJar() {
    Path modulesDir = tempDir.resolve("modules");
    HilmirTestJarBuilder.create()
        .withDescriptor(HilmirTestJarBuilder.minimalDescriptor("com.gimle.gateway", "1.0.0"))
        .build(modulesDir, "gimle-gateway-1.0.0.jar");
    return modulesDir;
  }

  @Test
  void disable_undeploys_the_gimle_gateway_release() throws Exception {
    fake = new FakeControlPlane();
    EnableGatewayCommand.run(
        List.of(
            "--server",
            fake.address(),
            "--modules-dir",
            modulesDirWithGatewayJar().toString(),
            "--set",
            "gateway.controlPlaneEndpoint=10.0.0.5:8080"),
        capture(new ByteArrayOutputStream()));
    assertTrue(fake.hasWorkload("DaemonSet", "gimle-gateway"));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int exitCode = DisableGatewayCommand.run(List.of("--server", fake.address()), capture(out));

    assertEquals(0, exitCode);
    assertFalse(fake.hasWorkload("DaemonSet", "gimle-gateway"));
    assertTrue(fake.configValue("gimle-hilmir", "hilmir.release.gimle-gateway.meta") == null);
  }

  @Test
  void disable_fails_clearly_when_gateway_was_never_enabled() throws Exception {
    fake = new FakeControlPlane();

    HilmirException e =
        assertThrows(
            HilmirException.class,
            () ->
                DisableGatewayCommand.run(
                    List.of("--server", fake.address()), capture(new ByteArrayOutputStream())));
    assertTrue(e.getMessage().contains("not currently enabled"));
  }

  /**
   * A DaemonSet named {@code gimle-gateway} put there some other way (a direct {@code gimle apply}
   * bypassing hilmir's own release ledger entirely, or a ledger row separately lost) is a genuinely
   * different situation from the gateway never having been enabled at all -- both looked identical
   * before this fix, since the ledger lookup alone can't distinguish them.
   */
  @Test
  void disable_names_the_leftover_daemonset_when_no_ledger_row_exists_for_it() throws Exception {
    fake = new FakeControlPlane();
    HttpClient client = HttpClient.newHttpClient();
    client.send(
        HttpRequest.newBuilder(URI.create("http://" + fake.address() + "/daemonsets/gimle-gateway"))
            .PUT(HttpRequest.BodyPublishers.ofString("kind: DaemonSet\nname: gimle-gateway\n"))
            .build(),
        HttpResponse.BodyHandlers.discarding());
    assertTrue(fake.hasWorkload("DaemonSet", "gimle-gateway"));

    HilmirException e =
        assertThrows(
            HilmirException.class,
            () ->
                DisableGatewayCommand.run(
                    List.of("--server", fake.address()), capture(new ByteArrayOutputStream())));

    assertFalse(e.getMessage().contains("nothing to disable"), e.getMessage());
    assertTrue(e.getMessage().contains("gimle-gateway"), e.getMessage());
    assertTrue(e.getMessage().contains("gimle delete daemonset"), e.getMessage());
  }
}
