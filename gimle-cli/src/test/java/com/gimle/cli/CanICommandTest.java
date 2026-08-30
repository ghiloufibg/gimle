package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.api.ApiServer;
import com.gimle.controlplane.fafnir.FafnirClient;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code gimle can-i} against a real in-process {@link ApiServer} -- the same wiring {@link
 * GimleCliTest} establishes. Plaintext mode answers yes for everything (nothing is actually gated
 * in that mode), which is exactly what these tests assert alongside the client-side verb/resource
 * validation that never reaches the server at all.
 */
class CanICommandTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private StateStore store;
  private RaftNode storeRaftNode;
  private StoreTransport storeTransport;
  private StoreClient storeClient;
  private FafnirServer fafnirServer;
  private FafnirClient fafnirClient;
  private ApiServer server;
  private String serverAddress;
  private ByteArrayOutputStream outBuffer;
  private ByteArrayOutputStream errBuffer;

  @BeforeEach
  void startServer() throws IOException {
    store = new StateStore();
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
    return GimleCli.run(args, new PrintStream(outBuffer), new PrintStream(errBuffer));
  }

  @Test
  void answers_yes_in_plaintext_mode_and_accepts_the_cli_noun_spellings() {
    assertEquals(
        0,
        run("can-i", "write", "deployments", "--tenant", "acme", "--server", serverAddress),
        errBuffer::toString);
    assertEquals("yes", outBuffer.toString(StandardCharsets.UTF_8).strip());

    outBuffer.reset();
    assertEquals(
        0, run("can-i", "read", "network-policy", "--server", serverAddress), errBuffer::toString);
    assertEquals("yes", outBuffer.toString(StandardCharsets.UTF_8).strip());

    // The run-together spellings the CLI's own verbs use (gimle delete kinddefinition,
    // set rolebinding) must spell valid questions here too, singular and plural alike.
    outBuffer.reset();
    assertEquals(
        0,
        run("can-i", "write", "kinddefinitions", "--server", serverAddress),
        errBuffer::toString);
    assertEquals("yes", outBuffer.toString(StandardCharsets.UTF_8).strip());

    outBuffer.reset();
    assertEquals(
        0, run("can-i", "delete", "rolebinding", "--server", serverAddress), errBuffer::toString);
    assertEquals("yes", outBuffer.toString(StandardCharsets.UTF_8).strip());
  }

  @Test
  void json_output_carries_the_full_review_including_the_allowed_flag() {
    assertEquals(
        0,
        run("can-i", "delete", "SERVICE", "-o", "json", "--server", serverAddress),
        errBuffer::toString);
    String out = outBuffer.toString(StandardCharsets.UTF_8);
    assertTrue(out.contains("\"allowed\":true"), out);
    assertTrue(out.contains("\"resource\":\"SERVICE\""), out);
    assertTrue(out.contains("\"verb\":\"DELETE\""), out);
  }

  @Test
  void an_unknown_verb_or_resource_is_rejected_before_any_request_is_sent() {
    assertEquals(1, run("can-i", "frobnicate", "deployment", "--server", serverAddress));
    assertTrue(errBuffer.toString(StandardCharsets.UTF_8).contains("unknown verb"));

    errBuffer.reset();
    assertEquals(1, run("can-i", "read", "flux-capacitors", "--server", serverAddress));
    assertTrue(errBuffer.toString(StandardCharsets.UTF_8).contains("unknown resource"));
  }
}
