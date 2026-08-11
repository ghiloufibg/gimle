package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.core.protocol.Json;
import com.gimle.mimir.raft.PeerAddress;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

  /**
   * The single {@code WorkerMain} child process descendant of a supervising {@code AgentMain}, if
   * one currently exists. Matched on {@code -Dgimle.log.root=.../workers/...} ({@code
   * AgentMain#buildWorkerCommand}'s own worker-only flag, scoping that worker's default log root to
   * a subdirectory named after it) rather than the trailing {@code com.gimle.worker.WorkerMain}
   * main-class argument: {@code ProcessHandle.Info#commandLine()} truncates a very long command
   * line (this repo's own many-module classpath easily exceeds whatever internal buffer it uses)
   * before reaching an argument that far into the command, a real, previously-observed gap between
   * what {@code ProcessBuilder} was actually given and what comes back out through {@code
   * ProcessHandle}. This flag sits early enough to survive that.
   */
  private static Optional<ProcessHandle> findWorkerDescendant(Process agentProcess) {
    return agentProcess
        .descendants()
        .filter(
            handle ->
                handle
                    .info()
                    .commandLine()
                    .map(line -> line.contains("-Dgimle.log.root=") && line.contains("/workers/"))
                    .orElse(false))
        .findFirst();
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
   * QA Phase 3 continuation: failover under real *concurrent* writes, not a write submitted only
   * after the dust settles (the test above proves the surviving majority eventually serves writes
   * again; this proves nothing acknowledged during the transition is ever lost). A background
   * writer thread {@code PUT}s a new, distinct tenant (a real, lightweight {@code StoreClient
   * #propose} write against the same 3-node Raft cluster, with no scheduler/agent/worker side
   * effects to confound the signal the way a real module deployment's placement would) roughly
   * every 200ms, continuously, before, during, and after one store node is killed -- deliberately
   * not targeting the leader specifically ({@code StoreRpc} deliberately doesn't expose "who is
   * leader" to a client, see the sibling test above), since Raft's own safety guarantee (a write is
   * only acknowledged once committed to a majority) must hold regardless of which node is lost.
   * Every write that received a real {@code 200} is recorded; once the writer stops, this asserts
   * three things: writes kept succeeding after the kill (real recovery under load, not just a
   * pre-kill snapshot), and -- the actual property under test -- every single acknowledged write is
   * still durably readable afterward, none silently lost.
   */
  @Test
  @Timeout(value = 6, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_leader_failover_loses_no_acknowledged_write_under_concurrent_load() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Set<String> acknowledgedTenants = ConcurrentHashMap.newKeySet();
    AtomicInteger attemptCounter = new AtomicInteger();
    AtomicInteger acknowledgedAfterKill = new AtomicInteger();
    AtomicBoolean killed = new AtomicBoolean(false);
    AtomicBoolean stopWriting = new AtomicBoolean(false);

    String quotaBody =
        Json.write(
            Map.of(
                "quota", Map.of("maxMemoryBytes", 1L, "maxCpuMillicores", 1L, "maxInstances", 0)));

    Thread writer =
        Thread.ofVirtual()
            .start(
                () -> {
                  while (!stopWriting.get()) {
                    String tenantId = "failover-canary-tenant-" + attemptCounter.incrementAndGet();
                    try {
                      HttpResponse<String> response =
                          httpClient.send(
                              HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/" + tenantId))
                                  .timeout(Duration.ofSeconds(5))
                                  .PUT(
                                      HttpRequest.BodyPublishers.ofString(
                                          quotaBody, StandardCharsets.UTF_8))
                                  .build(),
                              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                      if (response.statusCode() == 200) {
                        acknowledgedTenants.add(tenantId);
                        if (killed.get()) {
                          acknowledgedAfterKill.incrementAndGet();
                        }
                      }
                    } catch (IOException | InterruptedException e) {
                      // A transient failure during the failover window is expected and not itself
                      // a violation -- what matters is that a write actually ACKNOWLEDGED (200) is
                      // never subsequently lost, checked below. Simply move on to the next write.
                    }
                    try {
                      Thread.sleep(200);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                    }
                  }
                });

    // Let a real batch of writes land before the kill.
    Thread.sleep(Duration.ofSeconds(3).toMillis());
    killWithDescendants(cluster.storeProcesses().get(0));
    killed.set(true);

    // Keep writing well past any realistic re-election/recovery window.
    Thread.sleep(Duration.ofSeconds(30).toMillis());
    stopWriting.set(true);
    writer.join(Duration.ofSeconds(10).toMillis());

    assertTrue(
        acknowledgedTenants.size() >= 5,
        "expected several real writes to have been acknowledged across the whole run; got "
            + acknowledgedTenants.size());
    assertTrue(
        acknowledgedAfterKill.get() >= 1,
        "expected at least one write to be acknowledged after the kill, proving the surviving"
            + " majority actually resumed serving writes rather than just the pre-kill batch"
            + " surviving");

    for (String tenantId : acknowledgedTenants) {
      HttpResponse<String> getResponse =
          httpClient.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/" + tenantId)).GET().build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(
          200,
          getResponse.statusCode(),
          "tenant "
              + tenantId
              + " was acknowledged (200) by the write path but is not durably readable afterward"
              + " -- a real acknowledged write must never be lost across a leader failover");
    }
  }

  /**
   * Etcd-style live membership change (P1-5, {@code StoreClient#addServer}/{@code removeServer}),
   * exercised for the first time against real {@code StoreMain} processes talking real wire
   * protocol -- every existing coverage of this (e.g. {@code RaftMembershipChangeTest},
   * RaftClusterTest's own membership-change tests) constructs {@code RaftNode} directly in-process.
   * A fourth store node is started standalone (no {@code --peers}: it only becomes a cluster member
   * once {@link StoreClient#addServer} proposes it into the Raft configuration), added while the
   * bootstrap 3-node cluster is already live and serving writes, proven by a deployment submitted
   * only after the join, then removed again the same way, proven by a third deployment submitted
   * only after the removal. Deliberately removes the node it just added rather than one of the
   * original three: {@code RaftNode} has no dedicated handling for a leader removing itself from
   * its own membership (a real, harder Raft edge case -- see the design's own single-server-change
   * safety rule in {@code pendingMembershipChangeIndex}'s javadoc), and the freshly-joined follower
   * is, by construction, never the leader (it joins well after the bootstrap cluster's own election
   * already settled, and nothing about a plain {@code addServer} triggers a new one) -- so this
   * proves the addServer/removeServer roundtrip itself without also gambling on that untested edge
   * case within the same run.
   *
   * <p><b>Disabled</b>: this reproduction found and fixed one real bug (a self-elected phantom
   * leader that {@link com.gimle.mimir.raft.RaftNode#onAppendEntries}/{@code onInstallSnapshot}
   * never demoted on an equal-term message -- see QA_FINDINGS.md), but even with that fix in place
   * the cluster's own post-membership-change leader instability window is genuinely variable in
   * this sandbox's 12-JVM/4-core load -- 85s in some runs, still not recovered after 150s in
   * others. No timeout this suite picks can both stay honest about a real upper bound and pass
   * reliably here. Left in place (not deleted) as a deterministic repro for whoever picks up
   * QA_FINDINGS.md's still-open finding next -- either a quieter/dedicated CI runner, or a real
   * Raft-level fix (e.g. a non-voting learner catch-up phase before a new peer becomes a full
   * voting member, the classic Raft answer to exactly this instability).
   */
  @Test
  @org.junit.jupiter.api.Disabled(
      "QA_FINDINGS.md: live membership change's post-change leader-instability window is real but"
          + " variable (85-180s+) in this sandbox; see the class javadoc above for the fixed bug"
          + " and the still-open timing finding")
  @Timeout(value = 8, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_new_store_node_joins_via_live_membership_change_and_is_then_removed() throws Exception {
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
        "greeter-provider-deployment should reach ACTIVE before any membership change");

    int newRaftPort = STORE_RAFT_PORT_BASE + STORE_COUNT;
    int newClientPort = STORE_CLIENT_PORT_BASE + STORE_COUNT;
    Process fourthStore =
        spawnStore(
            javaExecutable,
            classpath,
            newRaftPort,
            newClientPort,
            "",
            tempDir.resolve("store-3.log"));
    processes.add(fourthStore);
    awaitPortOpen("127.0.0.1", newClientPort, Duration.ofSeconds(30));

    List<SocketAddress> bootstrapEndpoints = new ArrayList<>();
    for (int i = 0; i < STORE_COUNT; i++) {
      bootstrapEndpoints.add(new InetSocketAddress("127.0.0.1", STORE_CLIENT_PORT_BASE + i));
    }
    String newPeerId = "127.0.0.1:" + newRaftPort;
    try (StoreClient storeClient = new StoreClient(bootstrapEndpoints)) {
      storeClient.addServer(newPeerId, new PeerAddress("127.0.0.1", newRaftPort, newClientPort));
    }

    submitDeploymentWithRetry(
        baseUrl,
        "greeter-provider-deployment-2",
        "com.gimle.examples.greeter.provider",
        providerJar,
        Duration.ofSeconds(30));
    await(
        () -> isActive(baseUrl, "greeter-provider-deployment-2"),
        // 150s, not the usual 90s this suite's other post-disruption awaits use: a live
        // membership change genuinely destabilizes leadership for a real, repeatable ~85-95s in
        // this sandbox's 4-core/12-JVM load (QA_FINDINGS.md's own reproduction/measurement),
        // longer than a single node loss (that test's own 90s budget) since the new peer must
        // also fully catch up its log before the expanded quorum stabilizes -- generous headroom
        // here, not a padded guess.
        Duration.ofSeconds(150),
        "a deployment submitted after a 4th store node joined via live membership change should"
            + " still reach ACTIVE, proving the newly-expanded 4-node cluster kept serving writes");

    try (StoreClient storeClient = new StoreClient(bootstrapEndpoints)) {
      storeClient.removeServer(newPeerId);
    }

    submitDeploymentWithRetry(
        baseUrl,
        "greeter-provider-deployment-3",
        "com.gimle.examples.greeter.provider",
        providerJar,
        Duration.ofSeconds(30));
    await(
        () -> isActive(baseUrl, "greeter-provider-deployment-3"),
        Duration.ofSeconds(150),
        "a deployment submitted after the 4th store node was removed again should still reach"
            + " ACTIVE, proving the cluster is back to serving writes from its original three"
            + " members");
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
   * QA hardening pass, Phase 3: the agent-death test above (and every other existing scenario in
   * this class) never kills the *worker* JVM itself -- a genuinely different failure domain (see
   * CLAUDE.md's own "tiered self-healing" framing: module dispose+reinstantiate vs. worker
   * destroyForcibly+respawn vs. machine-level reschedule are three distinct recovery paths). This
   * proves the middle tier: WorkerProcessSupervisor (gimle-agent) actually respawns a killed worker
   * process and the deployment genuinely recovers to ACTIVE again, not just that the agent's own
   * bookkeeping believes it should.
   */
  @Test
  @Timeout(value = 6, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_crashed_workers_instance_is_respawned_and_returns_to_active() throws Exception {
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
        "greeter-provider-deployment should reach ACTIVE before the worker is killed");

    ProcessHandle firstWorker =
        findWorkerDescendant(cluster.agentProcess())
            .orElseThrow(
                () -> new AssertionError("expected a live WorkerMain descendant of the agent"));
    long firstWorkerPid = firstWorker.pid();
    firstWorker.destroyForcibly();

    await(
        () -> {
          Optional<ProcessHandle> current = findWorkerDescendant(cluster.agentProcess());
          // Both halves matter: a *new* worker process (proof the supervisor actually respawned
          // one, not that the old one lingers) that has *also* brought the deployment back to
          // ACTIVE (proof the respawned worker is genuinely healthy, not just alive).
          return current.isPresent()
              && current.get().pid() != firstWorkerPid
              && isActive(baseUrl, "greeter-provider-deployment");
        },
        Duration.ofSeconds(60),
        "a new worker process should replace pid "
            + firstWorkerPid
            + " and the deployment should return to ACTIVE");
  }

  /**
   * QA hardening pass, Phase 3 continuation: {@code QuotaReconciler}'s own class javadoc states it
   * deliberately never evicts instances to force compliance, only surfaces a quota violation for a
   * human operator to resolve -- covered at the reconciler-unit tier ({@code QuotaReconcilerTest}),
   * but nothing previously proved this against a real running deployment. A tenant's quota is
   * retroactively lowered below what's already running (the exact scenario that reconciler's own
   * javadoc names), and this asserts both halves: the violation becomes visible on the real API
   * surface, and the already-running instance is never touched.
   */
  @Test
  @Timeout(value = 6, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_tenant_over_quota_deployment_is_flagged_but_not_evicted() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path providerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    assertTrue(Files.isRegularFile(providerJar), "expected a built jar at " + providerJar);

    String quotaTenantId = "quota-smoke-tenant";
    // Generous enough for the provider's own request (32Mi/20m, gimle-module.yaml) to schedule
    // and reach ACTIVE cleanly before the quota is lowered below it.
    putTenantQuota(baseUrl, quotaTenantId, 256L * 1024 * 1024, 1000L, 10);

    submitDeployment(
        baseUrl,
        "greeter-provider-deployment",
        "com.gimle.examples.greeter.provider",
        providerJar,
        Optional.of(quotaTenantId));
    await(
        () -> isActive(baseUrl, "greeter-provider-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-deployment should reach ACTIVE under a compliant quota");
    assertTrue(
        !isQuotaViolating(baseUrl, "greeter-provider-deployment"),
        "should not be flagged while comfortably within quota");

    // Retroactively lower the same tenant's quota below what's already running -- QuotaReconciler's
    // own documented trigger for this flag, not a quota rejected at admission time.
    putTenantQuota(baseUrl, quotaTenantId, 1L, 1L, 0);

    await(
        () -> isQuotaViolating(baseUrl, "greeter-provider-deployment"),
        Duration.ofSeconds(30),
        "greeter-provider-deployment should be flagged quota-violating once its tenant's quota is"
            + " retroactively lowered below what's already running");
    // Give the reconciler several more ticks' worth of headroom, then confirm it still never
    // touched the running instance -- the actual guarantee this test exists to prove, not just
    // that the flag can be set.
    Thread.sleep(Duration.ofSeconds(5).toMillis());
    assertTrue(
        isActive(baseUrl, "greeter-provider-deployment"),
        "a quota-violating deployment must stay untouched, never evicted, per QuotaReconciler's"
            + " own documented contract");
  }

  /**
   * QA Phase 3 continuation: the admission-time counterpart to the flag-but-don't-evict scenario
   * above. {@code ApiServer#checkTenantQuota} is a real, already-implemented 409 rejection at
   * submission time -- distinct from {@code QuotaReconciler}'s own after-the-fact flag for a quota
   * lowered *below* what's already running -- but nothing previously proved it against a real
   * cluster either. A tenant's quota is sized to fit exactly one {@code greeter-provider} replica
   * (32Mi/20m request, see its {@code gimle-module.yaml}) and no more; a second deployment for the
   * same tenant is submitted once the first is already {@code ACTIVE}, and this asserts the
   * rejection is real (409, the deployment never created at all -- {@code GET /deployments/*}
   * returns 404, not an empty/pending record) and that it never touched the first, already-running
   * deployment.
   */
  @Test
  @Timeout(value = 4, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_deployment_that_would_exceed_tenant_quota_is_rejected_at_admission() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path providerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    assertTrue(Files.isRegularFile(providerJar), "expected a built jar at " + providerJar);

    String tenantId = "quota-admission-smoke-tenant";
    // Room for exactly one 32Mi/20m replica (the provider's own request) and no more: a second
    // replica of anything would push memory to 64Mi (> 40Mi) and instances to 2 (> 1).
    putTenantQuota(baseUrl, tenantId, 40L * 1024 * 1024, 1000L, 1);

    submitDeployment(
        baseUrl,
        "greeter-provider-quota-admission-a",
        "com.gimle.examples.greeter.provider",
        providerJar,
        Optional.of(tenantId));
    await(
        () -> isActive(baseUrl, "greeter-provider-quota-admission-a"),
        Duration.ofSeconds(60),
        "the first deployment should reach ACTIVE while comfortably within quota");

    HttpResponse<String> rejected =
        submitDeploymentExpectingRejection(
            baseUrl,
            "greeter-provider-quota-admission-b",
            "com.gimle.examples.greeter.provider",
            providerJar,
            tenantId);
    assertEquals(
        409,
        rejected.statusCode(),
        "a second deployment that would push the tenant past its quota must be rejected outright"
            + " at admission, not silently accepted and left for QuotaReconciler to flag later:"
            + " body="
            + rejected.body());

    HttpResponse<String> secondDeploymentStatus =
        httpClient.send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/deployments/greeter-provider-quota-admission-b"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(
        404,
        secondDeploymentStatus.statusCode(),
        "a rejected submission must never be durably created at all, not even in a"
            + " zero-instances/pending state");

    assertTrue(
        isActive(baseUrl, "greeter-provider-quota-admission-a"),
        "the first, already-compliant deployment must be completely unaffected by the second"
            + " submission's rejection");
  }

  private void putTenantQuota(
      String baseUrl, String tenantId, long maxMemoryBytes, long maxCpuMillicores, int maxInstances)
      throws Exception {
    String quotaBody =
        Json.write(
            Map.of(
                "quota",
                Map.of(
                    "maxMemoryBytes",
                    maxMemoryBytes,
                    "maxCpuMillicores",
                    maxCpuMillicores,
                    "maxInstances",
                    maxInstances)));
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/" + tenantId))
                .PUT(HttpRequest.BodyPublishers.ofString(quotaBody, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      fail("tenant quota update failed: " + response.statusCode() + " " + response.body());
    }
  }

  private boolean isQuotaViolating(String baseUrl, String deploymentName) {
    try {
      Map<String, Object> status = deploymentStatus(baseUrl, deploymentName);
      return Boolean.TRUE.equals(status.get("quotaViolating"));
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Multi-metric autoscaling (Part C, {@code AutoscaleReconciler}) under real generated load, not a
   * synthetic stand-in for it: {@code greeter-load-generator} (gimle-examples/) turns every inbound
   * HTTP request into one real cross-worker fabric call to greeter-provider's {@code Greeter}, and
   * Gatling ({@code GreeterAutoscaleSimulation}, spawned as its own JVM the same way every other
   * cluster component here is) drives that HTTP traffic at a real, controlled rate -- so the
   * request rate {@code AutoscaleReconciler}'s policy reads is greeter-provider's own real,
   * worker-reported {@code requestRatePerSecond}, produced by genuine external load, all the way
   * through the real pipeline: worker metrics report (5s) -> agent heartbeat (5s) -> {@code
   * AutoscaleReconciler}'s own tick (2s) -> {@code DeploymentReconciler} scheduling a real second
   * instance. The autoscale policy's {@code targetCpuUtilizationPercent} is set deliberately high
   * (200%, unreachable by this workload) so only the request-rate signal can be what drives the
   * scale-up -- proving a genuinely non-CPU signal works end to end against a real cluster, not
   * just CPU (the one signal that predates Part C and every other real-cluster deployment test here
   * already exercises implicitly).
   */
  @Test
  @Timeout(value = 8, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_deployment_scales_up_under_real_gatling_generated_request_rate_load() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path providerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    Path loadGeneratorJar =
        repoRoot.resolve(
            "gimle-examples/greeter-load-generator/target/greeter-load-generator-"
                + GIMLE_VERSION
                + ".jar");
    assertTrue(Files.isRegularFile(providerJar), "expected a built jar at " + providerJar);
    assertTrue(
        Files.isRegularFile(loadGeneratorJar), "expected a built jar at " + loadGeneratorJar);

    submitDeployment(
        baseUrl,
        "greeter-load-generator-deployment",
        "com.gimle.examples.greeter.loadgen",
        loadGeneratorJar);
    await(
        () -> isActive(baseUrl, "greeter-load-generator-deployment"),
        Duration.ofSeconds(60),
        "greeter-load-generator-deployment should reach ACTIVE before any load is generated");

    submitAutoscaleDeployment(
        baseUrl,
        "greeter-provider-autoscale-deployment",
        "com.gimle.examples.greeter.provider",
        "1.0.0",
        providerJar,
        /* minReplicas= */ 1,
        /* maxReplicas= */ 2,
        /* targetCpuUtilizationPercent= */ 200,
        /* targetRequestRatePerSecond= */ Optional.of(5.0),
        /* targetErrorRatePercent= */ Optional.empty(),
        /* targetQueueDepth= */ Optional.empty());
    await(
        () -> isActive(baseUrl, "greeter-provider-autoscale-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-autoscale-deployment should reach ACTIVE before any load is generated");
    assertEquals(
        1,
        activeInstanceCount(baseUrl, "greeter-provider-autoscale-deployment"),
        "should start at exactly its declared 1 replica before load begins");

    // Comfortably above the 5.0/s target and sustained well past every stage of the real
    // pipeline's own cadence (5s worker report + 5s agent heartbeat + 2s reconcile tick) --
    // generous headroom, not a tight race against those intervals.
    Process gatling =
        spawnGatling(
            javaExecutable,
            classpath,
            /* requestsPerSecond= */ 20,
            /* durationSeconds= */ 60,
            tempDir.resolve("gatling.log"));
    processes.add(gatling);

    await(
        () -> activeInstanceCount(baseUrl, "greeter-provider-autoscale-deployment") >= 2,
        Duration.ofSeconds(120),
        "greeter-provider-autoscale-deployment should scale up from 1 to 2 replicas under real"
            + " Gatling-generated request-rate load, driven purely by the non-CPU"
            + " targetRequestRatePerSecond signal");
  }

  /**
   * QA Phase 3 continuation: the error-rate autoscaling signal under real load. {@code
   * targetErrorRatePercent} is the one Part C signal never exercised against a real cluster before
   * this -- request rate found a real bug (the {@code DomainCodec} truncation, see above); error
   * rate and queue depth were still unit/integration-only. Deploys {@link
   * #buildFaultyProviderJar()} (real {@code greet} calls, deterministically ~50% of which throw) so
   * the fabric server's own dispatch (see {@code FabricServer#dispatch}) records real errors
   * against the instance's own {@code WorkerMetrics} -- the actual signal {@code
   * AutoscaleReconciler}'s {@code errorRatePercent} helper reads, not a synthetic stand-in. CPU and
   * request-rate targets are both set unreachable so only the error-rate signal can explain a
   * scale-up.
   */
  @Test
  @Timeout(value = 8, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_deployment_scales_up_under_real_error_rate_load() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path loadGeneratorJar =
        repoRoot.resolve(
            "gimle-examples/greeter-load-generator/target/greeter-load-generator-"
                + GIMLE_VERSION
                + ".jar");
    assertTrue(
        Files.isRegularFile(loadGeneratorJar), "expected a built jar at " + loadGeneratorJar);
    Path faultyProviderJar = buildFaultyProviderJar();

    submitDeployment(
        baseUrl,
        "greeter-load-generator-deployment",
        "com.gimle.examples.greeter.loadgen",
        loadGeneratorJar);
    await(
        () -> isActive(baseUrl, "greeter-load-generator-deployment"),
        Duration.ofSeconds(60),
        "greeter-load-generator-deployment should reach ACTIVE before any load is generated");

    submitAutoscaleDeployment(
        baseUrl,
        "greeter-provider-error-rate-deployment",
        "com.gimle.examples.greeter.provider",
        "1.0.0-faulty",
        faultyProviderJar,
        /* minReplicas= */ 1,
        /* maxReplicas= */ 2,
        /* targetCpuUtilizationPercent= */ 200,
        /* targetRequestRatePerSecond= */ Optional.empty(),
        // Comfortably below the real ~50% failure rate the faulty provider produces, so a stable
        // request stream alone (no rate target configured) still drives a scale-up purely off
        // error percentage.
        /* targetErrorRatePercent= */ Optional.of(20.0),
        /* targetQueueDepth= */ Optional.empty());
    await(
        () -> isActive(baseUrl, "greeter-provider-error-rate-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-error-rate-deployment should reach ACTIVE before any load is generated");
    assertEquals(
        1,
        activeInstanceCount(baseUrl, "greeter-provider-error-rate-deployment"),
        "should start at exactly its declared 1 replica before load begins");

    Process gatling =
        spawnGatling(
            javaExecutable,
            classpath,
            /* requestsPerSecond= */ 10,
            /* durationSeconds= */ 60,
            tempDir.resolve("gatling-error-rate.log"));
    processes.add(gatling);

    await(
        () -> activeInstanceCount(baseUrl, "greeter-provider-error-rate-deployment") >= 2,
        Duration.ofSeconds(120),
        "greeter-provider-error-rate-deployment should scale up from 1 to 2 replicas under real"
            + " request failures, driven purely by the non-CPU/non-rate targetErrorRatePercent"
            + " signal");
  }

  /**
   * QA Phase 3 continuation: the queue-depth autoscaling signal under real load. Deploys {@link
   * #buildSlowProviderJar()} (real {@code greet} calls, each sleeping ~300ms) and drives Gatling's
   * <i>closed</i> injection model (see {@code GreeterAutoscaleSimulation}'s own javadoc) to hold
   * more requests continuously in flight than {@code WorkerRuntime}'s per-module {@code
   * BoundedModuleScheduler} concurrency bound (4) -- the only way to build a real, sustained
   * backlog on it. {@code queueDepth} comes straight off that scheduler (see {@code
   * WorkerMain#metricsReportLoop}), the actual signal {@code AutoscaleReconciler}'s {@code
   * targetQueueDepth} reads. CPU, request-rate, and error-rate targets are all unreachable/absent
   * so only the queue-depth signal can explain a scale-up.
   */
  @Test
  @Timeout(value = 8, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_deployment_scales_up_under_real_queue_depth_load() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path loadGeneratorJar =
        repoRoot.resolve(
            "gimle-examples/greeter-load-generator/target/greeter-load-generator-"
                + GIMLE_VERSION
                + ".jar");
    assertTrue(
        Files.isRegularFile(loadGeneratorJar), "expected a built jar at " + loadGeneratorJar);
    Path slowProviderJar = buildSlowProviderJar();

    submitDeployment(
        baseUrl,
        "greeter-load-generator-deployment",
        "com.gimle.examples.greeter.loadgen",
        loadGeneratorJar);
    await(
        () -> isActive(baseUrl, "greeter-load-generator-deployment"),
        Duration.ofSeconds(60),
        "greeter-load-generator-deployment should reach ACTIVE before any load is generated");

    submitAutoscaleDeployment(
        baseUrl,
        "greeter-provider-queue-depth-deployment",
        "com.gimle.examples.greeter.provider",
        "1.0.0-slow",
        slowProviderJar,
        /* minReplicas= */ 1,
        /* maxReplicas= */ 2,
        /* targetCpuUtilizationPercent= */ 200,
        /* targetRequestRatePerSecond= */ Optional.empty(),
        /* targetErrorRatePercent= */ Optional.empty(),
        // Comfortably below the ~16-deep backlog 20 concurrent 300ms-each callers sustain against
        // a concurrency bound of 4 (roughly (20-4) requests waiting at any moment).
        /* targetQueueDepth= */ Optional.of(2));
    await(
        () -> isActive(baseUrl, "greeter-provider-queue-depth-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-queue-depth-deployment should reach ACTIVE before any load is generated");
    assertEquals(
        1,
        activeInstanceCount(baseUrl, "greeter-provider-queue-depth-deployment"),
        "should start at exactly its declared 1 replica before load begins");

    Process gatling =
        spawnGatling(
            javaExecutable,
            classpath,
            /* requestsPerSecond= */ 0,
            /* durationSeconds= */ 60,
            /* concurrentUsers= */ 20,
            tempDir.resolve("gatling-queue-depth.log"));
    processes.add(gatling);

    await(
        () -> activeInstanceCount(baseUrl, "greeter-provider-queue-depth-deployment") >= 2,
        Duration.ofSeconds(120),
        "greeter-provider-queue-depth-deployment should scale up from 1 to 2 replicas under a real"
            + " sustained request backlog, driven purely by the non-CPU/non-rate/non-error"
            + " targetQueueDepth signal");
  }

  /**
   * QA Phase 3: rolling update / version-aware traffic cutover under real load. Deploys 2 replicas
   * of {@code greeter-provider} at v1.0.0, confirms both reach {@code ACTIVE}, then submits a real
   * v1.1.0 build of the same module (compiled on the fly by {@link TestModuleBuilder} -- see {@link
   * #buildProviderV2Jar()} -- rather than committing a second, near-duplicate example module) while
   * Gatling drives sustained real HTTP traffic through {@code greeter-load-generator}'s real fabric
   * call the whole time. {@link
   * com.gimle.controlplane.reconcile.DeploymentReconciler#handleRollingUpdate} (see its own
   * javadoc) is deliberately minimal: one index migrated at a time, in place, no {@code
   * maxSurge}/{@code maxUnavailable} surge-then-drain -- so this is only a genuine "rolling", not
   * "recreate", test with 2+ replicas: while one index is mid-migration the other stays untouched
   * and keeps serving. A background virtual-thread sampler polls the deployment's real observed
   * instance count/version throughout the whole rollout window and asserts at least one instance
   * stayed {@code ACTIVE} at every sampled moment -- proving continuous availability, not just
   * eventual convergence to v2.
   */
  @Test
  @Timeout(value = 8, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_rolling_update_keeps_at_least_one_instance_serving_traffic_throughout() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path loadGeneratorJar =
        repoRoot.resolve(
            "gimle-examples/greeter-load-generator/target/greeter-load-generator-"
                + GIMLE_VERSION
                + ".jar");
    Path providerV1Jar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    assertTrue(
        Files.isRegularFile(loadGeneratorJar), "expected a built jar at " + loadGeneratorJar);
    assertTrue(Files.isRegularFile(providerV1Jar), "expected a built jar at " + providerV1Jar);

    submitDeployment(
        baseUrl,
        "greeter-load-generator-deployment",
        "com.gimle.examples.greeter.loadgen",
        loadGeneratorJar);
    await(
        () -> isActive(baseUrl, "greeter-load-generator-deployment"),
        Duration.ofSeconds(60),
        "greeter-load-generator-deployment should reach ACTIVE before any load is generated");

    submitDeploymentWithReplicasWithRetry(
        baseUrl,
        "greeter-provider-rolling-deployment",
        "com.gimle.examples.greeter.provider",
        "1.0.0",
        providerV1Jar,
        2,
        Duration.ofSeconds(30));
    await(
        () -> allInstancesOnVersion(baseUrl, "greeter-provider-rolling-deployment", "1.0.0", 2),
        Duration.ofSeconds(90),
        "both v1 replicas should reach ACTIVE before any rollout begins");

    AtomicInteger minActiveDuringRollout = new AtomicInteger(Integer.MAX_VALUE);
    AtomicBoolean stopSampling = new AtomicBoolean(false);
    Thread sampler =
        Thread.ofVirtual()
            .start(
                () -> {
                  while (!stopSampling.get()) {
                    int active =
                        activeInstanceCount(baseUrl, "greeter-provider-rolling-deployment");
                    minActiveDuringRollout.updateAndGet(min -> Math.min(min, active));
                    try {
                      Thread.sleep(300);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                    }
                  }
                });

    // Comfortably sustained across the whole rollout window -- proves the consumer-facing traffic
    // path (load generator -> real fabric call -> greeter-provider) keeps working throughout, not
    // just before/after.
    Process gatling =
        spawnGatling(
            javaExecutable,
            classpath,
            /* requestsPerSecond= */ 5,
            /* durationSeconds= */ 90,
            tempDir.resolve("gatling-rolling.log"));
    processes.add(gatling);

    Path providerV2Jar = buildProviderV2Jar();
    submitDeploymentWithReplicasWithRetry(
        baseUrl,
        "greeter-provider-rolling-deployment",
        "com.gimle.examples.greeter.provider",
        "1.1.0",
        providerV2Jar,
        2,
        Duration.ofSeconds(30));

    try {
      await(
          () -> allInstancesOnVersion(baseUrl, "greeter-provider-rolling-deployment", "1.1.0", 2),
          Duration.ofSeconds(180),
          "both replicas should migrate to v1.1.0 one index at a time, per"
              + " DeploymentReconciler's own rolling-update contract");
    } finally {
      stopSampling.set(true);
      sampler.join(Duration.ofSeconds(5).toMillis());
    }

    assertTrue(
        minActiveDuringRollout.get() >= 1,
        "at least one instance should have stayed ACTIVE at every sampled moment during the"
            + " rollout -- the whole point of a *rolling* update rather than a full outage."
            + " Observed minimum: "
            + minActiveDuringRollout.get());
  }

  /**
   * QA Phase 3 continuation: the single-replica counterpart to the 2-replica test above -- confirms
   * the documented tradeoff is real, not just documented. {@link
   * com.gimle.controlplane.reconcile.DeploymentReconciler#handleRollingUpdate}'s own javadoc is
   * explicit that this is in-place index replacement (kill old at that index, then place new at the
   * same index), never additive surge-then-drain, so a single-replica deployment WILL see real
   * downtime during a migration -- only a multi-replica deployment (the test above) can demonstrate
   * continuous availability. This test asserts the opposite inequality from that one: real,
   * observed downtime (the sampler must catch at least one moment with zero {@code ACTIVE}
   * instances), and that the deployment still fully converges to the new version afterward --
   * proving the tradeoff is exactly as costly as documented, not silently worse (e.g. never
   * recovering) or silently better (e.g. a surge the docs don't claim to provide).
   */
  @Test
  @Timeout(value = 8, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_single_replica_rolling_update_has_real_observed_downtime() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path loadGeneratorJar =
        repoRoot.resolve(
            "gimle-examples/greeter-load-generator/target/greeter-load-generator-"
                + GIMLE_VERSION
                + ".jar");
    Path providerV1Jar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    assertTrue(
        Files.isRegularFile(loadGeneratorJar), "expected a built jar at " + loadGeneratorJar);
    assertTrue(Files.isRegularFile(providerV1Jar), "expected a built jar at " + providerV1Jar);

    submitDeployment(
        baseUrl,
        "greeter-load-generator-deployment",
        "com.gimle.examples.greeter.loadgen",
        loadGeneratorJar);
    await(
        () -> isActive(baseUrl, "greeter-load-generator-deployment"),
        Duration.ofSeconds(60),
        "greeter-load-generator-deployment should reach ACTIVE before any load is generated");

    submitDeploymentWithReplicasWithRetry(
        baseUrl,
        "greeter-provider-single-rolling-deployment",
        "com.gimle.examples.greeter.provider",
        "1.0.0",
        providerV1Jar,
        1,
        Duration.ofSeconds(30));
    await(
        () ->
            allInstancesOnVersion(
                baseUrl, "greeter-provider-single-rolling-deployment", "1.0.0", 1),
        Duration.ofSeconds(90),
        "the single v1 replica should reach ACTIVE before any rollout begins");

    AtomicInteger minActiveDuringRollout = new AtomicInteger(Integer.MAX_VALUE);
    AtomicBoolean stopSampling = new AtomicBoolean(false);
    Thread sampler =
        Thread.ofVirtual()
            .start(
                () -> {
                  while (!stopSampling.get()) {
                    int active =
                        activeInstanceCount(baseUrl, "greeter-provider-single-rolling-deployment");
                    minActiveDuringRollout.updateAndGet(min -> Math.min(min, active));
                    try {
                      Thread.sleep(300);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                    }
                  }
                });

    Process gatling =
        spawnGatling(
            javaExecutable,
            classpath,
            /* requestsPerSecond= */ 5,
            /* durationSeconds= */ 90,
            tempDir.resolve("gatling-single-rolling.log"));
    processes.add(gatling);

    Path providerV2Jar = buildProviderV2Jar();
    submitDeploymentWithReplicasWithRetry(
        baseUrl,
        "greeter-provider-single-rolling-deployment",
        "com.gimle.examples.greeter.provider",
        "1.1.0",
        providerV2Jar,
        1,
        Duration.ofSeconds(30));

    try {
      await(
          () ->
              allInstancesOnVersion(
                  baseUrl, "greeter-provider-single-rolling-deployment", "1.1.0", 1),
          Duration.ofSeconds(180),
          "the single replica should still fully converge to v1.1.0 despite the observed downtime");
    } finally {
      stopSampling.set(true);
      sampler.join(Duration.ofSeconds(5).toMillis());
    }

    assertEquals(
        0,
        minActiveDuringRollout.get(),
        "a single-replica rolling update should show real, observed downtime (the old instance is"
            + " stopped before the new one is placed) -- DeploymentReconciler's own javadoc"
            + " documents this as a deliberate tradeoff of the minimal in-place-replacement design,"
            + " not additive surge-then-drain. Observed minimum active instances: "
            + minActiveDuringRollout.get()
            + " (0 expected)");
  }

  /**
   * Compiles a real v1.1.0 {@code greeter-provider} at test run time via {@link TestModuleBuilder},
   * same module name as the committed v1.0.0 example (so {@code DeploymentReconciler} recognizes it
   * as a version bump of the same deployment, not a different module), different version and
   * greeting text so a running instance's reported {@code moduleId.version} unambiguously proves
   * which build is actually serving.
   */
  private Path buildProviderV2Jar() {
    List<Path> compileJars = findCompileModulePathJars();
    return TestModuleBuilder.module(
            """
            module com.gimle.examples.greeter.provider {
              requires static com.gimle.module;
              requires static org.slf4j;
              exports com.gimle.examples.greeter.provider;
              exports com.gimle.examples.greeter;
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.Greeter",
            """
            package com.gimle.examples.greeter;
            public interface Greeter {
              String greet(String name);
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProviderV2Hooks",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.examples.greeter.Greeter;
            import com.gimle.module.lifecycle.ModuleContext;
            import com.gimle.module.lifecycle.ModuleLifecycleHooks;
            public final class GreeterProviderV2Hooks implements ModuleLifecycleHooks {
              public void onInstall(ModuleContext ctx) {}
              public void onStart(ModuleContext ctx) {
                ctx.registerService(Greeter.class, name -> "Hello, " + name + "! (from provider v2)");
              }
              public void onStop(ModuleContext ctx) {}
              public void onUninstall(ModuleContext ctx) {}
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProviderV2Liveness",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.module.probe.LivenessProbe;
            public final class GreeterProviderV2Liveness implements LivenessProbe {
              public boolean isAlive() { return true; }
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProviderV2Readiness",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.module.probe.ReadinessProbe;
            public final class GreeterProviderV2Readiness implements ReadinessProbe {
              public boolean isReady() { return true; }
            }
            """)
        .withDescriptor(
            """
            name: com.gimle.examples.greeter.provider
            version: 1.1.0
            isolation:
              tier: TIER_2
            resources:
              request:
                memory: 32Mi
                cpu: 20m
              limit:
                memory: 64Mi
                cpu: 100m
            exports:
              - service: com.gimle.examples.greeter.Greeter
                version: 1.0.0
            lifecycle:
              hooks: com.gimle.examples.greeter.provider.GreeterProviderV2Hooks
            health:
              liveness: com.gimle.examples.greeter.provider.GreeterProviderV2Liveness
              readiness: com.gimle.examples.greeter.provider.GreeterProviderV2Readiness
            """)
        .dependsOn(compileJars.toArray(Path[]::new))
        .build(tempDir, "greeter-provider-v2.jar");
  }

  /**
   * A real {@code greeter-provider} whose {@code greet} deterministically fails half the time
   * (every other call, via a static counter -- deterministic rather than random so the real failure
   * rate this produces is exactly predictable: ~50%) -- the fabric server-side dispatch that
   * invokes it records each thrown exception as a real error against this instance's own {@code
   * WorkerMetrics} (see {@code FabricServer#dispatch}), which is exactly the {@code
   * errorRatePerSecond} signal {@code AutoscaleReconciler}'s {@code targetErrorRatePercent} reads.
   * Built via {@link TestModuleBuilder} rather than modifying the real, committed example module,
   * same reasoning as {@link #buildProviderV2Jar()}.
   */
  private Path buildFaultyProviderJar() {
    List<Path> compileJars = findCompileModulePathJars();
    return TestModuleBuilder.module(
            """
            module com.gimle.examples.greeter.provider {
              requires static com.gimle.module;
              requires static org.slf4j;
              exports com.gimle.examples.greeter.provider;
              exports com.gimle.examples.greeter;
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.Greeter",
            """
            package com.gimle.examples.greeter;
            public interface Greeter {
              String greet(String name);
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProviderFaultyHooks",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.examples.greeter.Greeter;
            import com.gimle.module.lifecycle.ModuleContext;
            import com.gimle.module.lifecycle.ModuleLifecycleHooks;
            import java.util.concurrent.atomic.AtomicLong;
            public final class GreeterProviderFaultyHooks implements ModuleLifecycleHooks {
              private static final AtomicLong CALLS = new AtomicLong();
              public void onInstall(ModuleContext ctx) {}
              public void onStart(ModuleContext ctx) {
                ctx.registerService(Greeter.class, name -> {
                  if (CALLS.incrementAndGet() % 2 == 0) {
                    throw new IllegalStateException("synthetic QA fault");
                  }
                  return "Hello, " + name + "! (from faulty provider)";
                });
              }
              public void onStop(ModuleContext ctx) {}
              public void onUninstall(ModuleContext ctx) {}
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProviderFaultyLiveness",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.module.probe.LivenessProbe;
            public final class GreeterProviderFaultyLiveness implements LivenessProbe {
              public boolean isAlive() { return true; }
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProviderFaultyReadiness",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.module.probe.ReadinessProbe;
            public final class GreeterProviderFaultyReadiness implements ReadinessProbe {
              public boolean isReady() { return true; }
            }
            """)
        .withDescriptor(
            """
            name: com.gimle.examples.greeter.provider
            version: 1.0.0-faulty
            isolation:
              tier: TIER_2
            resources:
              request:
                memory: 32Mi
                cpu: 20m
              limit:
                memory: 64Mi
                cpu: 100m
            exports:
              - service: com.gimle.examples.greeter.Greeter
                version: 1.0.0
            lifecycle:
              hooks: com.gimle.examples.greeter.provider.GreeterProviderFaultyHooks
            health:
              liveness: com.gimle.examples.greeter.provider.GreeterProviderFaultyLiveness
              readiness: com.gimle.examples.greeter.provider.GreeterProviderFaultyReadiness
            """)
        .dependsOn(compileJars.toArray(Path[]::new))
        .build(tempDir, "greeter-provider-faulty.jar");
  }

  /**
   * A real {@code greeter-provider} whose {@code greet} always sleeps ~300ms before returning --
   * paired with sustained concurrent (not just rate-limited) load, this is what actually builds a
   * real backlog on {@code WorkerRuntime}'s per-module {@code BoundedModuleScheduler} (concurrency
   * bound 4): a fixed request rate alone says nothing about how many requests are in flight at
   * once, but enough slow, concurrent calls queue behind that bound the same way a real slow
   * downstream dependency would. {@code queueDepth} is reported straight off that scheduler (see
   * {@code WorkerMain#metricsReportLoop}), which is exactly the signal {@code
   * AutoscaleReconciler}'s {@code targetQueueDepth} reads. Built via {@link TestModuleBuilder},
   * same reasoning as {@link #buildProviderV2Jar()}.
   */
  private Path buildSlowProviderJar() {
    List<Path> compileJars = findCompileModulePathJars();
    return TestModuleBuilder.module(
            """
            module com.gimle.examples.greeter.provider {
              requires static com.gimle.module;
              requires static org.slf4j;
              exports com.gimle.examples.greeter.provider;
              exports com.gimle.examples.greeter;
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.Greeter",
            """
            package com.gimle.examples.greeter;
            public interface Greeter {
              String greet(String name);
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProviderSlowHooks",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.examples.greeter.Greeter;
            import com.gimle.module.lifecycle.ModuleContext;
            import com.gimle.module.lifecycle.ModuleLifecycleHooks;
            public final class GreeterProviderSlowHooks implements ModuleLifecycleHooks {
              public void onInstall(ModuleContext ctx) {}
              public void onStart(ModuleContext ctx) {
                ctx.registerService(Greeter.class, name -> {
                  try {
                    Thread.sleep(300);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return "Hello, " + name + "! (from slow provider)";
                });
              }
              public void onStop(ModuleContext ctx) {}
              public void onUninstall(ModuleContext ctx) {}
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProviderSlowLiveness",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.module.probe.LivenessProbe;
            public final class GreeterProviderSlowLiveness implements LivenessProbe {
              public boolean isAlive() { return true; }
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProviderSlowReadiness",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.module.probe.ReadinessProbe;
            public final class GreeterProviderSlowReadiness implements ReadinessProbe {
              public boolean isReady() { return true; }
            }
            """)
        .withDescriptor(
            """
            name: com.gimle.examples.greeter.provider
            version: 1.0.0-slow
            isolation:
              tier: TIER_2
            resources:
              request:
                memory: 32Mi
                cpu: 20m
              limit:
                memory: 64Mi
                cpu: 100m
            exports:
              - service: com.gimle.examples.greeter.Greeter
                version: 1.0.0
            lifecycle:
              hooks: com.gimle.examples.greeter.provider.GreeterProviderSlowHooks
            health:
              liveness: com.gimle.examples.greeter.provider.GreeterProviderSlowLiveness
              readiness: com.gimle.examples.greeter.provider.GreeterProviderSlowReadiness
            """)
        .dependsOn(compileJars.toArray(Path[]::new))
        .build(tempDir, "greeter-provider-slow.jar");
  }

  /**
   * Scans this test JVM's own classpath for the jars {@link #buildProviderV2Jar()}'s compilation
   * needs on its module path ({@code com.gimle.module}, {@code org.slf4j}) -- mirrors {@code
   * RealBundledHookAndProbeInvocationTest#findPlatformJars} (gimle-worker), matching either an
   * installed jar in the local repo or a reactor-build {@code target/classes} directory, since
   * which shape is on the classpath depends on whether this module is built standalone or as part
   * of a multi-module reactor build.
   */
  private static List<Path> findCompileModulePathJars() {
    String[] jarNeedles = {"slf4j-api-"};
    String[] moduleArtifacts = {"gimle-module"};
    List<Path> result = new ArrayList<>();
    String cp = System.getProperty("java.class.path");
    for (String entry : cp.split(File.pathSeparator)) {
      String fileName = Path.of(entry).getFileName().toString();
      String normalized = entry.replace('\\', '/');
      for (String needle : jarNeedles) {
        if (fileName.startsWith(needle)
            && fileName.endsWith(".jar")
            && !fileName.contains("tests")) {
          result.add(Path.of(entry));
        }
      }
      for (String artifact : moduleArtifacts) {
        boolean installedJar = fileName.startsWith(artifact + "-") && fileName.endsWith(".jar");
        boolean reactorClasses = normalized.endsWith("/" + artifact + "/target/classes");
        if ((installedJar && !fileName.contains("tests")) || reactorClasses) {
          result.add(Path.of(entry));
        }
      }
    }
    return result;
  }

  /**
   * Like {@link #submitDeployment}, but with a caller-chosen replica count and module version
   * (rather than the hardcoded {@code replicas: 1}/{@code version: 1.0.0} that helper writes) --
   * {@link com.gimle.mimir.manifest.DeploymentManifestParser#parseModuleId} reads the manifest's
   * own declared {@code module.version} field verbatim as {@code spec.moduleId()}, never re-derived
   * from the artifact's real descriptor inside the jar, so a version-bump rollout must state the
   * new version here explicitly or {@code DeploymentReconciler} would never see a {@code moduleId}
   * mismatch to trigger a rolling migration on.
   */
  private void submitDeploymentWithReplicas(
      String baseUrl,
      String deploymentName,
      String moduleName,
      String version,
      Path jar,
      int replicas)
      throws Exception {
    String manifest =
        """
        name: %s
        module:
          name: %s
          version: %s
        artifactPath: %s
        replicas: %d
        """
            .formatted(deploymentName, moduleName, version, jar.toAbsolutePath(), replicas);
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
   * Like {@link #submitDeploymentWithRetry}, but for {@link #submitDeploymentWithReplicas} -- the
   * store cluster can transiently reject a write with a 503 mid-election (see {@code
   * submitDeploymentWithRetry}'s own call sites for the same real, previously-observed behavior),
   * so every submission in this suite that isn't guaranteed to land against an
   * already-proven-stable cluster retries rather than failing outright on the first transient
   * rejection.
   */
  private void submitDeploymentWithReplicasWithRetry(
      String baseUrl,
      String deploymentName,
      String moduleName,
      String version,
      Path jar,
      int replicas,
      Duration timeout)
      throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      try {
        submitDeploymentWithReplicas(baseUrl, deploymentName, moduleName, version, jar, replicas);
        return;
      } catch (AssertionError e) {
        if (System.nanoTime() > deadline) {
          throw e;
        }
        Thread.sleep(500);
      }
    }
  }

  /**
   * True once exactly {@code expectedCount} of {@code deploymentName}'s real placed instances
   * report both {@code lifecycleState == ACTIVE} and {@code observation.moduleId.version ==
   * version} -- unlike {@link #isActive}, this also checks the *version* actually running, which is
   * the whole point of a rolling-update assertion (reaching ACTIVE again proves nothing about which
   * build is serving).
   */
  private boolean allInstancesOnVersion(
      String baseUrl, String deploymentName, String version, int expectedCount) {
    try {
      Map<String, Object> status = deploymentStatus(baseUrl, deploymentName);
      List<Map<String, Object>> instances = Json.asObjectList(status.get("instances"));
      if (instances.size() != expectedCount) {
        return false;
      }
      for (Map<String, Object> instance : instances) {
        Object observation = instance.get("observation");
        if (!(observation instanceof Map<?, ?> obsMap)
            || !"ACTIVE".equals(obsMap.get("lifecycleState"))) {
          return false;
        }
        Object moduleId = obsMap.get("moduleId");
        if (!(moduleId instanceof Map<?, ?> moduleIdMap)
            || !version.equals(moduleIdMap.get("version"))) {
          return false;
        }
      }
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * {@code moduleName}/{@code moduleVersion} are explicit rather than hardcoded to greeter-
   * provider's own committed 1.0.0 -- error-rate and queue-depth scenarios deploy a purpose-built
   * chaos variant compiled on the fly by {@link TestModuleBuilder} (see {@link
   * #buildFaultyProviderJar()}/{@link #buildSlowProviderJar()}) rather than modifying the real,
   * committed example module just to inject synthetic faults/latency into it. {@code
   * targetRequestRatePerSecond}/{@code targetErrorRatePercent}/{@code targetQueueDepth} are each
   * independently optional, matching {@code AutoscalePolicy}'s own shape -- an absent one is simply
   * omitted from the manifest, never evaluated by {@code AutoscaleReconciler}.
   */
  private void submitAutoscaleDeployment(
      String baseUrl,
      String deploymentName,
      String moduleName,
      String moduleVersion,
      Path jar,
      int minReplicas,
      int maxReplicas,
      int targetCpuUtilizationPercent,
      Optional<Double> targetRequestRatePerSecond,
      Optional<Double> targetErrorRatePercent,
      Optional<Integer> targetQueueDepth)
      throws Exception {
    StringBuilder autoscaleBlock =
        new StringBuilder("autoscale:\n")
            .append("  minReplicas: ")
            .append(minReplicas)
            .append('\n')
            .append("  maxReplicas: ")
            .append(maxReplicas)
            .append('\n')
            .append("  targetCpuUtilizationPercent: ")
            .append(targetCpuUtilizationPercent)
            .append('\n');
    targetRequestRatePerSecond.ifPresent(
        value ->
            autoscaleBlock.append("  targetRequestRatePerSecond: ").append(value).append('\n'));
    targetErrorRatePercent.ifPresent(
        value -> autoscaleBlock.append("  targetErrorRatePercent: ").append(value).append('\n'));
    targetQueueDepth.ifPresent(
        value -> autoscaleBlock.append("  targetQueueDepth: ").append(value).append('\n'));

    String manifest =
        """
        name: %s
        module:
          name: %s
          version: %s
        artifactPath: %s
        replicas: %d
        %s"""
            .formatted(
                deploymentName,
                moduleName,
                moduleVersion,
                jar.toAbsolutePath(),
                minReplicas,
                autoscaleBlock);
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + deploymentName))
                .PUT(HttpRequest.BodyPublishers.ofString(manifest, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      fail(
          "autoscale deployment submission failed: "
              + response.statusCode()
              + " "
              + response.body());
    }
  }

  /** How many of {@code deploymentName}'s real placed instances currently report ACTIVE. */
  private int activeInstanceCount(String baseUrl, String deploymentName) {
    try {
      Map<String, Object> status = deploymentStatus(baseUrl, deploymentName);
      List<Map<String, Object>> instances = Json.asObjectList(status.get("instances"));
      int active = 0;
      for (Map<String, Object> instance : instances) {
        Object observation = instance.get("observation");
        if (observation instanceof Map<?, ?> obsMap
            && "ACTIVE".equals(obsMap.get("lifecycleState"))) {
          active++;
        }
      }
      return active;
    } catch (Exception e) {
      return 0;
    }
  }

  /**
   * Spawns {@code GreeterAutoscaleSimulation} as {@code io.gatling.app.Gatling}'s own CLI, the same
   * shape every other cluster component in this suite is spawned in (Playwright's own {@code bun
   * run test:e2e} is the closest precedent: a separate process, a separate toolchain, never
   * embedded in this JVM). {@code --add-opens java.base/java.lang=ALL-UNNAMED} is a real,
   * documented Gatling requirement on modern JDKs (its stats writer reflects into {@code
   * java.lang.String} internals for a fast-path encoder) -- omitting it fails the run immediately
   * with an {@code IllegalAccessException}, not a silent degradation. {@code -nr} skips HTML report
   * generation: this test cares about the real side effect (greeter-provider's own worker-reported
   * request rate rising and the reconciler scaling up in response), not Gatling's own charts.
   */
  private Process spawnGatling(
      String javaExecutable,
      String classpath,
      int requestsPerSecond,
      int durationSeconds,
      Path logFile)
      throws IOException {
    return spawnGatling(
        javaExecutable,
        classpath,
        requestsPerSecond,
        durationSeconds,
        /* concurrentUsers= */ 0,
        logFile);
  }

  /**
   * Like {@link #spawnGatling(String, String, int, int, Path)}, but with a caller-chosen closed-
   * model concurrency instead of the open-model {@code requestsPerSecond} rate -- see {@code
   * GreeterAutoscaleSimulation}'s own javadoc on why a fixed request rate alone can't build a real
   * queue-depth backlog, only a sustained concurrent-in-flight count can. {@code concurrentUsers <=
   * 0} keeps the existing open-model behavior; {@code requestsPerSecond} is simply ignored in that
   * case, the same shape the two-arg overload above already implies.
   */
  private Process spawnGatling(
      String javaExecutable,
      String classpath,
      int requestsPerSecond,
      int durationSeconds,
      int concurrentUsers,
      Path logFile)
      throws IOException {
    ProcessBuilder pb =
        new ProcessBuilder(
            javaExecutable,
            "--add-opens",
            "java.base/java.lang=ALL-UNNAMED",
            "-cp",
            classpath,
            "-Dgimle.load.baseUrl=http://127.0.0.1:19077",
            "-Dgimle.load.requestsPerSecond=" + requestsPerSecond,
            "-Dgimle.load.durationSeconds=" + durationSeconds,
            "-Dgimle.load.concurrentUsers=" + concurrentUsers,
            "io.gatling.app.Gatling",
            "-s",
            "com.gimle.smoketests.load.GreeterAutoscaleSimulation",
            "-rf",
            tempDir.resolve("gatling-results").toString(),
            "-nr");
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    return pb.start();
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
   * Like {@link #submitDeployment(String, String, String, Path, Optional)}, but for a submission
   * expected to be rejected at admission -- returns the raw response instead of failing on a
   * non-200, so the caller can assert on the rejection itself ({@code ApiServer#checkTenantQuota}'s
   * own 409, in this suite's case).
   */
  private HttpResponse<String> submitDeploymentExpectingRejection(
      String baseUrl, String deploymentName, String moduleName, Path jar, String tenantId)
      throws Exception {
    String manifest =
        """
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        replicas: 1
        tenantId: %s
        """
            .formatted(deploymentName, moduleName, jar.toAbsolutePath(), tenantId);
    return httpClient.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + deploymentName))
            .PUT(HttpRequest.BodyPublishers.ofString(manifest, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
