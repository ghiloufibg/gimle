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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
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
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * The CLI's output-format and exit-code contract, exercised against a real {@link ApiServer} (the
 * same "real server, not mocked" convention {@code GimleCliTest} establishes) rather than a stub:
 * every exit code below is the one a real control-plane response actually produces, and every
 * {@code -o json} assertion parses the bytes the command really wrote.
 */
class CliOutputContractTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private RaftNode storeRaftNode;
  private StoreTransport storeTransport;
  private StoreClient storeClient;
  private FafnirServer fafnirServer;
  private FafnirClient fafnirClient;
  private ApiServer server;
  private HttpServer stubAgent;
  private String serverAddress;
  private ByteArrayOutputStream outBuffer;
  private ByteArrayOutputStream errBuffer;
  private PrintStream out;
  private PrintStream err;

  @BeforeEach
  void startServer() throws IOException {
    StateStore store = new StateStore();
    RaftLog raftLog = new RaftLog(tempDir.resolve("raft"));
    storeRaftNode = new RaftNode("self", Map.of(), raftLog, store);
    storeRaftNode.start();
    StoreNode storeNode = new StoreNode(storeRaftNode, store, Map.of());
    storeTransport = new StoreTransport(storeNode);
    SocketAddress storeAddress = storeTransport.listen(new InetSocketAddress("127.0.0.1", 0));
    storeClient = new StoreClient(List.of(storeAddress));

    FafnirCrypto fafnirCrypto = new FafnirCrypto(storeClient, tempDir.resolve("keys/secret.key"));
    fafnirServer = new FafnirServer(fafnirCrypto, 0);
    fafnirServer.start();
    fafnirClient = new FafnirClient("localhost:" + fafnirServer.port());

    server = new ApiServer(storeClient, 0, fafnirClient);
    server.start();
    serverAddress = "localhost:" + server.port();

    // Stands in for a node agent's own /volumes surface, which the control plane fans out to --
    // the volume verbs have no other way to reach a real answer.
    stubAgent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stubAgent.createContext("/volumes", CliOutputContractTest::serveVolumes);
    stubAgent.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    stubAgent.start();

    outBuffer = new ByteArrayOutputStream();
    errBuffer = new ByteArrayOutputStream();
    out = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);
    err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8);
  }

  @AfterEach
  void stopServer() {
    stubAgent.stop(0);
    server.close();
    fafnirClient.close();
    fafnirServer.close();
    storeClient.close();
    storeTransport.close();
    storeRaftNode.close();
  }

  /** The raw request URI of the most recent DELETE the stub agent saw, tenant query included. */
  private static final java.util.concurrent.atomic.AtomicReference<String> lastVolumeDelete =
      new java.util.concurrent.atomic.AtomicReference<>();

  private static void serveVolumes(HttpExchange exchange) throws IOException {
    if ("DELETE".equals(exchange.getRequestMethod())) {
      lastVolumeDelete.set(exchange.getRequestURI().toString());
    }
    String body =
        "GET".equals(exchange.getRequestMethod())
            ? Json.write(
                List.of(Map.of("statefulSet", "orders", "instanceIndex", 0, "sizeBytes", 4096)))
            : "{\"destroyed\":true}";
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream responseBody = exchange.getResponseBody()) {
      responseBody.write(bytes);
    }
    exchange.close();
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

  private void registerNode(String nodeId, String agentApiAddress) throws Exception {
    Map<String, Object> body =
        agentApiAddress == null
            ? Map.of("capabilities", Map.of("supportedTiers", List.of("TIER_1")))
            : Map.of(
                "capabilities",
                Map.of("supportedTiers", List.of("TIER_1")),
                "apiAddress",
                agentApiAddress);
    HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(
                    URI.create("http://" + serverAddress + "/nodes/" + nodeId + "/register"))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build(),
            HttpResponse.BodyHandlers.discarding());
  }

  private void createTenant(String tenantId) {
    assertEquals(
        0,
        run(
            "set",
            "tenant",
            tenantId,
            "--max-memory-bytes",
            "1000000000",
            "--max-cpu-millicores",
            "4000",
            "--max-instances",
            "10"),
        stderr());
  }

  // ---- -o json for the node and volume mutating verbs ----

  @Test
  void cordon_under_json_output_emits_a_parsable_result_object() throws Exception {
    registerNode("node-a", null);

    assertEquals(0, run("-o", "json", "cordon", "node-a"), stderr());

    Map<String, Object> result = Json.asObject(Json.parse(stdout()));
    assertEquals("cordoned", result.get("result"));
    assertEquals("node", result.get("kind"));
    assertEquals("node-a", result.get("id"));
  }

  @Test
  void uncordon_under_json_output_emits_a_parsable_result_object() throws Exception {
    registerNode("node-a", null);
    run("cordon", "node-a");
    outBuffer.reset();

    assertEquals(0, run("-o", "json", "uncordon", "node-a"), stderr());

    assertEquals("uncordoned", Json.asObject(Json.parse(stdout())).get("result"));
  }

  @Test
  void taint_and_untaint_under_json_output_name_the_tenant_they_applied_to() throws Exception {
    registerNode("node-a", null);

    assertEquals(0, run("-o", "json", "taint", "node-a", "acme"), stderr());
    Map<String, Object> tainted = Json.asObject(Json.parse(stdout()));
    assertEquals("tainted", tainted.get("result"));
    assertEquals("acme", tainted.get("tenantId"));

    outBuffer.reset();
    assertEquals(0, run("-o", "json", "untaint", "node-a", "acme"), stderr());
    Map<String, Object> untainted = Json.asObject(Json.parse(stdout()));
    assertEquals("untainted", untainted.get("result"));
    assertEquals("acme", untainted.get("tenantId"));
  }

  @Test
  void the_node_verbs_still_print_their_human_sentence_under_the_default_table_format()
      throws Exception {
    registerNode("node-a", null);

    assertEquals(0, run("cordon", "node-a"), stderr());

    assertEquals("node/node-a cordoned", stdout().strip());
  }

  /**
   * The table and {@code -o json} are two renderings of one {@code GET /nodes} response, so a taint
   * an operator can see in the table is necessarily in the JSON as well -- pinned here because the
   * two renderings are produced by different code paths ({@code humanize} flattens, {@code
   * withStatus} passes the raw shape through) and only this asserts they stay in agreement.
   */
  @Test
  void a_nodes_taint_reads_back_identically_in_the_table_and_in_json() throws Exception {
    registerNode("node-a", null);
    assertEquals(0, run("taint", "node-a", "acme"), stderr());
    outBuffer.reset();

    assertEquals(0, run("get", "nodes"), stderr());
    String table = stdout();
    outBuffer.reset();
    assertEquals(0, run("-o", "json", "get", "nodes"), stderr());
    List<Map<String, Object>> json = Json.asObjectList(Json.parse(stdout()));

    List<String> columns = List.of(table.lines().findFirst().orElseThrow().split("\t"));
    int taintsColumn = columns.indexOf("taints");
    assertTrue(taintsColumn >= 0, table);
    String tableTaints = table.lines().skip(1).findFirst().orElseThrow().split("\t")[taintsColumn];
    assertEquals("acme", tableTaints, table);
    assertEquals(List.of("acme"), json.get(0).get("taints"), stdout());
  }

  /**
   * {@code label node} reads the node's current operator labels before folding its own edits into
   * them, so the read has to be answerable: addressing one node by name used to reach the
   * sub-resource dispatcher and come back a usage error, which failed the verb outright.
   */
  @Test
  void label_node_reads_the_nodes_current_labels_and_applies_its_edit() throws Exception {
    registerNode("node-a", null);

    assertEquals(0, run("label", "node", "node-a", "zone=eu"), stderr());
    assertEquals("node/node-a labelled zone=eu", stdout().strip());

    outBuffer.reset();
    assertEquals(0, run("label", "node", "node-a", "tier=gold"), stderr());
    assertEquals("node/node-a labelled zone=eu,tier=gold", stdout().strip());

    outBuffer.reset();
    assertEquals(0, run("label", "node", "node-a", "zone=eu-"), stderr());
    assertEquals("node/node-a labelled tier=gold", stdout().strip());
  }

  @Test
  void volume_destroy_under_json_output_emits_a_parsable_result_object() throws Exception {
    registerNode("node-vol", "127.0.0.1:" + stubAgent.getAddress().getPort());

    assertEquals(
        0, run("-o", "json", "volume", "destroy", "orders", "0", "--node", "node-vol"), stderr());

    Map<String, Object> result = Json.asObject(Json.parse(stdout()));
    assertEquals("destroyed", result.get("result"));
    assertEquals("volume", result.get("kind"));
    assertEquals("orders/0", result.get("id"));
    assertEquals("node-vol", result.get("nodeId"));
  }

  @Test
  void volume_destroy_still_prints_its_human_sentence_under_the_default_table_format()
      throws Exception {
    registerNode("node-vol", "127.0.0.1:" + stubAgent.getAddress().getPort());

    assertEquals(0, run("volume", "destroy", "orders", "0", "--node", "node-vol"), stderr());

    assertEquals("destroyed volume orders[0] on node node-vol", stdout().strip());
  }

  /**
   * Without a way to name the volume's tenant, {@code volume destroy} could only ever address one
   * namespace -- so a tenanted volume that {@code volume list} happily shows was unreachable, and
   * the request landed on whatever volume the server's own default resolved to instead.
   */
  @Test
  void volume_destroy_sends_the_tenant_it_was_given_all_the_way_to_the_owning_agent()
      throws Exception {
    registerNode("node-vol", "127.0.0.1:" + stubAgent.getAddress().getPort());
    lastVolumeDelete.set(null);

    assertEquals(
        0,
        run("volume", "destroy", "orders", "0", "--node", "node-vol", "--tenant", "acme"),
        stderr());

    assertTrue(lastVolumeDelete.get().contains("tenant=acme"), lastVolumeDelete.get());
    assertTrue(stdout().contains("for tenant acme"), stdout());
  }

  @Test
  void volume_destroy_naming_no_tenant_reaches_the_agent_with_no_tenant_at_all() throws Exception {
    registerNode("node-vol", "127.0.0.1:" + stubAgent.getAddress().getPort());
    lastVolumeDelete.set(null);

    assertEquals(0, run("volume", "destroy", "orders", "0", "--node", "node-vol"), stderr());

    assertFalse(lastVolumeDelete.get().contains("tenant="), lastVolumeDelete.get());
  }

  @Test
  void volume_destroy_under_json_output_reports_the_tenant_it_addressed() throws Exception {
    registerNode("node-vol", "127.0.0.1:" + stubAgent.getAddress().getPort());

    assertEquals(
        0,
        run(
            "-o",
            "json",
            "volume",
            "destroy",
            "orders",
            "0",
            "--node",
            "node-vol",
            "--tenant",
            "acme"),
        stderr());

    assertEquals("acme", Json.asObject(Json.parse(stdout())).get("tenantId"));
  }

  @Test
  void the_unreachable_nodes_warning_goes_to_stderr_leaving_stdout_valid_json() throws Exception {
    // A node registered without an agent address is exactly the case the control plane reports as
    // unreachable rather than silently omitting.
    registerNode("node-gone", null);

    assertEquals(0, run("-o", "json", "volume", "list"), stderr());

    assertTrue(stderr().contains("unreachable nodes"), stderr());
    assertTrue(stderr().contains("node-gone"), stderr());
    assertFalse(stdout().contains("warning"), stdout());
    assertTrue(Json.asObjectList(Json.parse(stdout())).isEmpty(), stdout());
  }

  // ---- the exit-code contract ----

  @Test
  void a_rejected_request_exits_two_for_invalid_input() throws Exception {
    Path manifest = tempDir.resolve("v1-deployment.yaml");
    Files.writeString(
        manifest,
        """
        apiVersion: v1
        kind: Deployment
        name: legacy-orders
        module:
          name: com.gimle.example.orders
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
        replicas: 1
        """);

    assertEquals(2, run("apply", "-f", manifest.toString()), stderr());
    assertTrue(stderr().contains("invalid request"), stderr());
  }

  @Test
  void a_client_side_manifest_error_also_exits_two() throws Exception {
    // An ArtifactSet is parsed client-side before anything is sent, so a bundle entry missing its
    // required 'command' fails through the manifest-exception branch rather than the server's 400
    // -- a different code path to the same exit code.
    Path manifest = tempDir.resolve("broken.yaml");
    Files.writeString(
        manifest,
        """
        kind: ArtifactSet
        modules:
          - artifact: some-dir
            kind: bundle
            name: com.example.no-command
            version: 1.0.0
        """);

    assertEquals(2, run("apply", "-f", manifest.toString()), stderr());
    assertTrue(stderr().contains("invalid manifest"), stderr());
  }

  @Test
  void an_unknown_resource_exits_three_for_not_found() {
    assertEquals(3, run("get", "deployment", "never-created"), stderr());
    assertTrue(stderr().contains("not found"), stderr());
  }

  @Test
  void a_refused_request_exits_four_for_forbidden() {
    createTenant("acme");
    errBuffer.reset();

    // Plaintext transport carries no caller identity, so the control plane refuses a second real
    // tenant outright -- a genuine 403 with no RBAC objects to set up first.
    assertEquals(
        4,
        run(
            "set",
            "tenant",
            "beta",
            "--max-memory-bytes",
            "1000000",
            "--max-cpu-millicores",
            "1000",
            "--max-instances",
            "1"),
        stderr());
    assertTrue(stderr().contains("forbidden") || stderr().contains("one real tenant"), stderr());
  }

  @Test
  void a_conflicting_request_exits_five_for_conflict() throws Exception {
    createTenant("acme");
    Path manifest = tempDir.resolve("dangling-refs.yaml");
    Files.writeString(
        manifest,
        """
        kind: Deployment
        name: orders
        tenantId: acme
        module:
          name: com.gimle.example.orders
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
        replicas: 1
        configMapRefs:
          - no-such-configmap
        """);
    errBuffer.reset();

    assertEquals(5, run("apply", "-f", manifest.toString()), stderr());
    assertTrue(stderr().contains("conflict"), stderr());
  }

  @Test
  void an_unreachable_control_plane_exits_six() {
    int exit = GimleCli.run(new String[] {"get", "tenants", "--server", "localhost:1"}, out, err);

    assertEquals(6, exit, stderr());
    assertTrue(stderr().contains("could not reach control plane"), stderr());
  }

  @Test
  void a_usage_error_stays_on_the_unclassified_generic_code() {
    assertEquals(1, run("frobnicate"), stderr());
    assertTrue(stderr().contains("usage:"), stderr());
  }

  @Test
  void a_successful_command_still_exits_zero() throws Exception {
    registerNode("node-a", null);

    assertEquals(0, run("get", "nodes"), stderr());
  }

  @Test
  void the_usage_text_documents_the_exit_code_table() {
    assertEquals(0, GimleCli.run(new String[] {"-h"}, out, err));
    assertTrue(stdout().contains("exit codes:"), stdout());
  }
}
