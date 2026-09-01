package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * The Galdr walkthrough through the real CLI against a real {@link ApiServer} -- the same "real
 * server, not mocked" convention {@code GimleCliTest} establishes: define a kind via {@code apply
 * -f}, list it via {@code gimle kinds}, apply an instance, read it back through every noun the
 * definition declares (prefixed kind name, plural, short name) with printColumns rendered by dotted
 * path, and delete it -- plus the apply fallthrough's server-side unknown-kind error and the
 * non-retryable-409 surfacing.
 */
class CustomResourceCommandTest {

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

  private void resetOutput() {
    outBuffer.reset();
    errBuffer.reset();
  }

  private Path writeManifest(String fileName, String content) throws IOException {
    Path file = tempDir.resolve(fileName);
    Files.writeString(file, content);
    return file;
  }

  private static final String GREETING_DEFINITION =
      """
      kind: KindDefinition
      name: Greeting
      scope: Tenant
      description: "A greeting this cluster should keep saying"
      names:
        plural: greetings
        shortNames: [gr]
      schema:
        fields:
          - name: message
            type: string
            required: true
          - name: repeat
            type: int
            default: 1
            min: 1
            max: 100
      printColumns:
        - name: MESSAGE
          path: spec.message
        - name: SAID
          path: status.timesSaid
      """;

  private static final String HELLO_INSTANCE =
      """
      kind: custom.Greeting
      name: hello-world
      tenantId: team-a
      spec:
        message: "hello"
        repeat: 3
      """;

  private Path defineGreeting() throws IOException {
    Path definition = writeManifest("greeting-kind.yaml", GREETING_DEFINITION);
    assertEquals(0, run("apply", "-f", definition.toString()), stderr());
    resetOutput();
    return definition;
  }

  @Test
  void the_walkthrough_end_to_end_through_the_cli() throws Exception {
    Path definition = writeManifest("greeting-kind.yaml", GREETING_DEFINITION);
    assertEquals(0, run("apply", "-f", definition.toString()), stderr());
    // The prefix normalization reaches the submitting operator on stderr, kubectl-style.
    assertTrue(stderr().contains("stored as 'custom.Greeting'"), stderr());
    assertTrue(stdout().contains("kinddefinition/Greeting applied"), stdout());
    resetOutput();

    assertEquals(0, run("kinds"), stderr());
    String kinds = stdout();
    assertTrue(kinds.contains("custom.Greeting"), kinds);
    assertTrue(kinds.contains("greetings"), kinds);
    assertTrue(kinds.contains("gr"), kinds);
    assertTrue(kinds.contains("Tenant"), kinds);
    // Zero instances yet -- the count column is live, not decorative.
    assertTrue(kinds.contains("0"), kinds);
    resetOutput();

    Path instance = writeManifest("hello.yaml", HELLO_INSTANCE);
    assertEquals(0, run("apply", "-f", instance.toString()), stderr());
    assertTrue(stdout().contains("custom.Greeting/hello-world applied"), stdout());
    resetOutput();

    // All three nouns resolve to the same kind: exact prefixed name, plural, short name.
    for (String noun : List.of("custom.Greeting", "greetings", "gr")) {
      assertEquals(0, run("get", noun), "noun " + noun + ": " + stderr());
      String table = stdout();
      assertTrue(table.contains("NAME"), table);
      assertTrue(table.contains("MESSAGE"), table);
      assertTrue(table.contains("hello-world"), table);
      assertTrue(table.contains("team-a"), table);
      assertTrue(table.contains("hello"), table);
      // The SAID column resolves status.timesSaid -- no status reported yet, so an empty cell,
      // never an error.
      assertTrue(table.contains("SAID"), table);
      assertTrue(table.contains("-"), table);
      resetOutput();
    }

    assertEquals(0, run("get", "greetings", "hello-world", "--tenant", "team-a"), stderr());
    assertTrue(stdout().contains("hello-world"), stdout());
    resetOutput();

    // -o json emits spec (defaults persisted) and status verbatim.
    assertEquals(0, run("get", "gr", "hello-world", "--tenant", "team-a", "-o", "json"), stderr());
    Map<String, Object> resource = Json.asObject(Json.parse(stdout().trim()));
    Map<String, Object> spec = Json.asObject(resource.get("spec"));
    assertEquals("hello", spec.get("message"));
    assertEquals(3, ((Number) spec.get("repeat")).intValue());
    resetOutput();

    // Once the operator-side status lands (reported here straight through the API, the P4
    // operator loop's own wire), the SAID column fills in.
    putStatus("{\"timesSaid\":3,\"observedGeneration\":1}");
    assertEquals(0, run("get", "greetings"), stderr());
    assertTrue(stdout().contains("3"), stdout());
    resetOutput();

    assertEquals(0, run("delete", "greetings", "hello-world", "--tenant", "team-a"), stderr());
    assertTrue(stdout().contains("custom.Greeting/hello-world deleted"), stdout());
    resetOutput();

    assertEquals(0, run("get", "greetings"), stderr());
    assertTrue(stdout().contains("No resources found."), stdout());
    resetOutput();

    assertEquals(0, run("delete", "kinddefinition", "custom.Greeting"), stderr());
  }

  @Test
  void an_undefined_kind_apply_surfaces_the_server_side_catalog_error() throws Exception {
    defineGreeting();
    Path instance =
        writeManifest(
            "wrong.yaml",
            """
            kind: custom.Greetng
            name: hello
            tenantId: team-a
            spec: {}
            """);
    assertEquals(2, run("apply", "-f", instance.toString()));
    assertTrue(stderr().contains("unknown kind 'custom.Greetng'"), stderr());
    assertTrue(stderr().contains("custom.Greeting"), stderr());
  }

  @Test
  void an_unresolvable_get_noun_points_at_gimle_kinds() throws Exception {
    defineGreeting();
    assertEquals(1, run("get", "salutations"));
    assertTrue(stderr().contains("unknown resource: salutations"), stderr());
    assertTrue(stderr().contains("gimle kinds"), stderr());
  }

  @Test
  void a_non_retryable_conflict_is_surfaced_immediately_not_retried_into() throws Exception {
    defineGreeting();
    Path instance = writeManifest("hello.yaml", HELLO_INSTANCE);
    assertEquals(0, run("apply", "-f", instance.toString()), stderr());
    resetOutput();

    // Tightening max below the stored instance's own value is a violator-list 409 -- a real
    // refusal, not a lost CAS race, so no amount of re-sending can help and the CLI must
    // surface it as the conflict it is.
    Path breaking =
        writeManifest(
            "greeting-kind-breaking.yaml", GREETING_DEFINITION.replace("max: 100", "max: 2"));
    assertEquals(5, run("apply", "-f", breaking.toString()));
    assertTrue(stderr().contains("conflict"), stderr());
    assertTrue(stderr().contains("hello-world"), stderr());
  }

  @Test
  void deleting_a_definition_with_instances_surfaces_the_refusal() throws Exception {
    defineGreeting();
    Path instance = writeManifest("hello.yaml", HELLO_INSTANCE);
    assertEquals(0, run("apply", "-f", instance.toString()), stderr());
    resetOutput();

    assertEquals(5, run("delete", "kinddefinition", "custom.Greeting"));
    assertTrue(stderr().contains("instance"), stderr());
  }

  /**
   * Reports a status straight through the real API -- {@code gimle-cli} deliberately has no
   * status-write verb of its own (that is the operator loop's wire, never a human's), the same
   * "real server, not mocked" shortcut {@code GimleCliTest#registerNode} uses.
   */
  private void putStatus(String statusJson) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://"
                            + serverAddress
                            + "/resources/custom.Greeting/hello-world/status?tenant=team-a"))
                .PUT(HttpRequest.BodyPublishers.ofString(statusJson))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, response.statusCode(), response.body());
  }
}
