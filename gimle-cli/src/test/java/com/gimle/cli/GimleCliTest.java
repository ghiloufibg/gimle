package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.api.ApiServer;
import com.gimle.controlplane.store.StateStore;
import com.gimle.core.protocol.Json;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
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

  /**
   * Registers a node directly against the real {@link ApiServer}, bypassing the CLI: {@code
   * gimle-cli} has no {@code register node} verb of its own (that's the agent's job), so this is
   * the same "real server, not mocked" setup {@code ApiServerTest} uses for its own node tests.
   */
  private void registerNode(String nodeId) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    client.send(
        HttpRequest.newBuilder(
                URI.create("http://" + serverAddress + "/nodes/" + nodeId + "/register"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"capabilities\":{\"supportedTiers\":[\"TIER_1\"]}}"))
            .build(),
        java.net.http.HttpResponse.BodyHandlers.discarding());
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
  void get_nodes_lists_a_registered_node() throws Exception {
    registerNode("node-a");

    int exit = run("get", "nodes");
    assertEquals(0, exit);
    assertTrue(stdout().contains("node-a"));
  }

  @Test
  void get_nodes_as_json_includes_the_node_id_field() throws Exception {
    registerNode("node-b");

    int exit = run("-o", "json", "get", "nodes");
    assertEquals(0, exit);
    assertTrue(stdout().contains("\"nodeId\":\"node-b\""));
  }

  @Test
  void apply_then_get_deployments_as_json_round_trips() throws Exception {
    Path manifest = writeManifest("billing-service", 2);
    run("apply", "-f", manifest.toString());

    outBuffer.reset();
    int exit = run("-o", "json", "get", "deployments");
    assertEquals(0, exit);
    assertTrue(stdout().contains("\"name\":\"billing-service\""));
  }

  @Test
  void a_404_produces_a_clear_error_and_nonzero_exit() {
    int exit = run("get", "deployment", "does-not-exist");
    assertEquals(1, exit);
    assertTrue(stderr().contains("not found"));
  }

  @Test
  void a_bare_invocation_with_no_verb_prints_usage_rather_than_a_server_configuration_error() {
    int exit = GimleCli.run(new String[0], out, err);
    assertEquals(1, exit);
    assertTrue(stderr().contains("usage:"));
    assertFalse(stderr().contains("no control-plane server configured"));
  }

  @Test
  void missing_server_configuration_is_a_clear_error() {
    int exit = GimleCli.run(new String[] {"get", "tenants"}, out, err);
    assertEquals(1, exit);
    assertTrue(stderr().contains("no control-plane server configured"));
  }

  @Test
  void set_role_then_get_roles_round_trips_then_delete() throws Exception {
    int setExit =
        run(
            "set",
            "role",
            "deployment-reader",
            "--permission",
            "deployment:read",
            "--permission",
            "config:write:acme");
    assertEquals(0, setExit);

    outBuffer.reset();
    int getExit = run("get", "roles");
    assertEquals(0, getExit);
    assertTrue(stdout().contains("deployment-reader"));

    outBuffer.reset();
    int getSingleExit = run("-o", "json", "get", "role", "deployment-reader");
    assertEquals(0, getSingleExit);
    assertTrue(stdout().contains("\"resource\":\"DEPLOYMENT\""));
    assertTrue(stdout().contains("\"verb\":\"READ\""));
    assertTrue(stdout().contains("\"tenantScope\":\"acme\""));

    int deleteExit = run("delete", "role", "deployment-reader");
    assertEquals(0, deleteExit);
    outBuffer.reset();
    int getAfterDeleteExit = run("get", "role", "deployment-reader");
    assertEquals(1, getAfterDeleteExit);
  }

  @Test
  void set_rolebinding_then_get_rolebindings_round_trips_then_delete() throws Exception {
    int setExit =
        run("set", "rolebinding", "b1", "--subject", "user:alice", "--role", "cluster-admin");
    assertEquals(0, setExit);

    outBuffer.reset();
    int getExit = run("get", "rolebindings");
    assertEquals(0, getExit);
    assertTrue(stdout().contains("user:alice"));

    outBuffer.reset();
    int getSingleExit = run("-o", "json", "get", "rolebinding", "b1");
    assertEquals(0, getSingleExit);
    assertTrue(stdout().contains("\"subject\":\"user:alice\""));
    assertTrue(stdout().contains("\"roleName\":\"cluster-admin\""));

    int deleteExit = run("delete", "rolebinding", "b1");
    assertEquals(0, deleteExit);
  }

  @Test
  void set_account_then_get_accounts_round_trips_and_never_leaks_the_password_hash()
      throws Exception {
    int setExit = run("set", "account", "admin", "--password", "s3cret-password");
    assertEquals(0, setExit);

    outBuffer.reset();
    int getExit = run("-o", "json", "get", "accounts");
    assertEquals(0, getExit);
    assertTrue(stdout().contains("\"username\":\"admin\""));
    assertFalse(stdout().contains("passwordHash"));
    assertFalse(stdout().contains("s3cret-password"));

    int deleteExit = run("delete", "account", "admin");
    assertEquals(0, deleteExit);
  }

  @Test
  void unknown_verb_prints_usage_and_nonzero_exit() {
    int exit = run("frobnicate");
    assertEquals(1, exit);
    assertTrue(stderr().contains("usage:"));
  }

  @Test
  void an_unreachable_control_plane_produces_a_clear_error_and_nonzero_exit() {
    int exit = GimleCli.run(new String[] {"get", "tenants", "--server", "localhost:1"}, out, err);
    assertEquals(1, exit);
    assertTrue(stderr().contains("could not reach control plane"));
  }

  @Test
  void a_malformed_server_response_produces_a_clear_error_not_a_stack_trace() throws Exception {
    try (ServerSocket serverSocket = new ServerSocket(0)) {
      Thread.ofVirtual().start(() -> serveOneGarbageResponse(serverSocket));

      int exit =
          GimleCli.run(
              new String[] {
                "get", "tenants", "--server", "localhost:" + serverSocket.getLocalPort()
              },
              out,
              err);
      assertEquals(1, exit);
      assertTrue(stderr().contains("unexpected response from control plane"));
    }
  }

  /**
   * A minimal hand-rolled HTTP/1.1 responder over a raw socket -- avoids requiring {@code
   * jdk.httpserver} in {@code gimle-cli}'s production module descriptor purely for one test's sake.
   */
  private static void serveOneGarbageResponse(ServerSocket serverSocket) {
    try (Socket socket = serverSocket.accept()) {
      BufferedReader in =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      String line;
      while ((line = in.readLine()) != null && !line.isEmpty()) {
        // drain the request line and headers; the body this test sends back doesn't depend on them
      }
      byte[] body = "not json {{{".getBytes(StandardCharsets.UTF_8);
      String response =
          "HTTP/1.1 200 OK\r\n"
              + "Content-Type: application/json\r\n"
              + "Content-Length: "
              + body.length
              + "\r\n"
              + "Connection: close\r\n\r\n";
      socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
      socket.getOutputStream().write(body);
      socket.getOutputStream().flush();
    } catch (IOException e) {
      // best-effort; the client-side assertions in the test are what actually matter
    }
  }

  @Test
  void set_and_delete_tenant_produce_real_json_under_json_output_format() throws Exception {
    int setExit =
        run(
            "-o",
            "json",
            "set",
            "tenant",
            "acme",
            "--max-memory-bytes",
            "1",
            "--max-cpu-millicores",
            "1",
            "--max-instances",
            "1");
    assertEquals(0, setExit);
    Map<String, Object> setResult = Json.asObject(Json.parse(stdout()));
    assertEquals("configured", setResult.get("result"));
    assertEquals("tenant", setResult.get("kind"));
    assertEquals("acme", setResult.get("id"));

    outBuffer.reset();
    int deleteExit = run("-o", "json", "delete", "tenant", "acme");
    assertEquals(0, deleteExit);
    Map<String, Object> deleteResult = Json.asObject(Json.parse(stdout()));
    assertEquals("deleted", deleteResult.get("result"));
    assertEquals("tenant", deleteResult.get("kind"));
    assertEquals("acme", deleteResult.get("id"));
  }

  @Test
  void set_and_delete_config_produce_real_json_under_json_output_format() throws Exception {
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

    outBuffer.reset();
    int setExit = run("-o", "json", "set", "config", "acme", "greeting", "hello");
    assertEquals(0, setExit);
    Map<String, Object> setResult = Json.asObject(Json.parse(stdout()));
    assertEquals("set", setResult.get("result"));
    assertEquals("config", setResult.get("kind"));
    assertEquals("acme", setResult.get("tenantId"));
    assertEquals("greeting", setResult.get("key"));

    outBuffer.reset();
    int deleteExit = run("-o", "json", "delete", "config", "acme", "greeting");
    assertEquals(0, deleteExit);
    Map<String, Object> deleteResult = Json.asObject(Json.parse(stdout()));
    assertEquals("deleted", deleteResult.get("result"));
    assertEquals("config", deleteResult.get("kind"));
    assertEquals("acme", deleteResult.get("tenantId"));
    assertEquals("greeting", deleteResult.get("key"));
  }

  @Test
  void apply_and_delete_deployment_produce_real_json_under_json_output_format() throws Exception {
    Path manifest = writeManifest("json-delete-service", 1);

    int applyExit = run("-o", "json", "apply", "-f", manifest.toString());
    assertEquals(0, applyExit);
    Map<String, Object> applyResult = Json.asObject(Json.parse(stdout()));
    assertEquals("applied", applyResult.get("result"));
    assertEquals("deployment", applyResult.get("kind"));
    assertEquals("json-delete-service", applyResult.get("id"));

    outBuffer.reset();
    int deleteExit = run("-o", "json", "delete", "deployment", "json-delete-service");
    assertEquals(0, deleteExit);
    Map<String, Object> deleteResult = Json.asObject(Json.parse(stdout()));
    assertEquals("deleted", deleteResult.get("result"));
    assertEquals("deployment", deleteResult.get("kind"));
    assertEquals("json-delete-service", deleteResult.get("id"));
  }

  @Test
  void write_verbs_still_print_the_plain_sentence_under_the_default_table_format()
      throws Exception {
    int setExit =
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
    assertEquals(0, setExit);
    assertTrue(stdout().contains("tenant/acme configured"));

    outBuffer.reset();
    int deleteExit = run("delete", "tenant", "acme");
    assertEquals(0, deleteExit);
    assertTrue(stdout().contains("tenant/acme deleted"));
  }
}
