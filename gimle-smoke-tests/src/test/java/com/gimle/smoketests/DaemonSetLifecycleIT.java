package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.testkit.Await;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real-cluster coverage for {@code kind: DaemonSet} -- previously proven only by {@code
 * DaemonSetReconcilerTest}'s in-memory-store unit tests, never against real, separate node agent
 * processes. A DaemonSet's whole point is reacting to real node lifecycle (placing on every
 * eligible node, cleaning up after a node that stops being eligible), which is exactly what {@link
 * NodeCordoningIT}/{@link GossipFailureDetectionIT} already prove this fixture can drive for real
 * -- this class does the same for DaemonSet specifically: a genuine second real {@code AgentMain}
 * (mirroring {@link GossipFailureDetectionIT}'s multi-agent pattern) so per-node fan-out is
 * observable at all, then a real, hard kill of that second node's agent (mirroring {@code
 * SelfHealingIT}'s real-process-kill pattern) to prove the dead node's own assignment is actually
 * cleaned up, not just left stale once its heartbeat goes dark.
 */
@Tag("smoke")
class DaemonSetLifecycleIT extends GreeterSmokeClusterSupport {

  private static final String NODE2_ID = "smoke-node-2";

  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void a_daemonset_places_on_every_node_and_a_dead_nodes_assignment_is_cleaned_up()
      throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);
    String fafnirEndpoint = "127.0.0.1:" + fafnirPorts.get(0);

    // Same reasoning as GossipFailureDetectionIT: wait for node 1's own registration before
    // seeding a second agent off its gossip address, so the second agent's own join never races
    // node 1's gossip listener still binding.
    Await.until(
        () -> nodeRegistered(baseUrl, "smoke-node-1"),
        Duration.ofSeconds(30),
        "smoke-node-1 should register with the control plane before a second node seeds off it");

    Process agent2 =
        spawnAgent(
            javaExecutable,
            classpath,
            NODE2_ID,
            gossipAddressNode2,
            gossipAddress,
            baseUrl,
            fafnirEndpoint,
            cluster.muninnEndpoint(),
            tempDir.resolve("agent-2.log"));
    processes.add(agent2);

    Await.until(
        () -> nodeRegistered(baseUrl, NODE2_ID),
        Duration.ofSeconds(30),
        NODE2_ID + " should register with the control plane once its own gossip join completes");

    String moduleName = "com.gimle.fixture.daemonsetprobe";
    Path jar = buildInertTier1ModuleJar(moduleName, "1.0.0");
    submitDaemonSetWithRetry(
        baseUrl, "probe-daemonset", moduleName, "1.0.0", jar, Duration.ofSeconds(30));

    Await.until(
        () -> daemonSetActiveNodeCount(baseUrl, "probe-daemonset") == 2,
        Duration.ofSeconds(60),
        "probe-daemonset should place one ACTIVE instance on each of the two real nodes");
    assertTrue(daemonSetHasNodeAssignment(baseUrl, "probe-daemonset", "smoke-node-1"));
    assertTrue(daemonSetHasNodeAssignment(baseUrl, "probe-daemonset", NODE2_ID));

    // A genuine, hard node death -- not a cordon -- so this actually exercises the same
    // node-dark-timeout path GossipFailureDetectionIT/SelfHealingIT already prove for gossip
    // convergence and worker respawn, but for DaemonSetReconciler's own eligibleNodes-driven
    // cleanup specifically.
    killWithDescendants(agent2);

    // NODE_DARK_TIMEOUT (15s, ControlPlaneMain) before the dead node even stops being a placement
    // candidate, plus real reconcile ticks (RECONCILE_INTERVAL = 2s) after that before
    // DaemonSetReconciler actually removes the now-orphaned assignment -- 90s is generous
    // real-sandbox headroom on top of both, matching this suite's own established pattern for
    // real multi-process timing under contention.
    Await.until(
        () -> daemonSetActiveNodeCount(baseUrl, "probe-daemonset") == 1,
        Duration.ofSeconds(90),
        "probe-daemonset should drop back to exactly one ACTIVE instance once "
            + NODE2_ID
            + " goes dark and its own assignment is cleaned up");
    assertTrue(
        daemonSetHasNodeAssignment(baseUrl, "probe-daemonset", "smoke-node-1"),
        "the surviving node's own instance must be untouched by the dead node's cleanup");
    assertTrue(
        !daemonSetHasNodeAssignment(baseUrl, "probe-daemonset", NODE2_ID),
        NODE2_ID + "'s own assignment should have been removed, not just gone quiet");
  }
}
