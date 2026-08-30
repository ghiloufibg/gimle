package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.api.ApiServer;
import com.gimle.controlplane.fafnir.FafnirClient;
import com.gimle.core.protocol.Json;
import com.gimle.fafnir.FafnirCrypto;
import com.gimle.fafnir.FafnirServer;
import com.gimle.mimir.raft.RaftLog;
import com.gimle.mimir.raft.RaftNode;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.rpc.StoreNode;
import com.gimle.mimir.rpc.StoreTransport;
import com.gimle.mimir.store.StateStore;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

  private RaftNode storeRaftNode;
  private StoreTransport storeTransport;
  private StoreClient storeClient;
  private FafnirServer fafnirServer;
  private FafnirClient fafnirClient;
  private ApiServer server;
  private String serverAddress;
  private ByteArrayOutputStream outBuffer;
  private ByteArrayOutputStream errBuffer;
  private PrintStream out;
  private PrintStream err;

  @BeforeEach
  void startServer() throws IOException {
    // A single-node gimle-mimir store, in-process, backing this test's ApiServer over a real
    // loopback socket -- ApiServer no longer holds a StateStore directly, so exercising it now
    // always means standing up at least this much of a store.
    StateStore store = new StateStore();
    RaftLog raftLog = new RaftLog(tempDir.resolve("raft"));
    storeRaftNode = new RaftNode("self", Map.of(), raftLog, store);
    storeRaftNode.start();
    StoreNode storeNode = new StoreNode(storeRaftNode, store, Map.of());
    storeTransport = new StoreTransport(storeNode);
    SocketAddress storeAddress = storeTransport.listen(new InetSocketAddress("127.0.0.1", 0));
    storeClient = new StoreClient(List.of(storeAddress));

    // A real, in-process Fafnir replica -- ApiServer no longer performs crypto in-process,
    // so every config/secrets round trip below now needs a genuine encrypt/decrypt/
    // versioned-store hop, not a mock.
    FafnirCrypto fafnirCrypto = new FafnirCrypto(storeClient, tempDir.resolve("keys/secret.key"));
    fafnirServer = new FafnirServer(fafnirCrypto, 0);
    fafnirServer.start();
    fafnirClient = new FafnirClient("localhost:" + fafnirServer.port());

    server = new ApiServer(storeClient, 0, fafnirClient);
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
    fafnirClient.close();
    fafnirServer.close();
    storeClient.close();
    storeTransport.close();
    storeRaftNode.close();
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
        HttpResponse.BodyHandlers.discarding());
  }

  /**
   * Sends one heartbeat directly against the real {@link ApiServer}, bypassing the CLI -- the same
   * "real server, not mocked" shortcut {@link #registerNode} uses for its own resource. A
   * registered node carries no {@code lastHeartbeatAt} at all until its first heartbeat, so a test
   * asserting on the computed freshness status needs this to get a deterministic {@code HEALTHY}
   * rather than {@code UNKNOWN}.
   */
  private void heartbeatNode(String nodeId) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    Map<String, Object> body =
        Map.of(
            "capacity",
                Map.of(
                    "totalMemoryBytes", 1_000_000_000L,
                    "assignedMemoryBytes", 0L,
                    "totalCpuMillicores", 4000L,
                    "assignedCpuMillicores", 0L),
            "instances", List.of());
    client.send(
        HttpRequest.newBuilder(
                URI.create("http://" + serverAddress + "/nodes/" + nodeId + "/heartbeat"))
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
            .build(),
        HttpResponse.BodyHandlers.discarding());
  }

  /**
   * Appends one instance event directly against the real {@link ApiServer}, bypassing the CLI --
   * the same "real server, not mocked" shortcut {@link #registerNode} uses for its own resource.
   */
  private void appendInstanceEvent(
      String deploymentName, int instanceIndex, String id, long occurredAtEpochMilli)
      throws Exception {
    Map<String, Object> body =
        Map.of(
            "id", id,
            "deploymentName", deploymentName,
            "instanceIndex", instanceIndex,
            "kind", "ACTIVE",
            "message", "instance became active",
            "occurredAtEpochMilli", occurredAtEpochMilli);
    HttpClient client = HttpClient.newHttpClient();
    client.send(
        HttpRequest.newBuilder(URI.create("http://" + serverAddress + "/nodes/node-events/events"))
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
            .build(),
        HttpResponse.BodyHandlers.discarding());
  }

  private Path writeManifest(String name, int replicas) throws IOException {
    return writeManifest(name, "1.0.0", replicas);
  }

  private Path writeManifest(String name, String version, int replicas) throws IOException {
    Path file = tempDir.resolve(name + ".yaml");
    Files.writeString(
        file,
        """
        kind: Deployment
        name: %s
        module:
          name: com.gimle.example.orders
          version: %s
        artifactPath: /var/gimle/artifacts/orders-%s.jar
        replicas: %d
        """
            .formatted(name, version, version, replicas));
    return file;
  }

  private Path writeJobManifest(String name) throws IOException {
    Path file = tempDir.resolve(name + ".yaml");
    java.nio.file.Files.writeString(
        file,
        """
        kind: Job
        name: %s
        module:
          name: com.gimle.example.cleanup
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
        backoffLimit: 3
        """
            .formatted(name));
    return file;
  }

  private Path writeCronJobManifest(String name) throws IOException {
    Path file = tempDir.resolve(name + ".yaml");
    java.nio.file.Files.writeString(
        file,
        """
        kind: CronJob
        name: %s
        schedule: "0 0 1 1 *"
        jobTemplate:
          module:
            name: com.gimle.example.cleanup
            version: 1.0.0
          artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
        """
            .formatted(name));
    return file;
  }

  private Path writeDaemonSetManifest(String name) throws IOException {
    Path file = tempDir.resolve(name + ".yaml");
    java.nio.file.Files.writeString(
        file,
        """
        kind: DaemonSet
        name: %s
        module:
          name: com.gimle.example.node-exporter
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/node-exporter-1.0.0.jar
        """
            .formatted(name));
    return file;
  }

  private Path writeStatefulSetManifest(String name, int replicas) throws IOException {
    Path file = tempDir.resolve(name + ".yaml");
    java.nio.file.Files.writeString(
        file,
        """
        kind: StatefulSet
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
  void set_limitrange_then_get_limitranges_round_trips() throws Exception {
    int setExit =
        run(
            "set",
            "limitrange",
            "acme",
            "--min-request-memory",
            "64Mi",
            "--min-request-cpu",
            "50m",
            "--max-limit-memory",
            "512Mi",
            "--max-limit-cpu",
            "500m");
    assertEquals(0, setExit);

    outBuffer.reset();
    int getExit = run("get", "limitranges");
    assertEquals(0, getExit);
    assertTrue(stdout().contains("acme"));

    outBuffer.reset();
    int getSingleExit = run("-o", "json", "get", "limitrange", "acme");
    assertEquals(0, getSingleExit);
    assertTrue(stdout().contains("\"tenantId\":\"acme\""));
    assertTrue(stdout().contains("\"memory\":\"64Mi\""));
    assertTrue(stdout().contains("\"memory\":\"512Mi\""));

    outBuffer.reset();
    int deleteExit = run("delete", "limitrange", "acme");
    assertEquals(0, deleteExit);

    outBuffer.reset();
    int getAfterDeleteExit = run("get", "limitrange", "acme");
    assertEquals(1, getAfterDeleteExit);
    assertTrue(stderr().contains("not found"));
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

  /**
   * Creates a tenant with permissive limits, via the CLI itself, purely as setup: Fafnir now
   * rejects a secret write against a tenant id that doesn't exist yet (see {@code gimle-fafnir}'s
   * own {@code SecretsCommandTest}), so every secret test below needs one to exist first, the same
   * way a real operator would run {@code gimle set tenant} before {@code gimle secret set}.
   */
  private void createTenant(String tenantId) {
    run(
        "set",
        "tenant",
        tenantId,
        "--max-memory-bytes",
        "1000000000",
        "--max-cpu-millicores",
        "4000",
        "--max-instances",
        "10");
  }

  @Test
  void secret_set_then_get_round_trips_the_plaintext_value() throws Exception {
    createTenant("acme");

    int setExit = run("secret", "set", "acme", "db-password", "--value", "hunter2");
    assertEquals(0, setExit, stderr());
    assertTrue(stdout().contains("secrets/acme/db-password set (version 1)"));

    outBuffer.reset();
    int getExit = run("secret", "get", "acme", "db-password");
    assertEquals(0, getExit, stderr());
    assertTrue(stdout().contains("hunter2"));
  }

  @Test
  void secret_list_shows_the_key_without_ever_printing_a_value() throws Exception {
    createTenant("acme");
    run("secret", "set", "acme", "db-password", "--value", "hunter2");

    outBuffer.reset();
    int listExit = run("secret", "list", "acme");
    assertEquals(0, listExit, stderr());
    assertTrue(stdout().contains("db-password"));
    assertFalse(stdout().contains("hunter2"));
  }

  @Test
  void secret_versions_lists_every_claimed_version_after_two_writes() throws Exception {
    createTenant("acme");
    run("secret", "set", "acme", "db-password", "--value", "v1");
    run("secret", "set", "acme", "db-password", "--value", "v2");

    outBuffer.reset();
    int versionsExit = run("secret", "versions", "acme", "db-password");
    assertEquals(0, versionsExit, stderr());
    assertTrue(stdout().contains("1"));
    assertTrue(stdout().contains("2"));
  }

  @Test
  void secret_get_with_an_explicit_version_reads_the_historical_value() throws Exception {
    createTenant("acme");
    run("secret", "set", "acme", "db-password", "--value", "v1");
    run("secret", "set", "acme", "db-password", "--value", "v2");

    outBuffer.reset();
    int getExit = run("secret", "get", "acme", "db-password", "--version", "1");
    assertEquals(0, getExit, stderr());
    assertTrue(stdout().contains("v1"));
  }

  @Test
  void secret_delete_then_get_returns_not_found() throws Exception {
    createTenant("acme");
    run("secret", "set", "acme", "temp", "--value", "x");

    int deleteExit = run("secret", "delete", "acme", "temp");
    assertEquals(0, deleteExit, stderr());

    int getAfterDeleteExit = run("secret", "get", "acme", "temp");
    assertEquals(1, getAfterDeleteExit);
  }

  @Test
  void secret_rotate_key_returns_an_incrementing_active_key_id() throws Exception {
    int firstExit = run("-o", "json", "secret", "rotate-key");
    assertEquals(0, firstExit, stderr());
    Map<String, Object> first = Json.asObject(Json.parse(stdout()));
    assertEquals(1L, first.get("activeKeyId"));

    outBuffer.reset();
    int secondExit = run("-o", "json", "secret", "rotate-key");
    assertEquals(0, secondExit, stderr());
    Map<String, Object> second = Json.asObject(Json.parse(stdout()));
    assertEquals(2L, second.get("activeKeyId"));
  }

  /**
   * This suite runs {@code ApiServer} in plaintext mode (no TLS setup anywhere in {@link
   * #startServer}), so {@code requireAuthorized} short-circuits to {@code true} before ever
   * reaching the audit-emission code (see {@code ApiServer.requireAuthorized}'s own javadoc) --
   * these tests exercise the CLI command's own flag-parsing/query-string/output wiring against the
   * real {@code GET /audit} endpoint, not the write-then-record semantics themselves (already
   * covered against real mTLS by {@code ApiServerAuthzTest}/{@code FafnirSecretsAuthzTest}).
   */
  @Test
  void audit_list_with_no_filters_succeeds_and_is_empty_in_plaintext_mode() throws Exception {
    run("secret", "set", "acme", "db-password", "--value", "hunter2");

    outBuffer.reset();
    int exit = run("audit", "list");
    assertEquals(0, exit, stderr());
  }

  @Test
  void audit_list_accepts_every_filter_flag_without_a_malformed_request() throws Exception {
    int exit =
        run(
            "audit",
            "list",
            "--principal",
            "alice",
            "--resource",
            "DEPLOYMENT",
            "--tenant",
            "acme",
            "--since",
            "0",
            "--limit",
            "10");
    assertEquals(0, exit, stderr());
  }

  @Test
  void audit_command_without_the_list_verb_prints_usage_and_nonzero_exit() {
    int exit = run("audit");
    assertEquals(1, exit);
    assertTrue(stderr().contains("usage: gimle audit list"));
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

  /**
   * Regression coverage for the table/JSON parity gap: {@code get nodes -o table} has always shown
   * a computed {@code status} ({@code HEALTHY}/{@code STALE}) with no way for a JSON consumer to
   * reproduce it -- the raw API response never carried the field at all. {@code -o json} now
   * carries the identical computed value under the same key.
   */
  @Test
  void get_nodes_as_json_also_carries_the_computed_status_field() throws Exception {
    registerNode("node-status");
    heartbeatNode("node-status");

    int exit = run("-o", "json", "get", "nodes");
    assertEquals(0, exit, stderr());
    assertTrue(stdout().contains("\"status\":\"HEALTHY\""), stdout());
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
    assertEquals(0, deleteExit, errBuffer::toString);
    outBuffer.reset();
    int getAfterDeleteExit = run("get", "role", "deployment-reader");
    assertEquals(1, getAfterDeleteExit);
  }

  @Test
  void deleting_a_role_cascades_to_every_rolebinding_that_named_it() throws Exception {
    // FUNC-24 regression: roleName is a plain string resolved by name at authorize-time, not an
    // immutable ID -- a binding left behind after its Role is deleted would silently reactivate
    // the moment anyone later PUTs a new Role under the same name.
    assertEquals(
        0, run("set", "role", "reviewer", "--permission", "deployment:read"), errBuffer::toString);
    assertEquals(
        0,
        run("set", "rolebinding", "b1", "--subject", "user:alice", "--role", "reviewer"),
        errBuffer::toString);
    assertEquals(
        0,
        run("set", "rolebinding", "b2", "--subject", "group:reviewers", "--role", "reviewer"),
        errBuffer::toString);

    outBuffer.reset();
    int deleteExit = run("delete", "role", "reviewer");
    assertEquals(0, deleteExit);
    String out = stdout();
    // The operator is told which bindings were revoked, not left to discover it later.
    assertTrue(out.contains("b1"), out);
    assertTrue(out.contains("b2"), out);

    assertEquals(1, run("get", "rolebinding", "b1"));
    assertEquals(1, run("get", "rolebinding", "b2"));
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

  // ---- Job / CronJob / DaemonSet / StatefulSet -- previously entirely uncovered by this class
  // ----

  @Test
  void apply_then_get_jobs_round_trips() throws Exception {
    Path manifest = writeJobManifest("nightly-cleanup");
    int applyExit = run("apply", "-f", manifest.toString());
    assertEquals(0, applyExit);
    assertTrue(stdout().contains("job/nightly-cleanup applied"));

    outBuffer.reset();
    int getExit = run("get", "jobs");
    assertEquals(0, getExit);
    assertTrue(stdout().contains("nightly-cleanup"));
  }

  @Test
  void apply_then_delete_removes_the_job() throws Exception {
    Path manifest = writeJobManifest("one-off-job");
    run("apply", "-f", manifest.toString());
    int deleteExit = run("delete", "job", "one-off-job");
    assertEquals(0, deleteExit);
    int getAfterDeleteExit = run("get", "job", "one-off-job");
    assertEquals(1, getAfterDeleteExit);
    assertTrue(stderr().contains("not found"));
  }

  @Test
  void apply_then_get_cronjobs_round_trips() throws Exception {
    Path manifest = writeCronJobManifest("nightly-cleanup-cron");
    int applyExit = run("apply", "-f", manifest.toString());
    assertEquals(0, applyExit);
    assertTrue(stdout().contains("cronjob/nightly-cleanup-cron applied"));

    outBuffer.reset();
    int getExit = run("get", "cronjobs");
    assertEquals(0, getExit);
    assertTrue(stdout().contains("nightly-cleanup-cron"));
  }

  @Test
  void cronjob_trigger_fires_immediately_and_the_generated_job_is_real() throws Exception {
    Path manifest = writeCronJobManifest("trigger-me");
    run("apply", "-f", manifest.toString());

    outBuffer.reset();
    int triggerExit = run("cronjob", "trigger", "trigger-me");
    assertEquals(0, triggerExit);
    assertTrue(
        stdout().contains("cronjob/trigger-me triggered -> job/trigger-me-"),
        "unexpected trigger output: " + stdout());

    // Extract the real generated job name (trigger-me-<epochSeconds>) straight out of the CLI's
    // own printed sentence, then confirm it's a real, independently-fetchable Job -- not just a
    // string the trigger command happened to print. Anchored on "-> job/", not just "job/": the
    // sentence's own "cronjob/trigger-me" prefix already contains the substring "job/" (the last
    // three letters of "cronjob" plus the following slash), so a bare indexOf("job/") matches
    // there first instead of the actual generated-job segment.
    String marker = "-> job/";
    String jobName = stdout().trim().substring(stdout().indexOf(marker) + marker.length());
    outBuffer.reset();
    int getExit = run("get", "job", jobName);
    assertEquals(0, getExit);
    assertTrue(stdout().contains(jobName));
  }

  @Test
  void cronjob_trigger_on_an_unknown_cronjob_fails() throws Exception {
    int triggerExit = run("cronjob", "trigger", "no-such-cronjob");
    assertEquals(1, triggerExit);
    assertTrue(stderr().contains("no such cronjob"));
  }

  @Test
  void deployment_revisions_lists_history_newest_first() throws Exception {
    run("apply", "-f", writeManifest("orders-service", "1.0.0", 1).toString());
    run("apply", "-f", writeManifest("orders-service", "1.1.0", 1).toString());

    outBuffer.reset();
    int exit = run("-o", "json", "deployment", "revisions", "orders-service");
    assertEquals(0, exit);
    List<Map<String, Object>> revisions = Json.asObjectList(Json.parse(stdout().trim()));
    assertEquals(2, revisions.size());
    assertEquals(2L, revisions.get(0).get("revision"));
    assertEquals(1L, revisions.get(1).get("revision"));
  }

  @Test
  void deployment_rollback_with_no_flag_restores_the_previous_revision() throws Exception {
    run("apply", "-f", writeManifest("orders-service", "1.0.0", 1).toString());
    run("apply", "-f", writeManifest("orders-service", "1.1.0", 1).toString());

    outBuffer.reset();
    int rollbackExit = run("deployment", "rollback", "orders-service");
    assertEquals(0, rollbackExit);

    outBuffer.reset();
    int getExit = run("-o", "json", "get", "deployment", "orders-service");
    assertEquals(0, getExit);
    Map<String, Object> status = Json.asObject(Json.parse(stdout().trim()));
    Map<String, Object> spec = Json.asObject(status.get("spec"));
    assertEquals("1.0.0", Json.asObject(spec.get("moduleId")).get("version"));
  }

  @Test
  void deployment_rollback_to_an_explicit_revision_restores_that_one() throws Exception {
    run("apply", "-f", writeManifest("orders-service", "1.0.0", 1).toString());
    run("apply", "-f", writeManifest("orders-service", "1.1.0", 1).toString());
    run("apply", "-f", writeManifest("orders-service", "1.2.0", 1).toString());

    outBuffer.reset();
    int rollbackExit = run("deployment", "rollback", "orders-service", "--to-revision", "1");
    assertEquals(0, rollbackExit);

    outBuffer.reset();
    run("-o", "json", "get", "deployment", "orders-service");
    Map<String, Object> status = Json.asObject(Json.parse(stdout().trim()));
    Map<String, Object> spec = Json.asObject(status.get("spec"));
    assertEquals("1.0.0", Json.asObject(spec.get("moduleId")).get("version"));
  }

  @Test
  void deployment_rollback_of_an_unknown_deployment_fails() throws Exception {
    int rollbackExit = run("deployment", "rollback", "never-deployed");
    assertEquals(1, rollbackExit);
    assertTrue(stderr().contains("no revision history"));
  }

  @Test
  void apply_then_get_daemonsets_round_trips() throws Exception {
    Path manifest = writeDaemonSetManifest("node-exporter");
    int applyExit = run("apply", "-f", manifest.toString());
    assertEquals(0, applyExit);
    assertTrue(stdout().contains("daemonset/node-exporter applied"));

    outBuffer.reset();
    int getExit = run("get", "daemonsets");
    assertEquals(0, getExit);
    assertTrue(stdout().contains("node-exporter"));
  }

  @Test
  void apply_then_delete_removes_the_daemonset() throws Exception {
    Path manifest = writeDaemonSetManifest("short-lived-daemonset");
    run("apply", "-f", manifest.toString());
    int deleteExit = run("delete", "daemonset", "short-lived-daemonset");
    assertEquals(0, deleteExit);
    int getAfterDeleteExit = run("get", "daemonset", "short-lived-daemonset");
    assertEquals(1, getAfterDeleteExit);
    assertTrue(stderr().contains("not found"));
  }

  /**
   * Regression coverage for the bug a manual user-perspective pass surfaced: {@code get
   * daemonsets}' default table output used to dump each row's raw {@code spec}/{@code instances}
   * JSON per cell instead of clean columns, unlike {@code get deployments}. Both the bare list and
   * {@code get daemonset <name>} forms must render the same clean {@code name/module/artifactPath
   * /tenantId/instances/health} shape {@code DeploymentsCommand#humanize} already establishes for
   * Deployments -- no stray {@code {}}/{@code []} from a raw nested value leaking into a cell.
   */
  @Test
  void get_daemonsets_renders_clean_table_columns_instead_of_raw_json_per_cell() throws Exception {
    Path manifest = writeDaemonSetManifest("clean-columns-daemonset");
    run("apply", "-f", manifest.toString());

    outBuffer.reset();
    assertEquals(0, run("get", "daemonsets"));
    String listOut = stdout();
    assertTrue(listOut.contains("name"), listOut);
    assertTrue(listOut.contains("module"), listOut);
    assertTrue(listOut.contains("health"), listOut);
    assertTrue(listOut.contains("com.gimle.example.node-exporter@1.0.0"), listOut);
    assertTrue(listOut.contains("HEALTHY"), listOut);
    assertFalse(listOut.contains("{"), listOut);
    assertFalse(listOut.contains("["), listOut);

    outBuffer.reset();
    assertEquals(0, run("get", "daemonset", "clean-columns-daemonset"));
    String objectOut = stdout();
    assertTrue(objectOut.contains("clean-columns-daemonset"), objectOut);
    assertFalse(objectOut.contains("{"), objectOut);
    assertFalse(objectOut.contains("["), objectOut);

    // -o json keeps the raw nested shape at full fidelity -- this fix is table-output-only.
    outBuffer.reset();
    assertEquals(0, run("get", "daemonset", "clean-columns-daemonset", "-o", "json"));
    assertTrue(stdout().contains("\"spec\""), stdout());
    assertTrue(stdout().contains("\"instances\""), stdout());
  }

  @Test
  void apply_then_get_statefulsets_round_trips() throws Exception {
    Path manifest = writeStatefulSetManifest("orders-statefulset", 3);
    int applyExit = run("apply", "-f", manifest.toString());
    assertEquals(0, applyExit);
    assertTrue(stdout().contains("statefulset/orders-statefulset applied"));

    outBuffer.reset();
    int getExit = run("get", "statefulsets");
    assertEquals(0, getExit);
    assertTrue(stdout().contains("orders-statefulset"));
  }

  @Test
  void apply_then_delete_removes_the_statefulset() throws Exception {
    Path manifest = writeStatefulSetManifest("short-lived-statefulset", 1);
    run("apply", "-f", manifest.toString());
    int deleteExit = run("delete", "statefulset", "short-lived-statefulset");
    assertEquals(0, deleteExit);
    int getAfterDeleteExit = run("get", "statefulset", "short-lived-statefulset");
    assertEquals(1, getAfterDeleteExit);
    assertTrue(stderr().contains("not found"));
  }

  /**
   * Regression coverage for the same raw-JSON-per-cell bug {@code
   * get_daemonsets_renders_clean_table_columns_instead_of_raw_json_per_cell} covers, for
   * StatefulSet's own {@code spec}/{@code instances}/{@code unplacedCount} shape -- including its
   * {@code replicas} column showing the placed-vs-desired count {@code DeploymentsCommand#humanize}
   * already establishes, and {@code UNPLACED(N)} in the health column when nothing has been placed.
   */
  @Test
  void get_statefulsets_renders_clean_table_columns_instead_of_raw_json_per_cell()
      throws Exception {
    Path manifest = writeStatefulSetManifest("clean-columns-statefulset", 3);
    run("apply", "-f", manifest.toString());

    outBuffer.reset();
    assertEquals(0, run("get", "statefulsets"));
    String listOut = stdout();
    assertTrue(listOut.contains("name"), listOut);
    assertTrue(listOut.contains("replicas"), listOut);
    assertTrue(listOut.contains("com.gimle.example.orders@1.0.0"), listOut);
    assertTrue(listOut.contains("0/3"), listOut);
    assertTrue(listOut.contains("UNPLACED(3)"), listOut);
    assertFalse(listOut.contains("{"), listOut);
    assertFalse(listOut.contains("["), listOut);

    outBuffer.reset();
    assertEquals(0, run("get", "statefulset", "clean-columns-statefulset"));
    String objectOut = stdout();
    assertTrue(objectOut.contains("clean-columns-statefulset"), objectOut);
    assertFalse(objectOut.contains("{"), objectOut);
    assertFalse(objectOut.contains("["), objectOut);

    // -o json keeps the raw nested shape at full fidelity -- this fix is table-output-only.
    outBuffer.reset();
    assertEquals(0, run("get", "statefulset", "clean-columns-statefulset", "-o", "json"));
    assertTrue(stdout().contains("\"spec\""), stdout());
    assertTrue(stdout().contains("\"instances\""), stdout());
  }

  // ---- Service / NetworkPolicy -- previously entirely uncovered by this class ----

  @Test
  void set_service_then_get_services_round_trips_then_delete() throws Exception {
    int setExit =
        run(
            "set",
            "service",
            "web",
            "--deployment",
            "orders-service",
            "--port",
            "8080",
            "--target-port",
            "9090");
    assertEquals(0, setExit, stderr());
    assertTrue(stdout().contains("service/web configured"));

    outBuffer.reset();
    int getExit = run("get", "services");
    assertEquals(0, getExit);
    assertTrue(stdout().contains("web"));

    outBuffer.reset();
    int getSingleExit = run("-o", "json", "get", "service", "web");
    assertEquals(0, getSingleExit);
    assertTrue(stdout().contains("\"name\":\"web\""));
    assertTrue(stdout().contains("\"deploymentNames\":[\"orders-service\"]"));
    assertTrue(stdout().contains("\"port\":8080"));
    assertTrue(stdout().contains("\"targetPort\":9090"));

    int deleteExit = run("delete", "service", "web");
    assertEquals(0, deleteExit);
    outBuffer.reset();
    int getAfterDeleteExit = run("get", "service", "web");
    assertEquals(1, getAfterDeleteExit);
    assertTrue(stderr().contains("not found"));
  }

  @Test
  void set_service_defaults_target_port_to_port_when_omitted() throws Exception {
    run("set", "service", "solo-port", "--deployment", "orders-service", "--port", "7000");

    outBuffer.reset();
    int getExit = run("-o", "json", "get", "service", "solo-port");
    assertEquals(0, getExit);
    assertTrue(stdout().contains("\"port\":7000"));
    assertTrue(stdout().contains("\"targetPort\":7000"));
  }

  @Test
  void service_endpoints_reports_the_declared_port_shape_with_no_live_backing_instance()
      throws Exception {
    run("set", "service", "no-backing", "--deployment", "orders-service", "--port", "8080");

    outBuffer.reset();
    int exit = run("service", "endpoints", "no-backing");
    assertEquals(0, exit, stderr());
    assertTrue(stdout().contains("no-backing"));
  }

  @Test
  void set_service_without_a_deployment_flag_fails() {
    int exit = run("set", "service", "broken", "--port", "8080");
    assertEquals(1, exit);
    assertTrue(stderr().contains("--deployment"));
  }

  @Test
  void get_service_not_found_produces_a_clear_error() {
    int exit = run("get", "service", "does-not-exist");
    assertEquals(1, exit);
    assertTrue(stderr().contains("not found"));
  }

  @Test
  void set_networkpolicy_then_get_networkpolicies_round_trips_then_delete() throws Exception {
    int setExit =
        run(
            "set",
            "networkpolicy",
            "acme-policy",
            "--tenant",
            "acme",
            "--allowed-caller-tenant",
            "partner");
    assertEquals(0, setExit, stderr());
    assertTrue(stdout().contains("networkpolicy/acme-policy configured"));

    outBuffer.reset();
    int getExit = run("get", "networkpolicies");
    assertEquals(0, getExit);
    assertTrue(stdout().contains("acme-policy"));

    outBuffer.reset();
    int getSingleExit =
        run("-o", "json", "get", "networkpolicy", "acme-policy", "--tenant", "acme");
    assertEquals(0, getSingleExit);
    assertTrue(stdout().contains("\"tenantId\":\"acme\""));
    assertTrue(stdout().contains("\"allowedCallerTenantIds\":[\"partner\"]"));

    int deleteExit = run("delete", "networkpolicy", "acme-policy", "--tenant", "acme");
    assertEquals(0, deleteExit);
    outBuffer.reset();
    int getAfterDeleteExit = run("get", "networkpolicy", "acme-policy", "--tenant", "acme");
    assertEquals(1, getAfterDeleteExit);
    assertTrue(stderr().contains("not found"));
  }

  @Test
  void set_networkpolicy_without_a_tenant_flag_fails() {
    int exit = run("set", "networkpolicy", "broken", "--allowed-caller-tenant", "partner");
    assertEquals(1, exit);
    assertTrue(stderr().contains("--tenant"));
  }

  @Test
  void set_networkpolicy_carries_interface_scoping_and_egress_restrictions() throws Exception {
    int setExit =
        run(
            "set",
            "networkpolicy",
            "acme-egress",
            "--tenant",
            "acme",
            "--service-interface",
            "com.acme.Orders",
            "--allowed-callee-tenant",
            "partner",
            "--deny-all-callers");
    assertEquals(0, setExit, stderr());

    outBuffer.reset();
    int getExit = run("-o", "json", "get", "networkpolicy", "acme-egress", "--tenant", "acme");
    assertEquals(0, getExit);
    assertTrue(stdout().contains("\"serviceInterfaceNames\":[\"com.acme.Orders\"]"), stdout());
    assertTrue(stdout().contains("\"allowedCalleeTenantIds\":[\"partner\"]"), stdout());
    assertTrue(stdout().contains("\"allowedCallerTenantIds\":[]"), stdout());
  }

  @Test
  void set_networkpolicy_restricting_no_direction_at_all_fails_client_side() {
    int exit = run("set", "networkpolicy", "broken", "--tenant", "acme");
    assertEquals(1, exit);
    assertTrue(stderr().contains("at least one direction"), stderr());
  }

  @Test
  void get_networkpolicy_not_found_produces_a_clear_error() {
    int exit = run("get", "networkpolicy", "does-not-exist", "--tenant", "acme");
    assertEquals(1, exit);
    assertTrue(stderr().contains("not found"));
  }

  @Test
  void get_networkpolicy_without_a_tenant_flag_fails_client_side() {
    int exit = run("get", "networkpolicy", "does-not-exist");
    assertEquals(1, exit);
    assertTrue(stderr().contains("--tenant"));
  }

  // ---- table-output humanization for get nodes / get deployments ----

  @Test
  void get_nodes_in_table_format_humanizes_capabilities_and_capacity_instead_of_raw_json()
      throws Exception {
    registerNode("node-table");

    int exit = run("get", "nodes");
    assertEquals(0, exit, stderr());
    assertTrue(stdout().contains("node-table"));
    assertTrue(stdout().contains("TIER_1"));
    // The raw JSON blob a table cell used to contain -- confirms the capabilities map is now
    // flattened into its own column rather than serialized whole.
    assertFalse(stdout().contains("supportedTiers"));
  }

  @Test
  void get_nodes_as_json_still_returns_the_raw_capabilities_and_capacity_shape() throws Exception {
    registerNode("node-raw");

    int exit = run("-o", "json", "get", "nodes");
    assertEquals(0, exit, stderr());
    assertTrue(stdout().contains("\"supportedTiers\""));
  }

  @Test
  void get_deployments_in_table_format_humanizes_spec_and_replicas_instead_of_raw_json()
      throws Exception {
    Path manifest = writeManifest("humanized-service", 3);
    run("apply", "-f", manifest.toString());

    outBuffer.reset();
    int exit = run("get", "deployments");
    assertEquals(0, exit, stderr());
    assertTrue(stdout().contains("humanized-service"));
    assertTrue(stdout().contains("com.gimle.example.orders@1.0.0"));
    // No placed instances (no real agent in this test) -- replicas prints as placed/desired and
    // health flags the shortfall, rather than a raw {"name":...,"moduleId":{...}} blob cell.
    assertTrue(stdout().contains("0/3"));
    assertTrue(stdout().contains("UNPLACED(3)"));
    assertFalse(stdout().contains("\"artifactPath\""));
  }

  @Test
  void get_deployments_as_json_still_returns_the_raw_spec_shape() throws Exception {
    Path manifest = writeManifest("raw-json-service", 1);
    run("apply", "-f", manifest.toString());

    outBuffer.reset();
    int exit = run("-o", "json", "get", "deployments");
    assertEquals(0, exit, stderr());
    assertTrue(stdout().contains("\"artifactPath\""));
  }

  // ---- events --limit -- previously unsupported by this command ----

  @Test
  void events_with_no_limit_returns_every_event() throws Exception {
    appendInstanceEvent("orders-service", 0, "evt-1", 1_000L);
    appendInstanceEvent("orders-service", 0, "evt-2", 2_000L);
    appendInstanceEvent("orders-service", 0, "evt-3", 3_000L);

    int exit = run("events", "orders-service", "0");
    assertEquals(0, exit, stderr());
    assertTrue(stdout().contains("evt-1"));
    assertTrue(stdout().contains("evt-2"));
    assertTrue(stdout().contains("evt-3"));
  }

  @Test
  void events_with_limit_caps_the_returned_list() throws Exception {
    appendInstanceEvent("orders-service", 0, "evt-a", 1_000L);
    appendInstanceEvent("orders-service", 0, "evt-b", 2_000L);
    appendInstanceEvent("orders-service", 0, "evt-c", 3_000L);

    int exit = run("-o", "json", "events", "orders-service", "0", "--limit", "1");
    assertEquals(0, exit, stderr());
    List<Object> parsed = Json.asArray(Json.parse(stdout()));
    assertEquals(1, parsed.size(), stdout());
  }

  @Test
  void events_with_a_non_numeric_limit_fails() {
    int exit = run("events", "orders-service", "0", "--limit", "not-a-number");
    assertEquals(1, exit);
    assertTrue(stderr().contains("--limit"));
  }

  // ---- apply -f against an unreadable manifest file -- the reason is stated, not just the path
  // repeated ----

  @Test
  void apply_against_a_missing_manifest_file_states_no_such_file_not_just_the_path_twice() {
    Path missing = tempDir.resolve("does-not-exist.yaml");

    int exit = run("apply", "-f", missing.toString());
    assertEquals(1, exit);
    assertTrue(stderr().contains("could not read manifest file"), stderr());
    assertTrue(stderr().contains("no such file"), stderr());
  }

  @Test
  void apply_against_a_directory_states_is_a_directory() {
    int exit = run("apply", "-f", tempDir.toString());
    assertEquals(1, exit);
    assertTrue(stderr().contains("could not read manifest file"), stderr());
    assertTrue(stderr().contains("is a directory"), stderr());
  }

  @Test
  void apply_with_more_than_one_file_flag_is_rejected_not_silently_applying_only_the_first()
      throws Exception {
    // FUNC-44 regression: the original forward-scan implementation returned the first -f it saw
    // and never looked further, so a second -f silently vanished with no warning.
    Path first = writeManifest("first-service", 1);
    Path second = writeManifest("second-service", 1);

    int exit = run("apply", "-f", first.toString(), "-f", second.toString());

    assertNotEquals(0, exit);
    assertTrue(stderr().contains("exactly one"), stderr());
    outBuffer.reset();
    // Neither manifest was ever applied -- rejected before any request was made.
    assertEquals(0, run("get", "deployments"));
    assertFalse(stdout().contains("first-service"), stdout());
    assertFalse(stdout().contains("second-service"), stdout());
  }

  // ---- a single-resource CLI verb rejects more than one name/id rather than silently keeping
  // only the first (FUNC-44) ----

  @Test
  void deleting_a_tenant_with_more_than_one_positional_argument_is_rejected() {
    assertEquals(
        0,
        run(
            "set",
            "tenant",
            "acme",
            "--max-memory-bytes",
            "1",
            "--max-cpu-millicores",
            "1",
            "--max-instances",
            "1"),
        errBuffer::toString);

    int exitCode = run("delete", "tenant", "acme", "unexpected-extra-argument");

    assertNotEquals(0, exitCode);
    assertTrue(stderr().contains("too many arguments"), stderr());
    outBuffer.reset();
    // Rejected before any request was made -- "acme" was never actually deleted.
    assertEquals(0, run("get", "tenant", "acme"));
  }

  @Test
  void getting_a_tenant_with_more_than_one_positional_argument_is_rejected() {
    int exitCode = run("get", "tenant", "acme", "unexpected-extra-argument");

    assertNotEquals(0, exitCode);
    assertTrue(stderr().contains("too many arguments"), stderr());
  }

  @Test
  void cordoning_with_more_than_one_positional_argument_is_rejected() {
    int exitCode = run("cordon", "node-1", "node-2");

    assertNotEquals(0, exitCode);
    assertTrue(stderr().contains("too many arguments"), stderr());
  }

  // ---- -h/--help scopes to wherever it appears in the argument list ----

  @Test
  void bare_help_flag_prints_the_full_top_level_usage() {
    int exit = run("-h");
    assertEquals(0, exit, stderr());
    assertTrue(stdout().contains("usage: gimle <verb> <resource>"), stdout());
    assertTrue(stdout().contains("secret list <tenantId>"), stdout());
  }

  @Test
  void get_help_prints_only_the_get_resource_listing_not_the_full_usage() {
    int exit = run("get", "-h");
    assertEquals(0, exit, stderr());
    assertTrue(stdout().contains("usage: gimle get <resource>"), stdout());
    assertTrue(stdout().contains("deployments [name]"), stdout());
    // The full top-level usage's own unrelated sections must not leak into this scoped block.
    assertFalse(stdout().contains("secret list <tenantId>"), stdout());
  }

  @Test
  void get_deployments_help_prints_just_that_one_form() {
    int exit = run("get", "deployments", "-h");
    assertEquals(0, exit, stderr());
    assertEquals("usage: gimle get deployments [name]", stdout().trim());
  }

  @Test
  void apply_help_prints_just_the_apply_usage() {
    int exit = run("apply", "--help");
    assertEquals(0, exit, stderr());
    assertTrue(stdout().contains("usage: gimle apply -f <file.yaml>"), stdout());
    assertFalse(stdout().contains("secret list <tenantId>"), stdout());
  }

  @Test
  void secret_help_prints_just_the_secret_subverbs() {
    int exit = run("secret", "-h");
    assertEquals(0, exit, stderr());
    assertTrue(stdout().contains("usage: gimle secret <verb>"), stdout());
    assertTrue(stdout().contains("rotate-key"), stdout());
    assertFalse(stdout().contains("usage: gimle get <resource>"), stdout());
  }

  @Test
  void help_flag_never_requires_a_configured_server() {
    int exit = GimleCli.run(new String[] {"get", "deployments", "-h"}, out, err);
    assertEquals(0, exit, stderr());
    assertFalse(stderr().contains("no control-plane server configured"), stderr());
  }
}
