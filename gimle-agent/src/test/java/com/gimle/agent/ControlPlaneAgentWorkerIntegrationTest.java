package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.controlplane.api.ApiServer;
import com.gimle.controlplane.fafnir.FafnirClient;
import com.gimle.controlplane.reconcile.DeploymentReconciler;
import com.gimle.controlplane.reconcile.HealthReconciler;
import com.gimle.controlplane.reconcile.ReplicaCountReconciler;
import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.core.protocol.Json;
import com.gimle.fafnir.FafnirCrypto;
import com.gimle.fafnir.FafnirServer;
import com.gimle.mimir.raft.RaftLog;
import com.gimle.mimir.raft.RaftNode;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.rpc.StoreNode;
import com.gimle.mimir.rpc.StoreTransport;
import com.gimle.mimir.store.StateStore;
import com.gimle.module.testsupport.TestModuleBuilder;
import com.gimle.testkit.Await;
import com.gimle.testkit.PortLease;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * A real control plane (state store, scheduler, all three reconcilers, HTTP API server -- the exact
 * objects {@code ControlPlaneMain} wires, just constructed directly here for test-friendly fast
 * tick/timeout parameters) driving two real {@code gimle-agent} subprocesses, each of which spawns
 * its own real {@code gimle-worker} subprocess -- extending {@code AgentWorkerIntegrationTest}'s
 * pattern one level up. Submits a {@code replicas: 2} deployment, observes both instances reach
 * {@code ACTIVE} on two distinct real agent-managed nodes, kills one agent process, and observes
 * {@link ReplicaCountReconciler} notice the resulting gap and {@link DeploymentReconciler} re-place
 * it on the surviving node. Runs natively on whatever OS executes the suite -- no Docker, no
 * Linux-only gate; "distinct nodes" here means distinct agent processes with distinct node ids,
 * which exercises every relevant code path without needing separate hardware.
 *
 * <p>Anti-affinity itself is already covered by {@code DeploymentReconcilerTest} against synthetic
 * node candidates; this test runs with it disabled so killing either agent has a single, always-
 * unblocked recovery path (both replicas legitimately land on whichever one node survives) rather
 * than the test's own outcome depending on which of the two agents happened to get killed.
 */
class ControlPlaneAgentWorkerIntegrationTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  // Leased up front (two loopback ports: node-a's and node-b's gossip binds) rather than
  // hardcoded, so this test's cluster can't collide with a concurrently-running sibling on the
  // same machine. Each port is released immediately before the agent process that will bind it
  // spawns, mirroring gimle-smoke-tests' own GreeterSmokeClusterSupport usage.
  private final PortLease portLease = PortLease.reserve(2);
  private final List<Process> agentProcesses = new ArrayList<>();
  private RaftNode storeRaftNode;
  private StoreTransport storeTransport;
  private StoreClient storeClient;
  private FafnirServer fafnirServer;
  private FafnirClient fafnirClient;
  private ApiServer apiServer;
  private Thread reconcileLoop;
  private volatile boolean stopReconcileLoop;

  @AfterEach
  void tearDown() {
    stopReconcileLoop = true;
    if (reconcileLoop != null) {
      reconcileLoop.interrupt();
    }
    for (Process process : agentProcesses) {
      killWithDescendants(process);
    }
    if (apiServer != null) {
      apiServer.close();
    }
    if (fafnirClient != null) {
      fafnirClient.close();
    }
    if (fafnirServer != null) {
      fafnirServer.close();
    }
    if (storeClient != null) {
      storeClient.close();
    }
    if (storeTransport != null) {
      storeTransport.close();
    }
    if (storeRaftNode != null) {
      storeRaftNode.close();
    }
    portLease.close();
  }

  /**
   * {@link Process#destroyForcibly()} only kills the process itself, not the worker JVM it spawned
   * as its own child -- an orphaned worker would otherwise keep running (and keep its module
   * ACTIVE) independently of the agent that was supposed to be supervising it. Killing descendants
   * first mirrors what actually happens on a real crashed machine (everything on it stops), which
   * is the scenario this test means to simulate.
   */
  private static void killWithDescendants(Process process) {
    process.descendants().forEach(ProcessHandle::destroy);
    process.destroyForcibly();
  }

  @Test
  @Timeout(value = 3, unit = TimeUnit.MINUTES)
  void control_plane_places_replicas_on_real_agents_and_reschedules_after_an_agent_is_killed()
      throws Exception {
    Path jar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.controlplaneit {
                }
                """)
            .withDescriptor(
                TestModuleBuilder.minimalDescriptor("com.gimle.fixture.controlplaneit", "1.0.0"))
            .build(tempDir, "fixture.jar");

    StateStore store = new StateStore(tempDir.resolve("cp-state"));
    Scheduler scheduler = new Scheduler();
    DeploymentReconciler deploymentReconciler = new DeploymentReconciler(store, scheduler);
    // nodeDarkTimeout must comfortably exceed the agent's own 5s heartbeat cadence (15s covers 3
    // missed 5s heartbeats), or a perfectly healthy node between two of its own
    // heartbeats would look dark. placementGracePeriod covers the agent's own poll-then-start
    // latency (up to one 5s poll tick, plus real JVM/install/resolve/start time) before a
    // just-created assignment the node hasn't reported yet is treated as failed-to-start.
    ReplicaCountReconciler replicaCountReconciler =
        new ReplicaCountReconciler(store, Duration.ofSeconds(17), Duration.ofSeconds(20));
    HealthReconciler healthReconciler = new HealthReconciler(store);

    // A single-node gimle-mimir store, in-process, wrapping this same StateStore over a real
    // loopback socket -- ApiServer no longer holds a StateStore directly, now that the store
    // lives in its own process and is reached over the network; the reconcilers above still
    // read/write it directly, same as always.
    RaftLog storeRaftLog = new RaftLog(tempDir.resolve("cp-raft"));
    storeRaftNode = new RaftNode("self", Map.of(), storeRaftLog, store);
    storeRaftNode.start();
    StoreNode storeNode = new StoreNode(storeRaftNode, store, Map.of());
    storeTransport = new StoreTransport(storeNode);
    SocketAddress storeAddress = storeTransport.listen(new InetSocketAddress("127.0.0.1", 0));
    storeClient = new StoreClient(List.of(storeAddress));

    // A real, in-process Fafnir replica -- ApiServer no longer performs crypto in-process, now
    // that secret crypto lives in its own process, so standing up ApiServer at all now needs a
    // genuine FafnirClient to talk to.
    FafnirCrypto fafnirCrypto = new FafnirCrypto(storeClient, tempDir.resolve("keys/secret.key"));
    fafnirServer = new FafnirServer(fafnirCrypto, 0);
    fafnirServer.start();
    fafnirClient = new FafnirClient("localhost:" + fafnirServer.port());

    apiServer = new ApiServer(storeClient, 0, fafnirClient);
    apiServer.start();
    String baseUrl = "http://localhost:" + apiServer.port();

    reconcileLoop =
        Thread.ofVirtual()
            .name("test-reconcile-loop")
            .start(
                () -> {
                  while (!stopReconcileLoop) {
                    replicaCountReconciler.reconcileOnce();
                    healthReconciler.reconcileOnce();
                    deploymentReconciler.reconcileOnce();
                    try {
                      Thread.sleep(300);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                    }
                  }
                });

    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    List<Integer> gossipPorts = portLease.ports();
    int gossipPortA = gossipPorts.get(0);
    int gossipPortB = gossipPorts.get(1);
    String gossipAddressA = "127.0.0.1:" + gossipPortA;
    String gossipAddressB = "127.0.0.1:" + gossipPortB;

    portLease.release(gossipPortA);
    agentProcesses.add(
        spawnAgent(
            javaExecutable,
            classpath,
            "node-a",
            baseUrl,
            gossipAddressA,
            "-",
            tempDir.resolve("node-a.log")));
    portLease.release(gossipPortB);
    agentProcesses.add(
        spawnAgent(
            javaExecutable,
            classpath,
            "node-b",
            baseUrl,
            gossipAddressB,
            gossipAddressA,
            tempDir.resolve("node-b.log")));

    HttpClient httpClient = HttpClient.newHttpClient();
    submitDeployment(httpClient, baseUrl, "fixture-deployment", jar, 2);

    Await.until(
        () -> activeInstanceCountQuietly(httpClient, baseUrl, "fixture-deployment") >= 2,
        Duration.ofSeconds(90),
        "both replicas should reach ACTIVE");

    Map<String, Object> statusBeforeKill =
        deploymentStatus(httpClient, baseUrl, "fixture-deployment");
    List<Map<String, Object>> instancesBeforeKill = instancesOf(statusBeforeKill);
    String killedNodeId = (String) instancesBeforeKill.get(0).get("nodeId");
    String survivingNodeId = killedNodeId.equals("node-a") ? "node-b" : "node-a";

    Process toKill = killedNodeId.equals("node-a") ? agentProcesses.get(0) : agentProcesses.get(1);
    killWithDescendants(toKill);
    toKill.waitFor();

    // Checking count alone would trivially pass on stale pre-kill state: the store only reflects
    // the kill once ReplicaCountReconciler's dark-timeout/grace-period elapses and re-places the
    // orphaned instance, so the real assertion is "both ACTIVE *and* neither still on the node
    // whose agent just died."
    Await.until(
        () -> allActiveAndOnNode(httpClient, baseUrl, "fixture-deployment", 2, survivingNodeId),
        Duration.ofSeconds(90),
        "both replicas should recover to ACTIVE on the surviving node after the agent hosting one of them is killed");
  }

  private static Process spawnAgent(
      String javaExecutable,
      String classpath,
      String nodeId,
      String baseUrl,
      String gossipBindHostPort,
      String seeds,
      Path logFile)
      throws IOException {
    ProcessBuilder pb =
        new ProcessBuilder(
            javaExecutable,
            "-cp",
            classpath,
            "com.gimle.agent.AgentMain",
            nodeId,
            baseUrl,
            gossipBindHostPort,
            seeds,
            javaExecutable,
            "-cp",
            classpath,
            "com.gimle.worker.WorkerMain");
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    return pb.start();
  }

  private static void submitDeployment(
      HttpClient httpClient, String baseUrl, String name, Path jar, int replicas) throws Exception {
    String manifest =
        """
        kind: Deployment
        name: %s
        module:
          name: com.gimle.fixture.controlplaneit
          version: 1.0.0
        artifactPath: %s
        replicas: %d
        """
            .formatted(name, jar.toAbsolutePath().toString(), replicas);
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name))
                .PUT(HttpRequest.BodyPublishers.ofString(manifest, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      fail("deployment submission failed: " + response.statusCode() + " " + response.body());
    }
  }

  private static Map<String, Object> deploymentStatus(
      HttpClient httpClient, String baseUrl, String name) throws Exception {
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name)).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      return Map.of("instances", List.of());
    }
    return Json.asObject(Json.parse(response.body()));
  }

  private static List<Map<String, Object>> instancesOf(Map<String, Object> status) {
    return Json.asObjectList(status.get("instances"));
  }

  private static long activeInstanceCount(HttpClient httpClient, String baseUrl, String name)
      throws Exception {
    List<Map<String, Object>> instances = instancesOf(deploymentStatus(httpClient, baseUrl, name));
    long count = 0;
    for (Map<String, Object> instance : instances) {
      Object observation = instance.get("observation");
      if (observation instanceof Map<?, ?> obsMap
          && "ACTIVE".equals(obsMap.get("lifecycleState"))) {
        count++;
      }
    }
    return count;
  }

  /**
   * A {@code BooleanSupplier} can't declare checked exceptions; a transient HTTP hiccup mid-poll
   * should keep {@link Await#until} retrying, not fail the test outright.
   */
  private static long activeInstanceCountQuietly(
      HttpClient httpClient, String baseUrl, String name) {
    try {
      return activeInstanceCount(httpClient, baseUrl, name);
    } catch (Exception e) {
      return 0;
    }
  }

  /**
   * True only once exactly {@code expectedCount} instances are reported, every one is {@code
   * ACTIVE}, and every one is on {@code expectedNodeId} -- the stronger condition {@link
   * #activeInstanceCountQuietly} alone can't express, needed so a stale pre-kill snapshot (still
   * showing the dead node) can't satisfy this check.
   */
  private static boolean allActiveAndOnNode(
      HttpClient httpClient,
      String baseUrl,
      String name,
      int expectedCount,
      String expectedNodeId) {
    try {
      List<Map<String, Object>> instances =
          instancesOf(deploymentStatus(httpClient, baseUrl, name));
      if (instances.size() != expectedCount) {
        return false;
      }
      for (Map<String, Object> instance : instances) {
        if (!expectedNodeId.equals(instance.get("nodeId"))) {
          return false;
        }
        Object observation = instance.get("observation");
        if (!(observation instanceof Map<?, ?> obsMap)
            || !"ACTIVE".equals(obsMap.get("lifecycleState"))) {
          return false;
        }
      }
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static String javaExecutable() {
    Optional<String> command = ProcessHandle.current().info().command();
    if (command.isPresent()) {
      return command.get();
    }
    Path javaBin = Path.of(System.getProperty("java.home"), "bin");
    for (String candidate : List.of("java", "java.exe")) {
      Path path = javaBin.resolve(candidate);
      if (Files.isRegularFile(path)) {
        return path.toString();
      }
    }
    throw new IllegalStateException("could not locate the java launcher under " + javaBin);
  }
}
