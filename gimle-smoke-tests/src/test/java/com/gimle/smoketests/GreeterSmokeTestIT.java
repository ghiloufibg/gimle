package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
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
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * The self-contained "deploy to a real server" smoke suite: spawns a genuine {@code gimle-mimir}
 * store cluster ({@link #STORE_COUNT} {@code StoreMain} processes) plus multiple {@code
 * ControlPlaneMain} replicas ({@link #CONTROLPLANE_COUNT}) sharing that same store cluster, plus
 * one {@code AgentMain} (+ its own {@code WorkerMain} child) -- the etcd-store-extraction split
 * (see {@code claudedocs/etcd-store-extraction-design.md}) means "the control plane" is now
 * genuinely two kinds of process, so this suite exercises both a multi-node store cluster and
 * multiple stateless control-plane replicas talking to it, not just a single all-in-one process.
 * Modeled on {@code ControlPlaneAgentWorkerIntegrationTest}'s subprocess patterns (gimle-agent),
 * except that test constructs {@code ApiServer} in-JVM; this needs real processes because the
 * Playwright suite it drives at the end needs a real browser hitting the real bundled web console,
 * which only a genuine {@code ControlPlaneMain} serves.
 *
 * <p>Deploys {@code greeter-provider} and {@code greeter-consumer} (gimle-examples/) via the real
 * HTTP API, asserts both reach {@code ACTIVE} and that the consumer's real fabric call to the
 * provider actually shows up in its own application log, then runs {@code gimle-console}'s
 * Playwright suite against that same live cluster.
 *
 * <p>Uses ports distinct from {@code gimle-console/LOCAL_DEV.md}'s manual walkthrough (8080/9080)
 * so this can run alongside a developer's own manually-started cluster without colliding.
 *
 * <p>Not part of the default {@code mvn verify} -- opt in with {@code -Psmoke}. Assumes {@code mvn
 * install} has already produced every jar this launches, the same precondition LOCAL_DEV.md's own
 * manual flow has.
 */
class GreeterSmokeTestIT {

  private static final int STORE_COUNT = 3;
  private static final int CONTROLPLANE_COUNT = 2;
  private static final int STORE_RAFT_PORT_BASE = 19080;
  private static final int STORE_CLIENT_PORT_BASE = 19091;
  private static final int CONTROLPLANE_PORT_BASE = 18080;
  private static final String GOSSIP_ADDRESS = "127.0.0.1:19090";
  private static final String GIMLE_VERSION = "0.1.0-SNAPSHOT";
  // Matches gimle-console/e2e/greeter-smoke.spec.ts's own login credentials -- that suite logs in
  // as this account before touching any /console page (RBAC/session auth, commit 05af65d, gates
  // every console route client-side regardless of transport). Created via an unauthenticated PUT
  // below: plaintext mode's ApiServer#requireAuthorized bypasses auth entirely (there is nothing
  // real to protect without TLS), so no prior identity is needed to create the first account.
  private static final String SMOKE_OPERATOR_USERNAME = "smoke-operator";
  private static final String SMOKE_OPERATOR_PASSWORD = "smoke-operator-password";

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private final List<Process> processes = new ArrayList<>();
  private final HttpClient httpClient = HttpClient.newHttpClient();

  @AfterEach
  void tearDown() {
    // Reverse of spawn order (store nodes, then control-plane replicas, then the agent), so
    // nothing still-running races a peer it depends on already being gone. Killing an
    // already-dead process (e.g. one a test killed itself mid-run) is a harmless no-op.
    for (int i = processes.size() - 1; i >= 0; i--) {
      killWithDescendants(processes.get(i));
    }
  }

  private static void killWithDescendants(Process process) {
    process.descendants().forEach(ProcessHandle::destroy);
    process.destroyForcibly();
  }

  /** What a test needs to talk to a freshly-started store-cluster + control-plane-replica set. */
  private record SmokeCluster(List<Process> storeProcesses, List<String> controlPlaneBaseUrls) {}

  @Test
  @Timeout(value = 6, unit = java.util.concurrent.TimeUnit.MINUTES)
  void greeter_modules_deploy_across_a_store_cluster_and_multiple_control_plane_replicas()
      throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String writeUrl = cluster.controlPlaneBaseUrls().get(0);
    // A DIFFERENT control-plane replica than the one the deployments are submitted through --
    // proves the two share state via gimle-mimir rather than each holding its own, which is the
    // entire point of decoupling ApiServer replica count from the store's own membership.
    String readUrl = cluster.controlPlaneBaseUrls().get(CONTROLPLANE_COUNT - 1);

    Path providerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    Path consumerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-consumer/target/greeter-consumer-" + GIMLE_VERSION + ".jar");
    assertTrue(Files.isRegularFile(providerJar), "expected a built jar at " + providerJar);
    assertTrue(Files.isRegularFile(consumerJar), "expected a built jar at " + consumerJar);

    submitDeployment(
        writeUrl,
        "greeter-provider-deployment",
        "com.gimle.examples.greeter.provider",
        providerJar);
    submitDeployment(
        writeUrl,
        "greeter-consumer-deployment",
        "com.gimle.examples.greeter.consumer",
        consumerJar);

    await(
        () -> isActive(readUrl, "greeter-provider-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-deployment should reach ACTIVE, observed through a different"
            + " control-plane replica than the one it was submitted to");
    await(
        () -> isActive(readUrl, "greeter-consumer-deployment"),
        Duration.ofSeconds(60),
        "greeter-consumer-deployment should reach ACTIVE, observed through a different"
            + " control-plane replica than the one it was submitted to");

    // Proves the real fabric call happened, not just that both processes started: the consumer
    // retries its lookup+call every 5s, so a healthy cluster should show this well within a
    // minute of both instances going ACTIVE.
    await(
        () -> consumerLogShowsAGreeting(readUrl),
        Duration.ofSeconds(60),
        "greeter-consumer-deployment's own log should show a real reply from greeter-provider");

    // Playwright targets readUrl, not writeUrl: readUrl is the replica the awaits above already
    // polled until it showed fresh ACTIVE state. Store reads are deliberately loose across the
    // M-node store cluster (design doc §4.5, no linearizability requirement), so a replica that
    // hasn't been read from yet could still be serving a stale view for a few hundred ms after a
    // write -- real, expected, and not what this Playwright leg exists to characterize. The
    // cross-replica-consistency property itself is already proven above via plain HTTP.
    createLoginAccount(readUrl, SMOKE_OPERATOR_USERNAME, SMOKE_OPERATOR_PASSWORD);
    runPlaywrightSuite(repoRoot, readUrl);
  }

  @Test
  @Timeout(value = 6, unit = java.util.concurrent.TimeUnit.MINUTES)
  void cluster_tolerates_losing_one_store_node_mid_deployment() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path providerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    assertTrue(Files.isRegularFile(providerJar), "expected a built jar at " + providerJar);

    submitDeployment(
        baseUrl, "greeter-provider-deployment", "com.gimle.examples.greeter.provider", providerJar);
    await(
        () -> isActive(baseUrl, "greeter-provider-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-deployment should reach ACTIVE before the store node is killed");

    // Kill one store node -- whichever it turns out to be, leader or follower: Raft only needs a
    // majority (2 of 3 here) to keep serving, so this proves the deployed system survives losing
    // any single store replica, not just a follower. (A leader-specific kill would need a
    // client-visible "who is leader" query, which StoreRpc deliberately doesn't expose -- reads
    // are leader-agnostic by design, see StoreRpc's own javadoc.)
    killWithDescendants(cluster.storeProcesses().get(0));

    // A brand-new deployment submitted only after the kill: its placement, its ACTIVE transition,
    // and the reconciler-leader lease renewal that drives that transition all depend on the
    // surviving 2-of-3 store majority electing a leader and continuing to accept writes. Retried,
    // not a single attempt: the immediate aftermath of losing a node includes a real window where
    // the surviving store replicas are mid-election and every write genuinely 503s -- that window
    // recovering is exactly the property under test, not a reason to fail fast.
    submitDeploymentWithRetry(
        baseUrl,
        "greeter-provider-deployment-2",
        "com.gimle.examples.greeter.provider",
        providerJar,
        Duration.ofSeconds(30));
    await(
        () -> isActive(baseUrl, "greeter-provider-deployment-2"),
        Duration.ofSeconds(90),
        "a deployment submitted after losing one store node should still reach ACTIVE, proving"
            + " the surviving store majority kept serving writes");
  }

  private SmokeCluster startCluster(Path repoRoot, String javaExecutable, String classpath)
      throws IOException {
    List<Process> storeProcesses = new ArrayList<>();
    List<String> storeClientEndpoints = new ArrayList<>();
    for (int i = 0; i < STORE_COUNT; i++) {
      storeClientEndpoints.add("127.0.0.1:" + (STORE_CLIENT_PORT_BASE + i));
    }
    for (int i = 0; i < STORE_COUNT; i++) {
      int raftPort = STORE_RAFT_PORT_BASE + i;
      int clientPort = STORE_CLIENT_PORT_BASE + i;
      Process store =
          spawnStore(
              javaExecutable,
              classpath,
              raftPort,
              clientPort,
              storePeersSpecExcluding(i),
              tempDir.resolve("store-" + i + ".log"));
      processes.add(store);
      storeProcesses.add(store);
    }
    for (int i = 0; i < STORE_COUNT; i++) {
      awaitPortOpen("127.0.0.1", STORE_CLIENT_PORT_BASE + i, Duration.ofSeconds(30));
    }
    String storeEndpointsSpec = String.join(",", storeClientEndpoints);

    List<String> controlPlaneBaseUrls = new ArrayList<>();
    for (int i = 0; i < CONTROLPLANE_COUNT; i++) {
      int port = CONTROLPLANE_PORT_BASE + i;
      String baseUrl = "http://127.0.0.1:" + port;
      processes.add(
          spawnControlPlane(
              javaExecutable,
              classpath,
              port,
              storeEndpointsSpec,
              tempDir.resolve("controlplane-" + i + ".log")));
      final String awaitUrl = baseUrl;
      final int replicaIndex = i;
      await(
          () -> httpRespondsQuietly(awaitUrl + "/deployments"),
          Duration.ofSeconds(30),
          "control-plane replica #" + replicaIndex + " should start accepting requests");
      controlPlaneBaseUrls.add(baseUrl);
    }

    processes.add(
        spawnAgent(
            javaExecutable,
            classpath,
            GOSSIP_ADDRESS,
            controlPlaneBaseUrls.get(0),
            tempDir.resolve("agent.log")));

    return new SmokeCluster(storeProcesses, controlPlaneBaseUrls);
  }

  private static String storePeersSpecExcluding(int excludeIndex) {
    List<String> peers = new ArrayList<>();
    for (int i = 0; i < STORE_COUNT; i++) {
      if (i == excludeIndex) {
        continue;
      }
      peers.add("127.0.0.1:" + (STORE_RAFT_PORT_BASE + i) + ":" + (STORE_CLIENT_PORT_BASE + i));
    }
    return String.join(",", peers);
  }

  private void runPlaywrightSuite(Path repoRoot, String baseUrl)
      throws IOException, InterruptedException {
    Path consoleDir = repoRoot.resolve("gimle-console");
    Path logFile = tempDir.resolve("playwright.log");
    ProcessBuilder pb =
        new ProcessBuilder(bunExecutable(), "run", "test:e2e").directory(consoleDir.toFile());
    pb.environment().put("CONSOLE_BASE_URL", baseUrl);
    // Not inheritIO(): Surefire/Failsafe's forked-JVM protocol talks to the parent Maven process
    // over this same JVM's own stdout, and a child process writing to it directly (inheritIO
    // shares the file descriptor, not just mirrors it) corrupts that channel. Redirect to a file
    // like every other subprocess this test spawns, and surface it on failure instead.
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    Process playwright = pb.start();
    int exitCode = playwright.waitFor();
    if (exitCode != 0) {
      fail(
          "gimle-console's Playwright suite failed with exit code "
              + exitCode
              + "; see "
              + logFile);
    }
  }

  private static String bunExecutable() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
        ? "bun.exe"
        : "bun";
  }

  private Process spawnStore(
      String javaExecutable,
      String classpath,
      int raftPort,
      int clientPort,
      String peersSpec,
      Path logFile)
      throws IOException {
    List<String> command =
        new ArrayList<>(
            List.of(
                javaExecutable,
                "-cp",
                classpath,
                "com.gimle.mimir.StoreMain",
                tempDir.resolve("store-state-" + raftPort).toString(),
                String.valueOf(raftPort),
                String.valueOf(clientPort)));
    if (!peersSpec.isBlank()) {
      command.add("--peers");
      command.add(peersSpec);
    }
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    return pb.start();
  }

  private Process spawnControlPlane(
      String javaExecutable, String classpath, int port, String storeEndpointsSpec, Path logFile)
      throws IOException {
    ProcessBuilder pb =
        new ProcessBuilder(
            javaExecutable,
            "-cp",
            classpath,
            "com.gimle.controlplane.ControlPlaneMain",
            String.valueOf(port),
            tempDir.resolve("controlplane-secret-" + port + ".key").toString(),
            "--store-endpoints",
            storeEndpointsSpec);
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    return pb.start();
  }

  private Process spawnAgent(
      String javaExecutable,
      String classpath,
      String gossipAddress,
      String controlPlaneBaseUrl,
      Path logFile)
      throws IOException {
    ProcessBuilder pb =
        new ProcessBuilder(
            javaExecutable,
            "-cp",
            classpath,
            "com.gimle.agent.AgentMain",
            "smoke-node-1",
            controlPlaneBaseUrl,
            gossipAddress,
            "-",
            javaExecutable,
            "-cp",
            classpath,
            "com.gimle.worker.WorkerMain");
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    return pb.start();
  }

  private void submitDeployment(String baseUrl, String deploymentName, String moduleName, Path jar)
      throws Exception {
    String manifest =
        """
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        replicas: 1
        """
            .formatted(deploymentName, moduleName, jar.toAbsolutePath());
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + deploymentName))
                .PUT(HttpRequest.BodyPublishers.ofString(manifest, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      fail("deployment submission failed: " + response.statusCode() + " " + response.body());
    }
  }

  private void submitDeploymentWithRetry(
      String baseUrl, String deploymentName, String moduleName, Path jar, Duration timeout)
      throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      try {
        submitDeployment(baseUrl, deploymentName, moduleName, jar);
        return;
      } catch (AssertionError e) {
        if (System.nanoTime() > deadline) {
          throw e;
        }
        Thread.sleep(500);
      }
    }
  }

  private void createLoginAccount(String baseUrl, String username, String password)
      throws Exception {
    String body = Json.write(Map.of("password", password));
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/accounts/" + username))
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      fail("account creation failed: " + response.statusCode() + " " + response.body());
    }
  }

  private boolean isActive(String baseUrl, String deploymentName) {
    try {
      Map<String, Object> status = deploymentStatus(baseUrl, deploymentName);
      List<Map<String, Object>> instances = Json.asObjectList(status.get("instances"));
      if (instances.isEmpty()) {
        return false;
      }
      for (Map<String, Object> instance : instances) {
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

  private Map<String, Object> deploymentStatus(String baseUrl, String deploymentName)
      throws Exception {
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + deploymentName))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      return Map.of("instances", List.of());
    }
    return Json.asObject(Json.parse(response.body()));
  }

  private boolean consumerLogShowsAGreeting(String baseUrl) {
    try {
      HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(
                      URI.create(
                          baseUrl
                              + "/logs/instances/greeter-consumer-deployment/0"
                              + "?category=APPLICATION"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return response.statusCode() == 200 && response.body().contains("Hello, Gimlé!");
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean httpRespondsQuietly(String url) {
    try {
      HttpResponse<Void> response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(url)).GET().build(),
                  HttpResponse.BodyHandlers.discarding());
      return response.statusCode() < 500;
    } catch (Exception e) {
      return false;
    }
  }

  private static void awaitPortOpen(String host, int port, Duration timeout) {
    await(
        () -> isPortOpen(host, port),
        timeout,
        "store client port " + port + " should start listening");
  }

  private static boolean isPortOpen(String host, int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 500);
      return true;
    } catch (IOException e) {
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

  private static Path repoRoot() {
    return Path.of("").toAbsolutePath().getParent();
  }

  private static String javaExecutable() {
    java.util.Optional<String> command = ProcessHandle.current().info().command();
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
