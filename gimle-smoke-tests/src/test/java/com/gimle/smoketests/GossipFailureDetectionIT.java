package com.gimle.smoketests;

import com.gimle.core.protocol.Json;
import com.gimle.testkit.Await;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Gossip/SWIM failure detection -- the fourth and last of the plaintext-mode real-cluster gaps left
 * uncovered elsewhere in this package. Every other smoke test in this package runs a single-node
 * topology ({@link #startCluster} spawns exactly one real {@code AgentMain}, hardcoded {@code
 * smoke-node-1} with no other seeds), so {@code GossipMember}'s real membership/failure-detection
 * machinery -- proven only by {@code GossipMemberTest}'s in-process fakes -- has never actually run
 * across real, separate agent processes before.
 *
 * <p>Three nodes, not two, deliberately: with only two, {@code GossipMember}'s indirect ping-req
 * relay path is never exercised (no third node to route a probe through), the same shape the
 * reference unit test ({@code GossipMemberTest #a_killed_member_converges_to_dead_across_the_rest})
 * already uses. Both node 2 and node 3 are seeded off node 1 alone -- SWIM's own full-state
 * anti-entropy sync is what's relied on to converge the complete 3-node table without seeding every
 * node off every other one directly.
 */
@Tag("smoke")
class GossipFailureDetectionIT extends GreeterSmokeClusterSupport {

  private static final String NODE1_ID = "smoke-node-1";
  private static final String NODE2_ID = "smoke-node-2";
  private static final String NODE3_ID = "smoke-node-3";

  // AgentGossipServer binds to :0, so its port is never known ahead of spawn and never registered
  // with the control plane -- AgentMain only ever logs it, so parsing that startup line out of the
  // agent's own redirected stdout is the only way to learn which port to poll.
  private static final Pattern GOSSIP_HTTP_PORT_PATTERN =
      Pattern.compile("serving gossip membership at :(\\d+)");

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
    String fafnirEndpoint = "127.0.0.1:" + fafnirPorts.get(0);

    // startCluster itself returns the instant node 1's process is forked, with no wait for
    // anything about the agent specifically (only the control plane's own HTTP port). Real-cluster
    // finding: spawning node 2/3 immediately after races node 1's own gossip listener bind --
    // AgentMain's main() only registers with the control plane *after* GossipMember#start/#join
    // both return, so waiting for node 1's own registration is a reliable proxy for "its gossip
    // socket is definitely open," the same technique already used below for node 2 and node 3
    // themselves.
    Await.until(
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
            gossipAddressNode2,
            gossipAddress,
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
            gossipAddressNode3,
            gossipAddress,
            baseUrl,
            fafnirEndpoint,
            cluster.muninnEndpoint(),
            node3Log);
    processes.add(agent3);

    Await.until(
        () -> nodeRegistered(baseUrl, NODE2_ID),
        Duration.ofSeconds(30),
        NODE2_ID + " should register with the control plane once its own gossip join completes");
    Await.until(
        () -> nodeRegistered(baseUrl, NODE3_ID),
        Duration.ofSeconds(30),
        NODE3_ID + " should register with the control plane once its own gossip join completes");

    // Both new nodes only ever seed off node 1 directly, never off each other -- what's being
    // waited for here is SWIM's own periodic anti-entropy (GossipConfig.defaults()'s 1s protocol
    // period) propagating the full 3-node table between them, observed directly through each
    // agent's own /gossip/members endpoint rather than guessed at with a fixed sleep.
    Await.until(
        () -> gossipTableIncludesAllNodes(node2Log) && gossipTableIncludesAllNodes(node3Log),
        Duration.ofSeconds(30),
        "node 2 and node 3 should each converge, via SWIM anti-entropy seeded only through node 1,"
            + " to a gossip table containing all three nodes before node 3 is killed");

    killWithDescendants(agent3);

    // Worked through the state machine (round-robin target selection -> direct probe timeout ->
    // indirect escalation -> suspicion timeout -> dead), theoretical worst case with
    // GossipConfig.defaults() is roughly 8-10s, and measured real-cluster runs land in that same
    // 4-11s range -- a 60s budget is generous real-sandbox headroom on top of that, matching this
    // session's own established pattern (e.g. ClassloaderLeakIT's 90s window) for real
    // multi-process timing under contention.
    Await.until(
        () -> agentLogContains(node1Log, "member " + NODE3_ID + " is now DEAD"),
        Duration.ofSeconds(60),
        "node 1's own gossip member should detect and log " + NODE3_ID + " as DEAD");
    // Real-cluster finding, fixed in GossipMember#mergeOne: a node that learns of a peer's death
    // secondhand via gossip rather than detecting it directly used to adopt the DEAD status into
    // its own membership table completely silently, with no log line at all -- this assertion is
    // what surfaced that gap, not just what now proves it fixed.
    Await.until(
        () -> agentLogContains(node2Log, "member " + NODE3_ID + " is now DEAD"),
        Duration.ofSeconds(60),
        "node 2's own gossip member should also detect and log "
            + NODE3_ID
            + " as DEAD -- proving the failure was gossiped out to the whole cluster, not just"
            + " observed by whichever node happened to probe it directly");
  }

  /**
   * True once the agent whose redirected stdout is {@code logFile} reports, via its own real {@code
   * GET /gossip/members}, a table that already includes all three nodes -- the actual SWIM
   * anti-entropy convergence this test needs before killing node 3, not a guessed-at fixed delay.
   */
  private boolean gossipTableIncludesAllNodes(Path logFile) {
    OptionalInt port = gossipHttpPort(logFile);
    if (port.isEmpty()) {
      return false;
    }
    try {
      HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + port.getAsInt() + "/gossip/members"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        return false;
      }
      Map<String, Object> body = Json.asObject(Json.parse(response.body()));
      List<Map<String, Object>> members = Json.asObjectList(body.get("members"));
      boolean sawNode1 = false;
      boolean sawNode2 = false;
      boolean sawNode3 = false;
      for (Map<String, Object> member : members) {
        String nodeId = String.valueOf(member.get("nodeId"));
        sawNode1 = sawNode1 || NODE1_ID.equals(nodeId);
        sawNode2 = sawNode2 || NODE2_ID.equals(nodeId);
        sawNode3 = sawNode3 || NODE3_ID.equals(nodeId);
      }
      return sawNode1 && sawNode2 && sawNode3;
    } catch (IOException | InterruptedException | RuntimeException e) {
      return false;
    }
  }

  private OptionalInt gossipHttpPort(Path logFile) {
    try {
      Matcher matcher =
          GOSSIP_HTTP_PORT_PATTERN.matcher(Files.readString(logFile, StandardCharsets.UTF_8));
      return matcher.find()
          ? OptionalInt.of(Integer.parseInt(matcher.group(1)))
          : OptionalInt.empty();
    } catch (IOException e) {
      return OptionalInt.empty();
    }
  }
}
