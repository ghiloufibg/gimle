package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import com.gimle.mimir.raft.PeerAddress;
import com.gimle.mimir.rpc.StoreClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Raft/store-cluster resilience under real process failure: losing one store node mid-deployment, a
 * leader failover under real concurrent writes (no acknowledged write ever lost), and etcd-style
 * live membership change (a 4th node joining and leaving a live cluster).
 */
@Tag("smoke")
class RaftResilienceIT extends GreeterSmokeClusterSupport {

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
   * <p>This reproduction previously found and fixed two real bugs before it could pass reliably.
   * First, a self-elected phantom leader: a standalone store node started with no bootstrap peers
   * self-elects leader of its own one-node "cluster" immediately (see {@link
   * com.gimle.mimir.raft.RaftNode#start}'s own javadoc), and {@code onAppendEntries}/{@code
   * onInstallSnapshot} only ever demoted a {@code CANDIDATE} back to follower on an equal-term
   * message, not a stray same-term {@code LEADER} -- fixed by {@code demoteToFollowerLocked}
   * handling any non-follower role, not just candidate (see that method's own javadoc). Second, and
   * the deeper cause of the multi-minute leader-instability window this test used to observe: a
   * newly {@code addServer}-ed peer became a full voting member -- and thus part of election/commit
   * quorum arithmetic -- the instant it joined, before its log had caught up. A lagging log can
   * never win an election (the log up-to-date check in {@code onRequestVote} forbids it), but
   * nothing stopped it from *campaigning*: a `RequestVote` at a higher term makes every real voter,
   * including a perfectly healthy leader, bump its own term and step down before that check is even
   * consulted, so a slow-to-catch-up new peer that simply times out mid-catch-up could repeatedly
   * disrupt the whole cluster without ever coming close to winning itself. Fixed by the classic
   * Raft answer: a new peer now joins as a non-voting learner (excluded from both election and
   * commit quorum, and forbidden from campaigning itself) until its replicated log catches up, at
   * which point it is promoted to a full voting member automatically.
   */
  @Test
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
        // 150s, not the usual 90s this suite's other post-disruption awaits use: the new peer
        // joins as a non-voting learner and must fully catch up its log via real AppendEntries/
        // InstallSnapshot replication before it's promoted to a full voting member -- slower
        // than a single node loss's own 90s budget, which needs no catch-up at all, so this stays
        // generous rather than tightened to whatever this fix happens to measure in one sandbox's
        // own load at any given time.
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
}
