package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.api.ApiServer;
import com.gimle.controlplane.fafnir.FafnirClient;
import com.gimle.controlplane.reconcile.LimitRangeReconciler;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.fafnir.FafnirCrypto;
import com.gimle.fafnir.FafnirServer;
import com.gimle.mimir.raft.RaftLog;
import com.gimle.mimir.raft.RaftNode;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.rpc.StoreNode;
import com.gimle.mimir.rpc.StoreTransport;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.StateStore;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
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
 * {@code gimle get deployments}' table "health" column, end to end over a real cluster -- the
 * regression coverage for the bug a manual user-perspective pass surfaced: {@code
 * DeploymentsCommand#healthOf} only ever read {@code quotaViolating}, so a deployment the API
 * genuinely reports as {@code limitRangeViolating} still rendered as {@code HEALTHY} here (the
 * console's Deployments list had the identical blind spot, fixed alongside this one). Same
 * in-process store/Fafnir/{@link ApiServer} wiring {@link GimleCliTest} already establishes.
 */
class DeploymentsCommandTest {

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

  /** {@code TestModuleBuilder.minimalDescriptor} fixes this at 16Mi memory / 10m cpu request. */
  private Path buildFixtureJar(Path dir) {
    return TestModuleBuilder.module("module com.gimle.fixture.limitrange {\n}\n")
        .withDescriptor(
            TestModuleBuilder.minimalDescriptor("com.gimle.fixture.limitrange", "1.0.0"))
        .build(dir, "fixture.jar");
  }

  @Test
  void the_health_column_reports_limitrange_once_the_reconciler_flags_a_violation()
      throws Exception {
    Path jar = buildFixtureJar(tempDir.resolve("jars"));
    Path manifest = tempDir.resolve("deployment.yaml");
    Files.writeString(
        manifest,
        """
        kind: Deployment
        name: orders
        module:
          name: com.gimle.fixture.limitrange
          version: 1.0.0
        artifactPath: %s
        replicas: 1
        tenantId: acme
        """
            .formatted(jar.toAbsolutePath()));

    assertEquals(
        0,
        run(
            "set",
            "tenant",
            "acme",
            "--max-memory-bytes",
            "1000000000",
            "--max-cpu-millicores",
            "4000",
            "--max-instances",
            "10",
            "--server",
            serverAddress),
        errBuffer::toString);
    assertEquals(
        0, run("apply", "-f", manifest.toString(), "--server", serverAddress), errBuffer::toString);
    assertEquals(
        0,
        run(
            "set",
            "limitrange",
            "acme",
            "--min-request-memory",
            "32Mi", // above the fixture's own 16Mi request -- deliberately violated
            "--min-request-cpu",
            "20m",
            "--server",
            serverAddress),
        errBuffer::toString);

    // The reconciler is what actually flags the violation -- table output before it ever runs
    // must not already claim LIMITRANGE (no node agent is running in this test, so the instance
    // stays UNPLACED regardless -- that column is unaffected by, and orthogonal to, this fix).
    outBuffer.reset();
    assertEquals(0, run("get", "deployments", "--server", serverAddress));
    assertFalse(outBuffer.toString(StandardCharsets.UTF_8).contains("LIMITRANGE"));

    new LimitRangeReconciler(store).reconcileOnce();

    outBuffer.reset();
    assertEquals(0, run("get", "deployments", "--server", serverAddress));
    String out = outBuffer.toString(StandardCharsets.UTF_8);
    assertTrue(out.contains("LIMITRANGE"), out);
  }

  /**
   * Regression coverage for the health column's other blind spot: an instance whose agent-reported
   * {@code lifecycleState} is {@code FAILED} but whose {@code alive} flag still reads {@code true}
   * (the same shape {@link com.gimle.controlplane.reconcile.HealthReconciler#isHealthy} already
   * treats as unhealthy) rendered as {@code HEALTHY} here before {@code unhealthyInstanceCount}
   * also checked {@code lifecycleState}.
   */
  @Test
  void the_health_column_reports_unhealthy_for_a_failed_but_still_alive_instance()
      throws Exception {
    Path jar = buildFixtureJar(tempDir.resolve("jars2"));
    Path manifest = tempDir.resolve("deployment2.yaml");
    Files.writeString(
        manifest,
        """
        kind: Deployment
        name: billing
        module:
          name: com.gimle.fixture.limitrange
          version: 1.0.0
        artifactPath: %s
        replicas: 1
        """
            .formatted(jar.toAbsolutePath()));

    assertEquals(
        0, run("apply", "-f", manifest.toString(), "--server", serverAddress), errBuffer::toString);

    store.putAssignment(new InstanceAssignment("billing", 0, "node-a"));
    ModuleId moduleId = new ModuleId("com.gimle.fixture.limitrange", Version.parse("1.0.0"));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            "node-a",
            new ResourceUsageSnapshot(1000L, 0, 1000, 0),
            List.of(
                new InstanceObservation(
                    "billing", 0, moduleId, "FAILED", true, false, 0.0, 0, 0L, 0L, 0.0))));

    outBuffer.reset();
    assertEquals(0, run("get", "deployments", "--server", serverAddress));
    String out = outBuffer.toString(StandardCharsets.UTF_8);
    assertTrue(out.contains("UNHEALTHY(1)"), out);
  }
}
