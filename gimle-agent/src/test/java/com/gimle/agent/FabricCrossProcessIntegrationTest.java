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
 * Two real {@code gimle-agent} subprocesses on distinct simulated nodes (distinct node ids,
 * distinct gossip ports, same machine -- same "distinct nodes means distinct registered identities"
 * reasoning {@code ControlPlaneAgentWorkerIntegrationTest} already established), each spawning its
 * own real {@code gimle-worker} subprocess, with a real cross-process fabric call: a module on one
 * node exports a service, a module on the other looks it up and calls it over a real cross-machine
 * TCP hop, then the exporting worker's whole node is killed and the consumer is expected to observe
 * a failure rather than hang.
 *
 * <p>Placement is steered without any explicit node-targeting mechanism (none exists yet): the
 * provider deployment is submitted while only node-a is registered, then node-b starts and the
 * consumer deployment is submitted -- {@link Scheduler}'s first-fit-decreasing-by-free-capacity
 * placement deterministically prefers node-b at that point, since node-a's free capacity is now
 * (however slightly) below node-b's untouched capacity.
 *
 * <p>The consumer's lifecycle hook runs inside its own worker subprocess, so the only way this test
 * can observe what happened is a file it writes to, named by a system property passed on that
 * worker's own command line -- see {@link FabricGreeterConsumerHooks}.
 */
class FabricCrossProcessIntegrationTest {

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

  private static void killWithDescendants(Process process) {
    process.descendants().forEach(ProcessHandle::destroy);
    process.destroyForcibly();
  }

  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void
      a_consumer_on_one_node_calls_a_provider_on_another_over_a_real_fabric_hop_then_observes_its_failure()
          throws Exception {
    Path outputFile = tempDir.resolve("consumer-output.txt");

    Path providerJar =
        buildFixture(
            tempDir,
            "com.gimle.fixture.fabricprovider",
            "com.gimle.agent.FabricGreeterProviderHooks",
            true);
    Path consumerJar =
        buildFixture(
            tempDir,
            "com.gimle.fixture.fabricconsumer",
            "com.gimle.agent.FabricGreeterConsumerHooks",
            false);

    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    DeploymentReconciler deploymentReconciler = new DeploymentReconciler(store, scheduler);
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
    HttpClient httpClient = HttpClient.newHttpClient();

    List<Integer> gossipPorts = portLease.ports();
    int gossipPortA = gossipPorts.get(0);
    int gossipPortB = gossipPorts.get(1);
    String gossipAddressA = "127.0.0.1:" + gossipPortA;
    String gossipAddressB = "127.0.0.1:" + gossipPortB;

    // node-a hosts the provider; started and placed first, alone, so it's the only candidate.
    portLease.release(gossipPortA);
    agentProcesses.add(
        spawnAgent(
            javaExecutable,
            classpath,
            "node-a",
            baseUrl,
            gossipAddressA,
            "-",
            List.of(),
            tempDir.resolve("node-a.log")));
    submitDeployment(httpClient, baseUrl, "fabric-provider", providerJar, 1);
    Await.until(
        () -> activeInstanceCountQuietly(httpClient, baseUrl, "fabric-provider") >= 1,
        Duration.ofSeconds(60),
        "provider should reach ACTIVE");

    // node-b hosts the consumer; by the time it's registered, node-a's free capacity is already
    // (slightly) reduced by the provider instance, so the scheduler deterministically prefers
    // node-b -- untouched -- for this second deployment.
    portLease.release(gossipPortB);
    agentProcesses.add(
        spawnAgent(
            javaExecutable,
            classpath,
            "node-b",
            baseUrl,
            gossipAddressB,
            gossipAddressA,
            List.of("-Dgimle.test.outputFile=" + outputFile.toAbsolutePath()),
            tempDir.resolve("node-b.log")));
    // Wait for node-b to actually be a placement candidate (registered *and* heartbeated) before
    // submitting the consumer -- otherwise the reconcile loop's very next tick would place it on
    // node-a (still the only candidate) well before node-b's JVM has even finished starting up.
    Await.until(
        () -> store.getNodeHeartbeat("node-b").isPresent(),
        Duration.ofSeconds(30),
        "node-b should have registered and heartbeated");
    submitDeployment(httpClient, baseUrl, "fabric-consumer", consumerJar, 1);

    // Deliberately not awaiting "ACTIVE" here: the consumer's onStart hook itself blocks for its
    // whole lookup-retry-then-call-loop duration (gating-hook semantics -- onStart runs
    // synchronously as part of the STARTING -> ACTIVE transition), so ACTIVE is only reported
    // once that entire routine returns. Poll the output file directly instead.
    Await.until(
        () -> outputContainsQuietly(outputFile, "OK:hello world from provider"),
        Duration.ofSeconds(90),
        "consumer should successfully call the provider over a real cross-machine fabric hop; log:"
            + " "
            + logTailQuietly(tempDir.resolve("node-b.log")));

    // Kill node-a (agent + its worker subprocess) entirely and expect the consumer's subsequent
    // calls to start failing over -- never hanging -- once the catalog/breaker notice.
    killWithDescendants(agentProcesses.get(0));

    Await.until(
        () -> outputHasAFailureAfterASuccessQuietly(outputFile),
        Duration.ofSeconds(45),
        "consumer should observe a failure once the provider's node is killed; output so far: "
            + readQuietly(outputFile));
  }

  private static Path buildFixture(
      Path tempDir, String moduleName, String hooksClassName, boolean exportsGreeter) {
    String exportsSection =
        exportsGreeter
            ? """
              exports:
                - service: com.gimle.agent.FabricGreeter
                  version: 1.0.0
              """
            : "";
    String descriptor =
        """
        name: %s
        version: 1.0.0
        isolation:
          tier: TIER_1
        resources:
          request:
            memory: 16Mi
            cpu: 10m
          limit:
            memory: 32Mi
            cpu: 50m
        lifecycle:
          hooks: %s
        %s"""
            .formatted(moduleName, hooksClassName, exportsSection);
    return TestModuleBuilder.module(
            """
            module %s {
            }
            """
                .formatted(moduleName))
        .withDescriptor(descriptor)
        .build(tempDir, moduleName + ".jar");
  }

  private static Process spawnAgent(
      String javaExecutable,
      String classpath,
      String nodeId,
      String baseUrl,
      String gossipBindHostPort,
      String seeds,
      List<String> extraWorkerJvmArgs,
      Path logFile)
      throws IOException {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable);
    command.add("-cp");
    command.add(classpath);
    command.add("com.gimle.agent.AgentMain");
    command.add(nodeId);
    command.add(baseUrl);
    command.add(gossipBindHostPort);
    command.add(seeds);
    command.add(javaExecutable);
    command.addAll(extraWorkerJvmArgs);
    command.add("-cp");
    command.add(classpath);
    command.add("com.gimle.worker.WorkerMain");

    ProcessBuilder pb = new ProcessBuilder(command);
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
          name: %s
          version: 1.0.0
        artifactPath: %s
        replicas: %d
        """
            .formatted(
                name,
                jar.getFileName().toString().replace(".jar", ""),
                jar.toAbsolutePath(),
                replicas);
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

  private static long activeInstanceCountQuietly(
      HttpClient httpClient, String baseUrl, String name) {
    try {
      List<Map<String, Object>> instances =
          Json.asObjectList(deploymentStatus(httpClient, baseUrl, name).get("instances"));
      long count = 0;
      for (Map<String, Object> instance : instances) {
        Object observation = instance.get("observation");
        if (observation instanceof Map<?, ?> obsMap
            && "ACTIVE".equals(obsMap.get("lifecycleState"))) {
          count++;
        }
      }
      return count;
    } catch (Exception e) {
      return 0;
    }
  }

  private static boolean outputContainsQuietly(Path file, String needle) {
    try {
      return Files.exists(file) && Files.readString(file).contains(needle);
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean outputHasAFailureAfterASuccessQuietly(Path file) {
    try {
      if (!Files.exists(file)) {
        return false;
      }
      List<String> lines = Files.readAllLines(file);
      int firstOk = -1;
      for (int i = 0; i < lines.size(); i++) {
        if (firstOk < 0 && lines.get(i).startsWith("OK:")) {
          firstOk = i;
        } else if (firstOk >= 0 && lines.get(i).startsWith("FAIL:")) {
          return true;
        }
      }
      return false;
    } catch (IOException e) {
      return false;
    }
  }

  private static String readQuietly(Path file) {
    try {
      return Files.exists(file) ? Files.readString(file) : "(no output file yet)";
    } catch (IOException e) {
      return "(failed to read: " + e.getMessage() + ")";
    }
  }

  private static String logTailQuietly(Path file) {
    try {
      if (!Files.exists(file)) {
        return "(no log yet)";
      }
      List<String> lines = Files.readAllLines(file);
      int from = Math.max(0, lines.size() - 20);
      return String.join("\n", lines.subList(from, lines.size()));
    } catch (IOException e) {
      return "(failed to read log: " + e.getMessage() + ")";
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
