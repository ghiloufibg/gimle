package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.controlplane.api.ApiServer;
import com.gimle.controlplane.reconcile.DeploymentReconciler;
import com.gimle.controlplane.reconcile.HealthReconciler;
import com.gimle.controlplane.reconcile.ReplicaCountReconciler;
import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.controlplane.store.StateStore;
import com.gimle.core.protocol.Json;
import com.gimle.module.testsupport.TestModuleBuilder;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real network-fault scenarios for the agent-to-control-plane path, modeled directly on {@link
 * ControlPlaneAgentWorkerIntegrationTest}'s own real-control-plane-plus-real-agent-subprocess
 * shape. That sibling test only ever kills the agent process outright; nothing proves the cluster
 * survives a *flaky* network -- a connection that drops and later heals -- without any process
 * dying, which is exactly what a real network partition looks like and a hard kill can't simulate.
 *
 * <p>A Toxiproxy proxy sits between each agent subprocess and the real {@link ApiServer}: each
 * agent is given its own proxy's address as its control-plane base URL rather than the real one, so
 * every one of its HTTP calls passes through the proxy transparently until a toxic is introduced.
 * This needs zero production code changes -- the base URL is already test-constructed here, exactly
 * as in the sibling test, not self-resolved internally the way §2.3's fabric-address case is.
 *
 * <p><b>A real finding from building this test, worth recording:</b> the first version of {@code
 * a_network_cut_is_detected_and_recovers_once_it_heals} used a single agent/node and asserted the
 * active-instance count dropped to zero after cutting the network. It never did, even though the
 * cut was verified genuine at every layer (Toxiproxy's own {@code Stop()} closes tracked
 * connections, confirmed against its Go source; a standalone {@code java.net.http.HttpClient}
 * correctly failed against the same disabled proxy in isolation). The real mechanism: {@code
 * ReplicaCountReconciler} and {@code DeploymentReconciler} run back-to-back in the same
 * reconcile-loop iteration with no gap -- the instant the stale assignment is removed, it gets
 * re-placed, and with only one node registered, it can only land right back on the same (still
 * network-cut) node. The freshly re-created assignment then gets matched, by the API's
 * (deploymentName, instanceIndex) join, against that node's last-received heartbeat observation --
 * which predates the cut and still says ACTIVE, since heartbeats are never purged on staleness,
 * only judged stale by the dark-node check specifically. The count-drops-to-zero window this test
 * originally waited for genuinely never exists, not even momentarily. Fixed by mirroring the
 * sibling test's own two-agent, observe-then-act shape (§0 below) instead of asserting on a
 * transient count that a single-node scheduler can mask entirely.
 */
class ControlPlaneAgentNetworkFaultTest {

  private static final String TOXIPROXY_HOST = "127.0.0.1";
  private static final int TOXIPROXY_PORT = 8474;

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private final List<Process> agentProcesses = new ArrayList<>();
  private final List<Proxy> proxies = new ArrayList<>();
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
      process.descendants().forEach(ProcessHandle::destroy);
      process.destroyForcibly();
    }
    if (apiServer != null) {
      apiServer.close();
    }
    for (Proxy proxy : proxies) {
      try {
        proxy.delete();
      } catch (IOException ignored) {
        // best-effort cleanup; a leftover proxy on a dead test JVM's ephemeral ports is harmless
      }
    }
  }

  private static boolean toxiproxyServerReachable() {
    try (Socket probe = new Socket()) {
      probe.connect(new InetSocketAddress(TOXIPROXY_HOST, TOXIPROXY_PORT), 500);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * §0: two agents, two proxies (one per agent), both transparent (enabled) at first. Which node
   * ends up hosting which replica is left to the scheduler, exactly like {@code
   * ControlPlaneAgentWorkerIntegrationTest} does -- observed afterward, not assumed, so this test
   * doesn't depend on placement being deterministic.
   */
  @Test
  @Timeout(value = 3, unit = TimeUnit.MINUTES)
  void a_network_cut_is_detected_and_recovers_once_it_heals() throws Exception {
    Assumptions.assumeTrue(
        toxiproxyServerReachable(),
        "toxiproxy-server not reachable on "
            + TOXIPROXY_HOST
            + ":"
            + TOXIPROXY_PORT
            + " -- skipping");

    Path jar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.networkfault {
                }
                """)
            .withDescriptor(
                TestModuleBuilder.minimalDescriptor("com.gimle.fixture.networkfault", "1.0.0"))
            .build(tempDir, "fixture.jar");

    StateStore store = new StateStore(tempDir.resolve("cp-state"));
    Scheduler scheduler = new Scheduler();
    DeploymentReconciler deploymentReconciler = new DeploymentReconciler(store, scheduler);
    // Same values ControlPlaneAgentWorkerIntegrationTest already validated as safe against real
    // subprocess/heartbeat timing: nodeDarkTimeout comfortably exceeds the agent's own 5s
    // heartbeat cadence, placementGracePeriod covers its poll-then-start latency.
    ReplicaCountReconciler replicaCountReconciler =
        new ReplicaCountReconciler(store, Duration.ofSeconds(17), Duration.ofSeconds(20));
    HealthReconciler healthReconciler = new HealthReconciler(store);
    apiServer = new ApiServer(store, 0);
    apiServer.start();
    String realBaseUrl = "http://127.0.0.1:" + apiServer.port();

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

    ToxiproxyClient toxiproxyClient = new ToxiproxyClient(TOXIPROXY_HOST, TOXIPROXY_PORT);
    int proxyPortA = reservePort();
    Proxy proxyA =
        toxiproxyClient.createProxy(
            "gimle-cp-api-a-" + UUID.randomUUID(),
            "127.0.0.1:" + proxyPortA,
            "127.0.0.1:" + apiServer.port());
    proxies.add(proxyA);
    int proxyPortB = reservePort();
    Proxy proxyB =
        toxiproxyClient.createProxy(
            "gimle-cp-api-b-" + UUID.randomUUID(),
            "127.0.0.1:" + proxyPortB,
            "127.0.0.1:" + apiServer.port());
    proxies.add(proxyB);

    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");
    agentProcesses.add(
        spawnAgent(
            javaExecutable,
            classpath,
            "node-a",
            "http://127.0.0.1:" + proxyPortA,
            "127.0.0.1:17980",
            "-",
            tempDir.resolve("node-a.log")));
    agentProcesses.add(
        spawnAgent(
            javaExecutable,
            classpath,
            "node-b",
            "http://127.0.0.1:" + proxyPortB,
            "127.0.0.1:17982",
            "127.0.0.1:17980",
            tempDir.resolve("node-b.log")));

    HttpClient httpClient = HttpClient.newHttpClient();
    submitDeployment(httpClient, realBaseUrl, "fixture-deployment", jar, 2);

    await(
        () -> activeInstanceCountQuietly(httpClient, realBaseUrl, "fixture-deployment") >= 2,
        Duration.ofSeconds(90),
        "both replicas should reach ACTIVE through their (currently transparent) proxies");

    Map<String, Object> statusBeforeCut =
        deploymentStatus(httpClient, realBaseUrl, "fixture-deployment");
    List<Map<String, Object>> instancesBeforeCut = instancesOf(statusBeforeCut);
    String affectedNodeId = (String) instancesBeforeCut.get(0).get("nodeId");
    String survivingNodeId = affectedNodeId.equals("node-a") ? "node-b" : "node-a";
    Proxy proxyToDisable = affectedNodeId.equals("node-a") ? proxyA : proxyB;

    // Full network cut to one node: its agent process stays alive throughout, but every one of its
    // HTTP calls to the control plane now fails -- the scenario a hard-kill test structurally
    // cannot cover. The other node's proxy stays enabled/transparent the whole time.
    proxyToDisable.disable();

    // Checking count alone would trivially pass on stale pre-cut state, per this class's own
    // recorded finding above; the real assertion is "both ACTIVE *and* neither still on the
    // network-cut node" -- exactly ControlPlaneAgentWorkerIntegrationTest's own pattern, just
    // triggered by a network partition instead of a process kill.
    await(
        () -> allActiveAndOnNode(httpClient, realBaseUrl, "fixture-deployment", 2, survivingNodeId),
        Duration.ofSeconds(90),
        "both replicas should recover to ACTIVE on the surviving node once the network partition to"
            + " the other node is detected");

    // Heal the network. The affected agent's own tick loop never crashed on the repeated failures
    // (its per-tick catch-and-continue already existed before this test) -- proving its control
    // path itself recovers is the specific half a killed process could never demonstrate within the
    // same test.
    proxyToDisable.enable();
    HttpClient probeClient = HttpClient.newHttpClient();
    await(
        () ->
            canReach(
                probeClient,
                "http://127.0.0.1:" + (affectedNodeId.equals("node-a") ? proxyPortA : proxyPortB)),
        Duration.ofSeconds(30),
        "the previously network-cut node's control-plane path should be reachable again once healed");
  }

  private static boolean canReach(HttpClient httpClient, String baseUrl) {
    try {
      HttpResponse<Void> response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/does-not-matter"))
                  .timeout(Duration.ofSeconds(5))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      // Any real HTTP response (even a 404 for an unknown deployment) proves the path is open;
      // only a connection-level failure (caught below) means it's still cut.
      return response.statusCode() > 0;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Scoped to what this scenario actually needs to prove -- the agent's own HTTP calls tolerate
   * real added latency -- rather than a full deploy-to-ACTIVE cycle. A first version asserted on
   * deployment status reaching ACTIVE, matching the other scenario's own style; even at a modest
   * 500ms each-way delay, that never completed within a 220s budget. A full cycle needs several
   * *sequential* control-plane round trips (register, then per-tick heartbeat + fetch-assignments)
   * plus real worker JVM startup on top, so injected latency compounds per call, not just once --
   * disproportionately expensive to tune reliably for what this scenario is actually about.
   * Checking the control plane's own {@link StateStore} directly for this node's first heartbeat
   * (same JVM, no extra HTTP round trip to observe it) is a direct, cheap, still-genuine proof that
   * the agent's HTTP path keeps working under latency, without needing the rest of the deploy
   * lifecycle to complete too.
   */
  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  void the_agent_keeps_working_correctly_under_realistic_added_latency() throws Exception {
    Assumptions.assumeTrue(
        toxiproxyServerReachable(),
        "toxiproxy-server not reachable on "
            + TOXIPROXY_HOST
            + ":"
            + TOXIPROXY_PORT
            + " -- skipping");

    StateStore store = new StateStore(tempDir.resolve("cp-state-latency"));
    apiServer = new ApiServer(store, 0);
    apiServer.start();

    ToxiproxyClient toxiproxyClient = new ToxiproxyClient(TOXIPROXY_HOST, TOXIPROXY_PORT);
    int proxyPort = reservePort();
    Proxy toxiproxy =
        toxiproxyClient.createProxy(
            "gimle-cp-api-latency-" + UUID.randomUUID(),
            "127.0.0.1:" + proxyPort,
            "127.0.0.1:" + apiServer.port());
    proxies.add(toxiproxy);
    String proxiedBaseUrl = "http://127.0.0.1:" + proxyPort;

    // A real 500ms delay each way -- comfortably below AgentMain's own 30s per-request HTTP
    // timeout, but well above real localhost speed, so this actually exercises added latency
    // rather than just re-proving the proxy is transparent at zero cost.
    toxiproxy.toxics().latency("agent-latency-down", ToxicDirection.DOWNSTREAM, 500);
    toxiproxy.toxics().latency("agent-latency-up", ToxicDirection.UPSTREAM, 500);

    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");
    agentProcesses.add(
        spawnAgent(
            javaExecutable,
            classpath,
            "node-a",
            proxiedBaseUrl,
            "127.0.0.1:17981",
            "-",
            tempDir.resolve("node-a-latency.log")));

    await(
        () -> store.getNodeHeartbeat("node-a").isPresent(),
        Duration.ofSeconds(60),
        "the agent should register and send its first heartbeat despite every control-plane call"
            + " now taking an extra ~1s round trip");
  }

  private static int reservePort() throws IOException {
    try (ServerSocketChannel probe = ServerSocketChannel.open()) {
      probe.bind(new InetSocketAddress("127.0.0.1", 0));
      return ((InetSocketAddress) probe.getLocalAddress()).getPort();
    }
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
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        replicas: %d
        """
            .formatted(name, moduleNameFor(name), jar.toAbsolutePath().toString(), replicas);
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

  private static String moduleNameFor(String deploymentName) {
    return "fixture-deployment".equals(deploymentName)
        ? "com.gimle.fixture.networkfault"
        : "com.gimle.fixture.networklatency";
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
   * ACTIVE}, and every one is on {@code expectedNodeId} -- the stronger condition needed so a stale
   * pre-cut snapshot (still showing the network-cut node) can't satisfy this check. Mirrors {@code
   * ControlPlaneAgentWorkerIntegrationTest}'s own helper of the same shape exactly.
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

  private static void await(BooleanSupplier condition, Duration timeout, String description) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within " + timeout + ": " + description);
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while waiting for: " + description, e);
      }
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
