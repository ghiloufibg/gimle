package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * FUNC-02 regression coverage, end to end through {@link GimleCli#run}: {@code SecretMapCommand}'s
 * batch verbs (set/replace/seal/rollback) used to print each key's own outcome but only ever check
 * {@code client.expectSuccess}'s HTTP status, which Fafnir's own {@code SecretMapStore}-backed
 * handlers always returned as 200 -- so a CI script gating on exit status (the whole point of
 * {@code SealCommand}'s own offline-sealing workflow) never saw a batch that failed every single
 * key. Same in-process store/Fafnir/{@link ApiServer} wiring {@code DeploymentsCommandTest} already
 * establishes.
 *
 * <p>Every server started here reads {@code gimle.transport.protocol} (and the TLS paths that go
 * with it) from JVM-global system properties, so this class must not run alongside a class that
 * mutates them -- read access is enough, since nothing here writes any.
 */
@ResourceLock(value = Resources.SYSTEM_PROPERTIES, mode = ResourceAccessMode.READ)
class SecretMapCommandTest {

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
            "10",
            "--server",
            serverAddress),
        errBuffer::toString);
  }

  @Test
  void secretmap_set_with_every_key_valid_exits_zero() {
    createTenant("acme");

    int exitCode =
        run(
            "secretmap",
            "set",
            "acme",
            "db-creds",
            "--from-literal",
            "username=admin",
            "--server",
            serverAddress);

    assertEquals(0, exitCode, errBuffer::toString);
  }

  @Test
  void secretmap_set_with_one_invalid_key_exits_nonzero_after_printing_every_keys_own_result() {
    // ':' is reserved by SecretMapCodec's own raw-key convention, so this key fails to write while
    // "username" in the same batch succeeds -- exactly the mixed-outcome batch FUNC-02 describes.
    createTenant("acme");

    int exitCode =
        run(
            "secretmap",
            "set",
            "acme",
            "db-creds",
            "--from-literal",
            "username=admin",
            "--from-literal",
            "bad:key=hunter2",
            "--server",
            serverAddress);

    assertNotEquals(0, exitCode);
    String out = outBuffer.toString(StandardCharsets.UTF_8);
    // The operator still sees exactly which key failed and which succeeded, printed before the
    // process exits nonzero -- the exit code alone isn't enough to act on a partial failure.
    assertTrue(out.contains("username"), out);
    assertTrue(out.contains("bad:key"), out);
  }

  @Test
  void secretmap_replace_with_one_invalid_key_exits_nonzero() {
    // replace shares the identical failIfAnyKeyErrored check set already exercises -- covered
    // separately since it parses a differently-shaped request body (full replace, not a merge).
    createTenant("acme");

    int exitCode =
        run(
            "secretmap",
            "replace",
            "acme",
            "db-creds",
            "--from-literal",
            "bad:key=hunter2",
            "--server",
            serverAddress);

    assertNotEquals(0, exitCode);
    assertTrue(outBuffer.toString(StandardCharsets.UTF_8).contains("bad:key"));
  }
}
