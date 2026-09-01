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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Exercises {@code gimle get <resource> --watch} against a real {@link ApiServer}, the same "real
 * server, not mocked" convention {@link GimleCliTest} establishes -- including the cases only a
 * real server can produce: a resource genuinely changing between two ticks, and a server that stops
 * answering halfway through a watch.
 *
 * <p>Every watch here is bounded by {@code --watch-ticks} or ends by the server going away, so no
 * test depends on an interrupt to terminate; the per-test timeouts are a backstop against a watch
 * that fails to terminate at all, which is the failure mode worth catching loudly.
 *
 * <p>The read lock is the same one {@link GimleCliTest} takes: this class starts a real server, and
 * another class repoints {@code gimle.transport.protocol} while it runs.
 */
@ResourceLock(value = Resources.SYSTEM_PROPERTIES, mode = ResourceAccessMode.READ)
class ResourceWatchTest {

  private static final String WATCH_INTERVAL = "--watch-interval=0.05";

  @TempDir(cleanup = CleanupMode.NEVER)
  java.nio.file.Path tempDir;

  private RaftNode storeRaftNode;
  private StoreTransport storeTransport;
  private StoreClient storeClient;
  private FafnirServer fafnirServer;
  private FafnirClient fafnirClient;
  private ApiServer server;
  private boolean serverClosed;
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
    outBuffer = new ByteArrayOutputStream();
    errBuffer = new ByteArrayOutputStream();
    out = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);
    err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8);
  }

  @AfterEach
  void stopServer() {
    closeServer();
    fafnirClient.close();
    fafnirServer.close();
    storeClient.close();
    storeTransport.close();
    storeRaftNode.close();
  }

  private void closeServer() {
    if (!serverClosed) {
      serverClosed = true;
      server.close();
    }
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
   * Applies a deployment straight over HTTP rather than through {@code gimle apply} -- a mutation
   * made while a watch is running must not write into the same stdout buffer the watch's own output
   * is being asserted on.
   */
  private void putDeployment(String name, int replicas) throws Exception {
    String manifest =
        """
        kind: Deployment
        name: %s
        module:
          name: com.gimle.example.orders
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
        replicas: %d
        """
            .formatted(name, replicas);
    HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://" + serverAddress + "/deployments/" + name))
                .PUT(HttpRequest.BodyPublishers.ofString(manifest))
                .build(),
            HttpResponse.BodyHandlers.discarding());
  }

  private void deleteDeployment(String name) throws Exception {
    HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://" + serverAddress + "/deployments/" + name))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.discarding());
  }

  private void registerNode(String nodeId) throws Exception {
    HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(
                    URI.create("http://" + serverAddress + "/nodes/" + nodeId + "/register"))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"capabilities\":{\"supportedTiers\":[\"TIER_1\"]}}"))
                .build(),
            HttpResponse.BodyHandlers.discarding());
  }

  private void cordonNode(String nodeId) throws Exception {
    HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(
                    URI.create("http://" + serverAddress + "/nodes/" + nodeId + "/cordon"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.discarding());
  }

  /**
   * Runs {@code action} on its own thread once the watch's first tick has actually reached stdout,
   * so the change under test is guaranteed to fall between two ticks rather than racing the watch's
   * own startup.
   */
  private Thread afterFirstTick(String marker, Mutation action) {
    Thread thread =
        new Thread(
            () -> {
              try {
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (!stdout().contains(marker) && System.nanoTime() < deadline) {
                  Thread.sleep(5);
                }
                action.run();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } catch (Exception e) {
                throw new IllegalStateException("mid-watch mutation failed", e);
              }
            },
            "watch-test-mutation");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private interface Mutation {
    void run() throws Exception;
  }

  private List<String> stdoutLines() {
    List<String> lines = new ArrayList<>();
    for (String line : stdout().split("\n")) {
      if (!line.isBlank()) {
        lines.add(line);
      }
    }
    return lines;
  }

  @Test
  @Timeout(60)
  void bounded_watch_prints_one_full_snapshot_then_exits() throws Exception {
    putDeployment("orders-service", 3);

    int exit = run("get", "deployments", "--watch", "--watch-ticks=1", WATCH_INTERVAL);

    assertEquals(0, exit, stderr());
    List<String> lines = stdoutLines();
    assertEquals(2, lines.size(), stdout());
    assertTrue(lines.get(0).startsWith("EVENT\tname\t"), stdout());
    assertTrue(lines.get(1).startsWith("ADDED\torders-service\t"), stdout());
  }

  @Test
  @Timeout(60)
  void watch_reports_a_deployment_applied_between_ticks() throws Exception {
    putDeployment("orders-service", 3);
    Thread mutation = afterFirstTick("orders-service", () -> putDeployment("billing-service", 1));

    int exit = run("get", "deployments", "--watch", "--watch-ticks=40", WATCH_INTERVAL);
    mutation.join();

    assertEquals(0, exit, stderr());
    assertTrue(stdout().contains("ADDED\tbilling-service\t"), stdout());
    // Only the new row is reported: the unchanged one is printed once, on the first tick.
    assertEquals(1, countLinesContaining("orders-service"), stdout());
  }

  @Test
  @Timeout(60)
  void watch_reports_a_replica_change_as_modified() throws Exception {
    putDeployment("orders-service", 3);
    Thread mutation = afterFirstTick("orders-service", () -> putDeployment("orders-service", 7));

    int exit = run("get", "deployments", "--watch", "--watch-ticks=40", WATCH_INTERVAL);
    mutation.join();

    assertEquals(0, exit, stderr());
    assertTrue(stdout().contains("ADDED\torders-service\t"), stdout());
    assertTrue(stdout().contains("MODIFIED\torders-service\t"), stdout());
    assertTrue(stdout().contains("0/7"), stdout());
    // The header belongs to the watch, not to each tick: it is printed exactly once.
    assertEquals(1, countLinesContaining("EVENT\tname"), stdout());
  }

  @Test
  @Timeout(60)
  void watch_reports_a_deleted_deployment() throws Exception {
    putDeployment("orders-service", 3);
    Thread mutation = afterFirstTick("orders-service", () -> deleteDeployment("orders-service"));

    int exit = run("get", "deployments", "--watch", "--watch-ticks=40", WATCH_INTERVAL);
    mutation.join();

    assertEquals(0, exit, stderr());
    assertTrue(stdout().contains("DELETED\torders-service\t"), stdout());
  }

  @Test
  @Timeout(60)
  void watch_on_nodes_reports_a_cordon_landing() throws Exception {
    registerNode("node-a");
    Thread mutation = afterFirstTick("node-a", () -> cordonNode("node-a"));

    int exit = run("get", "nodes", "--watch", "--watch-ticks=40", WATCH_INTERVAL);
    mutation.join();

    assertEquals(0, exit, stderr());
    assertTrue(stdout().contains("ADDED\tnode-a\t"), stdout());
    assertTrue(stdout().contains("MODIFIED\tnode-a\t"), stdout());
  }

  @Test
  @Timeout(60)
  void json_watch_emits_ndjson_one_object_per_change() throws Exception {
    putDeployment("orders-service", 3);
    Thread mutation = afterFirstTick("orders-service", () -> putDeployment("billing-service", 1));

    int exit =
        run("-o", "json", "get", "deployments", "--watch", "--watch-ticks=40", WATCH_INTERVAL);
    mutation.join();

    assertEquals(0, exit, stderr());
    List<String> lines = stdoutLines();
    assertEquals(2, lines.size(), stdout());
    for (String line : lines) {
      // NDJSON, not a JSON array: an endless stream has no closing bracket to print, so every
      // line stands alone as one complete object.
      assertTrue(line.startsWith("{") && line.endsWith("}"), line);
      Map<String, Object> envelope = Json.asObject(Json.parse(line));
      assertEquals("ADDED", envelope.get("event"), line);
      assertTrue(envelope.get("object") instanceof Map, line);
      // The raw resource shape, exactly as the non-watch -o json form returns it.
      assertTrue(Json.write(envelope.get("object")).contains("\"artifactPath\""), line);
    }
  }

  @Test
  @Timeout(60)
  void watch_warns_and_gives_up_when_the_server_goes_away_mid_watch() throws Exception {
    putDeployment("orders-service", 3);
    Thread mutation = afterFirstTick("orders-service", this::closeServer);

    int exit = run("get", "deployments", "--watch", "--watch-ticks=200", WATCH_INTERVAL);
    mutation.join();

    assertEquals(CliExitCode.UNAVAILABLE.code(), exit, stderr());
    // Never a silent spin: every failed poll says so before the watch gives up.
    assertTrue(stderr().contains("watch poll failed (1/5)"), stderr());
    assertTrue(stderr().contains("watch gave up after 5 consecutive failed polls"), stderr());
  }

  @Test
  @Timeout(60)
  void watch_fails_immediately_when_the_very_first_poll_cannot_reach_the_server() throws Exception {
    int unusedPort;
    try (ServerSocket probe = new ServerSocket(0)) {
      unusedPort = probe.getLocalPort();
    }

    int exit =
        GimleCli.run(
            new String[] {
              "get",
              "deployments",
              "--watch",
              "--watch-ticks=200",
              WATCH_INTERVAL,
              "--server",
              "localhost:" + unusedPort
            },
            out,
            err);

    assertEquals(CliExitCode.UNAVAILABLE.code(), exit, stderr());
    // Nothing to watch yet, so it fails like the one-shot form rather than retrying into a hang.
    assertFalse(stderr().contains("watch poll failed"), stderr());
    assertTrue(stderr().contains("could not reach control plane"), stderr());
  }

  @Test
  @Timeout(60)
  void watch_is_rejected_for_a_resource_that_has_no_watch_form() {
    int exit = run("get", "tenants", "--watch", "--watch-ticks=1");

    assertEquals(CliExitCode.GENERIC.code(), exit);
    assertTrue(stderr().contains("--watch is not available for 'tenants'"), stderr());
    assertTrue(stderr().contains("deployments, jobs"), stderr());
  }

  @Test
  @Timeout(60)
  void watch_is_rejected_alongside_manifest_output() {
    int exit = run("-o", "manifest", "get", "deployment", "orders-service", "--watch");

    assertEquals(CliExitCode.INVALID_INPUT.code(), exit);
    assertTrue(stderr().contains("has no --watch form"), stderr());
  }

  @Test
  @Timeout(60)
  void a_non_positive_watch_interval_is_rejected_rather_than_spun_on() {
    int exit = run("get", "deployments", "--watch", "--watch-interval=0");

    assertEquals(CliExitCode.INVALID_INPUT.code(), exit);
    assertTrue(stderr().contains("--watch-interval must be between"), stderr());
  }

  @Test
  @Timeout(60)
  void watch_tuning_flags_without_watch_are_rejected() {
    int exit = run("get", "deployments", "--watch-ticks=3");

    assertEquals(CliExitCode.INVALID_INPUT.code(), exit);
    assertTrue(stderr().contains("only mean something alongside --watch"), stderr());
  }

  @Test
  @Timeout(60)
  void watch_of_a_single_named_deployment_follows_just_that_one() throws Exception {
    putDeployment("orders-service", 3);
    putDeployment("billing-service", 1);
    Thread mutation = afterFirstTick("orders-service", () -> putDeployment("orders-service", 9));

    int exit =
        run("get", "deployment", "orders-service", "--watch", "--watch-ticks=40", WATCH_INTERVAL);
    mutation.join();

    assertEquals(0, exit, stderr());
    assertTrue(stdout().contains("MODIFIED\torders-service\t"), stdout());
    assertFalse(stdout().contains("billing-service"), stdout());
  }

  @Test
  @Timeout(60)
  void watching_an_empty_resource_set_says_so_rather_than_printing_nothing() {
    int exit = run("get", "deployments", "--watch", "--watch-ticks=1", WATCH_INTERVAL);

    assertEquals(0, exit, stderr());
    assertTrue(stdout().contains("No resources found."), stdout());
  }

  private int countLinesContaining(String text) {
    int count = 0;
    for (String line : stdoutLines()) {
      if (line.contains(text)) {
        count++;
      }
    }
    return count;
  }
}
