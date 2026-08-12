package com.gimle.smoketests;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * QA hardening pass, plaintext-cluster coverage: gossip/SWIM failure detection -- the fourth and
 * last of the plaintext-mode real-cluster gaps identified this session. Every other smoke test in
 * this package runs a single-node topology ({@link #startCluster} spawns exactly one real {@code
 * AgentMain}, hardcoded {@code smoke-node-1} with no other seeds), so {@code GossipMember}'s real
 * membership/failure-detection machinery -- proven only by {@code GossipMemberTest}'s in-process
 * fakes -- has never actually run across real, separate agent processes before.
 *
 * <p>Three nodes, not two, deliberately: with only two, {@code GossipMember}'s indirect ping-req
 * relay path is never exercised (no third node to route a probe through), the same shape the
 * reference unit test ({@code GossipMemberTest #a_killed_member_converges_to_dead_across_the_rest})
 * already uses. Both node 2 and node 3 are seeded off node 1 alone -- SWIM's own full-state
 * anti-entropy sync (P2-8) is what's relied on to converge the complete 3-node table without
 * seeding every node off every other one directly.
 */
@Tag("smoke")
class GossipFailureDetectionIT extends GreeterSmokeClusterSupport {

  private static final String NODE2_ID = "smoke-node-2";
  private static final String NODE3_ID = "smoke-node-3";

  @Test
  @Timeout(value = 4, unit = TimeUnit.MINUTES)
  void a_hard_killed_member_converges_to_dead_on_both_surviving_real_agents() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);
    // Same Fafnir replica node 1's own agent was already given -- Fafnir replicas are shared
    // cluster infrastructure, not agent-exclusive, and neither of these two extra nodes ever hosts
    // a deployment that would need one anyway.
    String fafnirEndpoint = "127.0.0.1:" + FAFNIR_PORT_BASE;

    // startCluster itself returns the instant node 1's process is forked, with no wait for
    // anything about the agent specifically (only the control plane's own HTTP port). Real-cluster
    // finding: spawning node 2/3 immediately after races node 1's own gossip listener bind --
    // AgentMain's main() only registers with the control plane *after* GossipMember#start/#join
    // both return, so waiting for node 1's own registration is a reliable proxy for "its gossip
    // socket is definitely open," the same technique already used below for node 2 and node 3
    // themselves.
    await(
        () -> nodeRegistered(baseUrl, "smoke-node-1"),
        Duration.ofSeconds(30),
        "smoke-node-1 should register with the control plane before any other node tries to seed"
            + " off its gossip address");

    Path node1Log = tempDir.resolve("agent.log");
    Path node2Log = tempDir.resolve("agent-2.log");
    Path node3Log = tempDir.resolve("agent-3.log");

    Process agent2 =
        spawnAgent(
            javaExecutable,
            classpath,
            NODE2_ID,
            GOSSIP_ADDRESS_NODE2,
            GOSSIP_ADDRESS,
            baseUrl,
            fafnirEndpoint,
            cluster.muninnEndpoint(),
            node2Log);
    processes.add(agent2);
    Process agent3 =
        spawnAgent(
            javaExecutable,
            classpath,
            NODE3_ID,
            GOSSIP_ADDRESS_NODE3,
            GOSSIP_ADDRESS,
            baseUrl,
            fafnirEndpoint,
            cluster.muninnEndpoint(),
            node3Log);
    processes.add(agent3);

    await(
        () -> nodeRegistered(baseUrl, NODE2_ID),
        Duration.ofSeconds(30),
        NODE2_ID + " should register with the control plane once its own gossip join completes");
    await(
        () -> nodeRegistered(baseUrl, NODE3_ID),
        Duration.ofSeconds(30),
        NODE3_ID + " should register with the control plane once its own gossip join completes");

    // Both new nodes only ever seed off node 1 directly, never off each other -- this is real
    // waiting time for SWIM's own periodic anti-entropy (GossipConfig.defaults()'s 1s protocol
    // period) to propagate the full 3-node table between them, not something pollable: no HTTP
    // endpoint anywhere in this codebase exposes gossip/membership status (confirmed by grep of
    // ApiServer and AgentMain).
    Thread.sleep(Duration.ofSeconds(5).toMillis());

    killWithDescendants(agent3);

    // Worked through the state machine (round-robin target selection -> direct probe timeout ->
    // indirect escalation -> suspicion timeout -> dead), theoretical worst case with
    // GossipConfig.defaults() is roughly 8-10s, and measured real-cluster runs land in that same
    // 4-11s range -- a 60s budget is generous real-sandbox headroom on top of that, matching this
    // session's own established pattern (e.g. ClassloaderLeakIT's 90s window) for real
    // multi-process timing under contention.
    await(
        () -> agentLogContains(node1Log, "member " + NODE3_ID + " is now DEAD"),
        Duration.ofSeconds(60),
        "node 1's own gossip member should detect and log " + NODE3_ID + " as DEAD");
    // Real-cluster QA finding (fixed in GossipMember#mergeOne, see QA_FINDINGS.md): a node that
    // learns of a peer's death secondhand via gossip rather than detecting it directly used to
    // adopt the DEAD status into its own membership table completely silently, with no log line
    // at all -- this assertion is what surfaced that gap, not just what now proves it fixed.
    await(
        () -> agentLogContains(node2Log, "member " + NODE3_ID + " is now DEAD"),
        Duration.ofSeconds(60),
        "node 2's own gossip member should also detect and log "
            + NODE3_ID
            + " as DEAD -- proving the failure was gossiped out to the whole cluster, not just"
            + " observed by whichever node happened to probe it directly");
  }
}
