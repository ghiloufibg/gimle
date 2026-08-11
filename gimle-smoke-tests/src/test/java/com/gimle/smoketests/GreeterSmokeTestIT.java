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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
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
 * manual flow has. {@code @Tag("smoke")} plus this module's own {@code excludedGroups} surefire
 * configuration is belt-and-suspenders against a real, previously-observed build-wiring gap (see
 * FLAKY_TESTS.md): an unqualified {@code -Dtest='!A,!B,...'} (exclusions only, no positive pattern)
 * has been seen to broaden Surefire's own default class discovery enough to pick this class up
 * under plain {@code mvn verify} anyway -- JUnit 5's own tag-based filtering is a separate
 * mechanism from Surefire's class-name-pattern selection, so it isn't affected the same way.
 */
@Tag("smoke")
class GreeterSmokeTestIT {

  private static final int STORE_COUNT = 3;
  private static final int CONTROLPLANE_COUNT = 2;
  private static final int FAFNIR_COUNT = 2;
  private static final int STORE_RAFT_PORT_BASE = 19080;
  private static final int STORE_CLIENT_PORT_BASE = 19091;
  private static final int CONTROLPLANE_PORT_BASE = 18080;
  private static final int FAFNIR_PORT_BASE = 19060;
  private static final int MUNINN_PORT = 19070;
  private static final String GOSSIP_ADDRESS = "127.0.0.1:19090";
  private static final String GIMLE_VERSION = "0.1.0-SNAPSHOT";
  // The one tenant this suite exercises the real secret round trip for: an untenanted deployment
  // never has config/secrets delivered at all (AgentMain#deliverConfig returns immediately when
  // AssignedInstance#tenantId is empty), so a real end-to-end check needs a genuine registered
  // Tenant, not just a bare deployment.
  private static final String SECRET_TENANT_ID = "smoke-tenant";
  private static final String SECRET_KEY = "some-secret-key";
  private static final String SECRET_VALUE = "smoke-test-secret-value";
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
  private record SmokeCluster(
      List<Process> storeProcesses,
      List<String> controlPlaneBaseUrls,
      Process agentProcess,
      String muninnEndpoint) {}

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

    // Must exist before the tenant-scoped deployment below is admitted (DeploymentSpec's own
    // javadoc: a tenantId must already name a registered Tenant), and before it, so the secret is
    // already readable the moment the agent delivers config at install time.
    provisionTenantAndSecret(writeUrl);

    submitDeployment(
        writeUrl,
        "greeter-provider-deployment",
        "com.gimle.examples.greeter.provider",
        providerJar,
        Optional.of(SECRET_TENANT_ID));
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

    // The real secret round trip (design doc §9/§11 Phase C): written via the API above, fetched
    // by the agent straight from Fafnir, delivered to the worker, and read back by the module's
    // own onStart hook -- logged there, asserted here. onStart already ran by the time the
    // ACTIVE await above passed, so this should already be true; the await is headroom for log
    // flush/propagation latency, not for the secret fetch itself.
    await(
        () -> providerLogShowsTheSecret(readUrl),
        Duration.ofSeconds(30),
        "greeter-provider-deployment's own log should show the real secret value fetched from"
            + " Fafnir");

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

  /**
   * The Muninn logs fallback (design doc Part B/O-11), end to end: a real deployed instance's own
   * log line is observed once through the live agent, survives that agent's own death, and is still
   * observable through the identical {@code /logs/instances/*} request afterward -- served from
   * Muninn's shipped history instead of a 502, with no client-visible difference in how the request
   * is made. Reuses {@link #providerLogShowsTheSecret} both before and after the kill: the exact
   * same substring match against the exact same JSON shape either endpoint returns is itself proof
   * the fallback is genuinely transparent, not a client-visible failover with different output.
   */
  @Test
  @Timeout(value = 6, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_deployed_instances_log_survives_its_owning_agent_dying() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path providerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    assertTrue(Files.isRegularFile(providerJar), "expected a built jar at " + providerJar);

    provisionTenantAndSecret(baseUrl);
    submitDeployment(
        baseUrl,
        "greeter-provider-deployment",
        "com.gimle.examples.greeter.provider",
        providerJar,
        Optional.of(SECRET_TENANT_ID));
    await(
        () -> isActive(baseUrl, "greeter-provider-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-deployment should reach ACTIVE");
    await(
        () -> providerLogShowsTheSecret(baseUrl),
        Duration.ofSeconds(30),
        "greeter-provider-deployment's own log should show the real secret value, served live by"
            + " its owning agent");

    // Headroom past AgentMain's own 5s MuninnShipper tick interval, so the line above is
    // genuinely already shipped before the agent that shipped it is killed -- once it's dead, the
    // shipper dies with it, and only whatever Muninn already received survives.
    Thread.sleep(Duration.ofSeconds(8).toMillis());

    killWithDescendants(cluster.agentProcess());

    await(
        () -> providerLogShowsTheSecret(baseUrl),
        Duration.ofSeconds(30),
        "greeter-provider-deployment's own log should still show the real secret value after its"
            + " owning agent died, now served from Muninn's shipped history instead");
  }

  /**
   * The metrics round trip (design doc Part B/O-10): a real request against a real control-plane
   * replica increments a real counter, that counter is shipped to Muninn, and the shipped value is
   * readable back through {@code GET /metrics-history/*} -- not just that the endpoint returns
   * *something*, but that the specific meter this test's own traffic drives shows up by name.
   */
  @Test
  @Timeout(value = 6, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_control_planes_own_request_metrics_round_trip_through_muninn() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);
    // Matches ControlPlaneMain's own selfApiAddress derivation (selfHost defaults to 127.0.0.1
    // when --host isn't passed, which spawnControlPlane above never passes).
    String processId = "127.0.0.1:" + CONTROLPLANE_PORT_BASE;

    // Real traffic against this replica's own /deployments endpoint -- exactly what
    // ApiServerMetricsTest's own unit-level assertion drives, here observed end to end through a
    // real shipped-and-read-back round trip instead of an in-process registry read.
    for (int i = 0; i < 5; i++) {
      httpClient.send(
          HttpRequest.newBuilder(URI.create(baseUrl + "/deployments")).GET().build(),
          HttpResponse.BodyHandlers.discarding());
    }

    await(
        () -> metricsHistoryShowsDeploymentsRequestCount(baseUrl, processId),
        Duration.ofSeconds(30),
        "gimle.controlplane.request.count for the deployments endpoint should be shipped to"
            + " Muninn and readable back through /metrics-history/*");
  }

  private boolean metricsHistoryShowsDeploymentsRequestCount(String baseUrl, String processId) {
    try {
      HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(
                      URI.create(baseUrl + "/metrics-history/CONTROLPLANE/" + processId))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return response.statusCode() == 200
          && response.body().contains("gimle.controlplane.request.count")
          && response.body().contains("deployments");
    } catch (Exception e) {
      return false;
    }
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

    // Before Fafnir/control-plane, not after: Muninn only needs the store (its own read-only
    // Authorizer check), so bringing it up this early means it's already reachable to receive
    // shipped data from every process started after it, matching BootstrapMojo's own ordering
    // (design doc Part B/O-14).
    String muninnEndpoint = "127.0.0.1:" + MUNINN_PORT;
    processes.add(
        spawnMuninn(
            javaExecutable,
            classpath,
            MUNINN_PORT,
            storeEndpointsSpec,
            tempDir.resolve("muninn.log")));
    awaitPortOpen("127.0.0.1", MUNINN_PORT, Duration.ofSeconds(30));

    // A single key file shared across every Fafnir replica -- the design doc §8 multi-replica
    // provisioning requirement -- unlike spawnControlPlane's own controlplane-secret-<port>.key
    // below, which is deliberately still per-replica-distinct (that key only ever guards plain,
    // legacy /config/* entries this suite doesn't exercise, so its own multi-replica-consistency
    // gap is a separate, already-flagged issue outside this item's scope).
    Path fafnirSecretKeyPath = tempDir.resolve("fafnir-secret.key");
    List<String> fafnirEndpoints = new ArrayList<>();
    for (int i = 0; i < FAFNIR_COUNT; i++) {
      int port = FAFNIR_PORT_BASE + i;
      processes.add(
          spawnFafnir(
              javaExecutable,
              classpath,
              port,
              storeEndpointsSpec,
              fafnirSecretKeyPath,
              muninnEndpoint,
              tempDir.resolve("fafnir-" + i + ".log")));
      fafnirEndpoints.add("127.0.0.1:" + port);
    }
    for (String fafnirEndpoint : fafnirEndpoints) {
      String[] hostPort = fafnirEndpoint.split(":");
      awaitPortOpen(hostPort[0], Integer.parseInt(hostPort[1]), Duration.ofSeconds(30));
    }

    List<String> controlPlaneBaseUrls = new ArrayList<>();
    for (int i = 0; i < CONTROLPLANE_COUNT; i++) {
      int port = CONTROLPLANE_PORT_BASE + i;
      String baseUrl = "http://127.0.0.1:" + port;
      // Round-robin across the Fafnir replicas -- FafnirClient talks to exactly one address, so
      // this is what proves every replica is independently usable (not just replica #0), matching
      // the store cluster's own "read through a different replica than you wrote through" proof
      // pattern above.
      String fafnirEndpoint = fafnirEndpoints.get(i % fafnirEndpoints.size());
      processes.add(
          spawnControlPlane(
              javaExecutable,
              classpath,
              port,
              storeEndpointsSpec,
              fafnirEndpoint,
              muninnEndpoint,
              tempDir.resolve("controlplane-" + i + ".log")));
      final String awaitUrl = baseUrl;
      final int replicaIndex = i;
      await(
          () -> httpRespondsQuietly(awaitUrl + "/deployments"),
          Duration.ofSeconds(30),
          "control-plane replica #" + replicaIndex + " should start accepting requests");
      controlPlaneBaseUrls.add(baseUrl);
    }

    Process agentProcess =
        spawnAgent(
            javaExecutable,
            classpath,
            GOSSIP_ADDRESS,
            controlPlaneBaseUrls.get(0),
            fafnirEndpoints.get(0),
            muninnEndpoint,
            tempDir.resolve("agent.log"));
    processes.add(agentProcess);

    return new SmokeCluster(storeProcesses, controlPlaneBaseUrls, agentProcess, muninnEndpoint);
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
                // MUNINN_PORT is a known constant even though Muninn itself starts after every
                // store node does (see #startCluster's own comment) -- matches BootstrapMojo's own
                // spawnStore wiring (design doc Part B/O-10).
                "-Dgimle.store.muninnEndpoint=127.0.0.1:" + MUNINN_PORT,
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

  private Process spawnMuninn(
      String javaExecutable, String classpath, int port, String storeEndpointsSpec, Path logFile)
      throws IOException {
    ProcessBuilder pb =
        new ProcessBuilder(
            javaExecutable,
            "-cp",
            classpath,
            "com.gimle.muninn.MuninnMain",
            String.valueOf(port),
            "--store-endpoints",
            storeEndpointsSpec,
            "--data-root",
            tempDir.resolve("muninn-data").toString());
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    return pb.start();
  }

  private Process spawnFafnir(
      String javaExecutable,
      String classpath,
      int port,
      String storeEndpointsSpec,
      Path secretKeyPath,
      String muninnEndpoint,
      Path logFile)
      throws IOException {
    ProcessBuilder pb =
        new ProcessBuilder(
            javaExecutable,
            "-Dgimle.fafnir.muninnEndpoint=" + muninnEndpoint,
            "-cp",
            classpath,
            "com.gimle.fafnir.FafnirMain",
            String.valueOf(port),
            secretKeyPath.toString(),
            "--store-endpoints",
            storeEndpointsSpec);
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    return pb.start();
  }

  private Process spawnControlPlane(
      String javaExecutable,
      String classpath,
      int port,
      String storeEndpointsSpec,
      String fafnirEndpoint,
      String muninnEndpoint,
      Path logFile)
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
            storeEndpointsSpec,
            "--fafnir-endpoint",
            fafnirEndpoint,
            "--muninn-endpoint",
            muninnEndpoint);
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    return pb.start();
  }

  private Process spawnAgent(
      String javaExecutable,
      String classpath,
      String gossipAddress,
      String controlPlaneBaseUrl,
      String fafnirEndpoint,
      String muninnEndpoint,
      Path logFile)
      throws IOException {
    ProcessBuilder pb =
        new ProcessBuilder(
            javaExecutable,
            "-Dgimle.agent.fafnirEndpoint=" + fafnirEndpoint,
            "-Dgimle.agent.muninnEndpoint=" + muninnEndpoint,
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
    submitDeployment(baseUrl, deploymentName, moduleName, jar, Optional.empty());
  }

  private void submitDeployment(
      String baseUrl, String deploymentName, String moduleName, Path jar, Optional<String> tenantId)
      throws Exception {
    String manifest =
        """
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        replicas: 1
        %s
        """
            .formatted(
                deploymentName,
                moduleName,
                jar.toAbsolutePath(),
                tenantId.map(id -> "tenantId: " + id).orElse(""));
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

  /**
   * Registers {@link #SECRET_TENANT_ID} with a generous quota -- large enough that {@code
   * greeter-provider}'s own {@code 64Mi}/{@code 100m} limit (see its {@code gimle-module.yaml})
   * never brushes it -- and writes {@link #SECRET_KEY} through the real {@code /secrets/*} proxy
   * (design doc §6e) before any deployment references the tenant. A deployment's {@code tenantId}
   * must already name a registered {@code Tenant} at admission time (see {@code DeploymentSpec}'s
   * own javadoc), so this must run before {@link #submitDeployment}.
   */
  private void provisionTenantAndSecret(String baseUrl) throws Exception {
    String quotaBody =
        Json.write(
            Map.of(
                "quota",
                Map.of(
                    "maxMemoryBytes",
                    256L * 1024 * 1024,
                    "maxCpuMillicores",
                    1000L,
                    "maxInstances",
                    10)));
    HttpResponse<String> tenantResponse =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/" + SECRET_TENANT_ID))
                .PUT(HttpRequest.BodyPublishers.ofString(quotaBody, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (tenantResponse.statusCode() != 200) {
      fail("tenant creation failed: " + tenantResponse.statusCode() + " " + tenantResponse.body());
    }

    String secretBody =
        Json.write(
            Map.of(
                "value",
                Base64.getEncoder().encodeToString(SECRET_VALUE.getBytes(StandardCharsets.UTF_8))));
    HttpResponse<String> secretResponse =
        httpClient.send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/secrets/" + SECRET_TENANT_ID + "/" + SECRET_KEY))
                .PUT(HttpRequest.BodyPublishers.ofString(secretBody, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (secretResponse.statusCode() != 200) {
      fail("secret write failed: " + secretResponse.statusCode() + " " + secretResponse.body());
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

  /**
   * The real secret round trip's own assertion point: {@link #SECRET_VALUE} written through the API
   * in {@link #provisionTenantAndSecret}, fetched by the real node agent straight from a real
   * Fafnir replica (design doc §9/§11 Phase C), delivered down to the worker, and read back out by
   * {@code GreeterProviderHooks#onStart}'s own {@code ctx.config(...)} call -- observed here purely
   * through this instance's own application log, the same way {@link #consumerLogShowsAGreeting}
   * observes the cross-worker fabric call.
   */
  private boolean providerLogShowsTheSecret(String baseUrl) {
    try {
      HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(
                      URI.create(
                          baseUrl
                              + "/logs/instances/greeter-provider-deployment/0"
                              + "?category=APPLICATION"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return response.statusCode() == 200 && response.body().contains(SECRET_VALUE);
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
        "port " + host + ":" + port + " should start listening");
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
