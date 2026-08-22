package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    StateStore store = new StateStore(tempDir.resolve("store"));
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

  private Path writeManifest(String name, int replicas) throws IOException {
    Path file = tempDir.resolve(name + ".yaml");
    Files.writeString(
        file,
        """
        kind: Deployment
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
  void secret_set_then_get_round_trips_the_plaintext_value() throws Exception {
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
    run("secret", "set", "acme", "db-password", "--value", "hunter2");

    outBuffer.reset();
    int listExit = run("secret", "list", "acme");
    assertEquals(0, listExit, stderr());
    assertTrue(stdout().contains("db-password"));
    assertFalse(stdout().contains("hunter2"));
  }

  @Test
  void secret_versions_lists_every_claimed_version_after_two_writes() throws Exception {
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
    run("secret", "set", "acme", "db-password", "--value", "v1");
    run("secret", "set", "acme", "db-password", "--value", "v2");

    outBuffer.reset();
    int getExit = run("secret", "get", "acme", "db-password", "--version", "1");
    assertEquals(0, getExit, stderr());
    assertTrue(stdout().contains("v1"));
  }

  @Test
  void secret_delete_then_get_returns_not_found() throws Exception {
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
    int getSingleExit = run("-o", "json", "get", "networkpolicy", "acme-policy");
    assertEquals(0, getSingleExit);
    assertTrue(stdout().contains("\"tenantId\":\"acme\""));
    assertTrue(stdout().contains("\"allowedCallerTenantIds\":[\"partner\"]"));

    int deleteExit = run("delete", "networkpolicy", "acme-policy");
    assertEquals(0, deleteExit);
    outBuffer.reset();
    int getAfterDeleteExit = run("get", "networkpolicy", "acme-policy");
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
  void get_networkpolicy_not_found_produces_a_clear_error() {
    int exit = run("get", "networkpolicy", "does-not-exist");
    assertEquals(1, exit);
    assertTrue(stderr().contains("not found"));
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
}
