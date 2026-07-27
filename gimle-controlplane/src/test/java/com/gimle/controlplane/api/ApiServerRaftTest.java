package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.raft.PeerConnection;
import com.gimle.controlplane.raft.RaftLog;
import com.gimle.controlplane.raft.RaftNode;
import com.gimle.controlplane.raft.RaftPeerClient;
import com.gimle.controlplane.raft.RaftTransport;
import com.gimle.controlplane.store.StateStore;
import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A real 3-node cluster of {@link ApiServer}s over real HTTP, each backed by a real Raft loopback
 * cluster (design §2.6): a write to the leader succeeds and replicates; a write to a follower comes
 * back as a {@code 307} naming the correct leader; a heartbeat against a non-leader is rejected the
 * same way even though it never touches the Raft log at all (design §2.1).
 */
class ApiServerRaftTest {

  @TempDir Path tempDir;

  private final List<RaftNode> raftNodes = new ArrayList<>();
  private final List<RaftTransport> raftTransports = new ArrayList<>();
  private final List<ApiServer> apiServers = new ArrayList<>();
  private final HttpClient client = HttpClient.newHttpClient();

  private record ClusterNode(String id, RaftNode raftNode, ApiServer apiServer, StateStore store) {}

  @AfterEach
  void tearDown() {
    for (ApiServer server : apiServers) {
      server.close();
    }
    for (RaftNode node : raftNodes) {
      node.close();
    }
    for (RaftTransport transport : raftTransports) {
      transport.close();
    }
  }

  private static int reservePort() throws IOException {
    try (ServerSocketChannel probe = ServerSocketChannel.open()) {
      probe.bind(new InetSocketAddress("127.0.0.1", 0));
      return ((InetSocketAddress) probe.getLocalAddress()).getPort();
    }
  }

  private List<ClusterNode> buildCluster(int n) throws IOException {
    List<Integer> raftPorts = new ArrayList<>();
    List<Integer> apiPorts = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      raftPorts.add(reservePort());
      apiPorts.add(reservePort());
    }

    List<ClusterNode> clusterNodes = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Map<String, RaftPeerClient> peers = new HashMap<>();
      Map<String, String> peerApiAddresses = new HashMap<>();
      for (int j = 0; j < n; j++) {
        if (j == i) {
          continue;
        }
        String peerRaftId = "127.0.0.1:" + raftPorts.get(j);
        peers.put(
            peerRaftId, new PeerConnection(new InetSocketAddress("127.0.0.1", raftPorts.get(j))));
        peerApiAddresses.put(peerRaftId, "localhost:" + apiPorts.get(j));
      }

      Path dir = tempDir.resolve("node-" + i);
      StateStore store = new StateStore(dir.resolve("store"));
      RaftLog raftLog = new RaftLog(dir.resolve("raft"));
      String selfId = "127.0.0.1:" + raftPorts.get(i);
      RaftNode raftNode = new RaftNode(selfId, peers, raftLog, store);
      raftNodes.add(raftNode);
      RaftTransport transport = new RaftTransport(raftNode);
      raftTransports.add(transport);
      transport.listen(new InetSocketAddress("127.0.0.1", raftPorts.get(i)));
      raftNode.start();

      ApiServer apiServer =
          new ApiServer(
              store, apiPorts.get(i), dir.resolve("secret.key"), raftNode, peerApiAddresses);
      apiServer.start();
      apiServers.add(apiServer);

      clusterNodes.add(new ClusterNode("node-" + i, raftNode, apiServer, store));
    }
    return clusterNodes;
  }

  private static void awaitTrue(BooleanSupplier condition, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(20);
    }
    assertTrue(condition.getAsBoolean(), "condition not met within " + timeout);
  }

  private static ClusterNode awaitLeader(List<ClusterNode> cluster) throws InterruptedException {
    awaitTrue(() -> cluster.stream().anyMatch(c -> c.raftNode().isLeader()), Duration.ofSeconds(5));
    return cluster.stream().filter(c -> c.raftNode().isLeader()).findFirst().orElseThrow();
  }

  private static String tenantJson(long maxMemoryBytes, long maxCpuMillicores, int maxInstances) {
    return """
        {"quota": {"maxMemoryBytes": %d, "maxCpuMillicores": %d, "maxInstances": %d}}
        """
        .formatted(maxMemoryBytes, maxCpuMillicores, maxInstances);
  }

  @Test
  @Timeout(15)
  void a_write_to_the_leader_succeeds_and_becomes_visible_on_every_replica() throws Exception {
    List<ClusterNode> cluster = buildCluster(3);
    ClusterNode leader = awaitLeader(cluster);
    String baseUrl = "http://localhost:" + leader.apiServer().port();

    HttpResponse<String> put =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/t1"))
                .PUT(HttpRequest.BodyPublishers.ofString(tenantJson(1024, 500, 10)))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, put.statusCode());

    awaitTrue(
        () -> cluster.stream().allMatch(c -> c.store().getTenant("t1").isPresent()),
        Duration.ofSeconds(3));
  }

  @Test
  @Timeout(15)
  void a_write_to_a_follower_returns_307_with_the_leaders_address_and_a_json_body()
      throws Exception {
    List<ClusterNode> cluster = buildCluster(3);
    ClusterNode leader = awaitLeader(cluster);
    ClusterNode follower = cluster.stream().filter(c -> c != leader).findFirst().orElseThrow();
    String followerUrl = "http://localhost:" + follower.apiServer().port();

    HttpResponse<String> put =
        client.send(
            HttpRequest.newBuilder(URI.create(followerUrl + "/tenants/t2"))
                .PUT(HttpRequest.BodyPublishers.ofString(tenantJson(1, 1, 1)))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(307, put.statusCode());
    assertTrue(put.headers().firstValue("Location").isPresent());
    assertTrue(
        put.headers()
            .firstValue("Location")
            .get()
            .contains(String.valueOf(leader.apiServer().port())));
    Map<String, Object> body = Json.asObject(Json.parse(put.body()));
    assertEquals("not-leader", body.get("error"));
    assertNotNull(body.get("leaderApiAddress"));
  }

  @Test
  @Timeout(15)
  void a_heartbeat_against_a_non_leader_is_rejected_without_touching_the_raft_log()
      throws Exception {
    List<ClusterNode> cluster = buildCluster(3);
    ClusterNode leader = awaitLeader(cluster);
    ClusterNode follower = cluster.stream().filter(c -> c != leader).findFirst().orElseThrow();
    String followerUrl = "http://localhost:" + follower.apiServer().port();

    String heartbeatBody =
        """
        {"capacity": {"totalMemoryBytes": 1, "assignedMemoryBytes": 0, "totalCpuMillicores": 1, \
        "assignedCpuMillicores": 0}, "instances": []}
        """;
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(followerUrl + "/nodes/agent-1/heartbeat"))
                .POST(HttpRequest.BodyPublishers.ofString(heartbeatBody))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(307, response.statusCode());
    assertTrue(follower.store().getNodeHeartbeat("agent-1").isEmpty());
  }
}
