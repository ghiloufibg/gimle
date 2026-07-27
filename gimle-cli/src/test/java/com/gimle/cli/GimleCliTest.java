package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.api.ApiServer;
import com.gimle.controlplane.store.StateStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link GimleCli} against a real {@link ApiServer}, the same "real server, not mocked"
 * convention {@code ApiServerTest} establishes.
 */
class GimleCliTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private ApiServer server;
  private String serverAddress;
  private ByteArrayOutputStream outBuffer;
  private ByteArrayOutputStream errBuffer;
  private PrintStream out;
  private PrintStream err;

  @BeforeEach
  void startServer() throws IOException {
    StateStore store = new StateStore(tempDir.resolve("store"));
    server = new ApiServer(store, 0);
    server.start();
    serverAddress = "localhost:" + server.port();
    outBuffer = new ByteArrayOutputStream();
    errBuffer = new ByteArrayOutputStream();
    out = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);
    err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8);
  }

  @AfterEach
  void stopServer() {
    server.close();
  }

  private int run(String... args) {
    String[] withServer = new String[args.length + 2];
    System.arraycopy(args, 0, withServer, 0, args.length);
    withServer[args.length] = "--server";
    withServer[args.length + 1] = serverAddress;
    return GimleCli.run(withServer, out, err);
  }

  private String stdout() {
    return outBuffer.toString(StandardCharsets.UTF_8);
  }

  private String stderr() {
    return errBuffer.toString(StandardCharsets.UTF_8);
  }

  private Path writeManifest(String name, int replicas) throws IOException {
    Path file = tempDir.resolve(name + ".yaml");
    java.nio.file.Files.writeString(
        file,
        """
        name: %s
        module:
          name: com.gimle.example.orders
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
        replicas: %d
        """
            .formatted(name, replicas));
    return file;
  }

  @Test
  void apply_then_get_deployments_round_trips() throws Exception {
    Path manifest = writeManifest("orders-service", 3);

    int applyExit = run("apply", "-f", manifest.toString());
    assertEquals(0, applyExit);
    assertTrue(stdout().contains("deployment/orders-service applied"));

    outBuffer.reset();
    int getExit = run("get", "deployments");
    assertEquals(0, getExit);
    assertTrue(stdout().contains("orders-service"));
  }

  @Test
  void apply_then_delete_removes_the_deployment() throws Exception {
    Path manifest = writeManifest("catalog-service", 1);
    run("apply", "-f", manifest.toString());

    int deleteExit = run("delete", "deployment", "catalog-service");
    assertEquals(0, deleteExit);

    int getAfterDeleteExit = run("get", "deployment", "catalog-service");
    assertEquals(1, getAfterDeleteExit);
    assertTrue(stderr().contains("not found"));
  }

  @Test
  void set_tenant_then_get_tenants_round_trips() throws Exception {
    int setExit =
        run(
            "set",
            "tenant",
            "acme",
            "--max-memory-bytes",
            "1000000000",
            "--max-cpu-millicores",
            "4000",
            "--max-instances",
            "10");
    assertEquals(0, setExit);

    outBuffer.reset();
    int getExit = run("get", "tenants");
    assertEquals(0, getExit);
    assertTrue(stdout().contains("acme"));

    outBuffer.reset();
    int getSingleExit = run("-o", "json", "get", "tenant", "acme");
    assertEquals(0, getSingleExit);
    assertTrue(stdout().contains("\"id\":\"acme\""));
    assertTrue(stdout().contains("\"maxInstances\":10"));
  }

  @Test
  void set_and_get_config_round_trips() throws Exception {
    run(
        "set",
        "tenant",
        "acme",
        "--max-memory-bytes",
        "1",
        "--max-cpu-millicores",
        "1",
        "--max-instances",
        "1");

    int setConfigExit = run("set", "config", "acme", "greeting", "hello");
    assertEquals(0, setConfigExit);

    outBuffer.reset();
    int listExit = run("get", "config", "acme");
    assertEquals(0, listExit);
    assertTrue(stdout().contains("hello"));

    int deleteExit = run("delete", "config", "acme", "greeting");
    assertEquals(0, deleteExit);
  }

  @Test
  void a_404_produces_a_clear_error_and_nonzero_exit() {
    int exit = run("get", "deployment", "does-not-exist");
    assertEquals(1, exit);
    assertTrue(stderr().contains("not found"));
  }

  @Test
  void missing_server_configuration_is_a_clear_error() {
    int exit = GimleCli.run(new String[] {"get", "tenants"}, out, err);
    assertEquals(1, exit);
    assertTrue(stderr().contains("no control-plane server configured"));
  }

  @Test
  void unknown_verb_prints_usage_and_nonzero_exit() {
    int exit = run("frobnicate");
    assertEquals(1, exit);
    assertTrue(stderr().contains("usage:"));
  }
}
