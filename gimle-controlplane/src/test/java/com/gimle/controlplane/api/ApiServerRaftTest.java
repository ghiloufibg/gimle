package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.mimir.raft.PeerConnection;
import com.gimle.mimir.raft.RaftLog;
import com.gimle.mimir.raft.RaftNode;
import com.gimle.mimir.raft.RaftPeerClient;
import com.gimle.mimir.raft.RaftTransport;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.rpc.StoreNode;
import com.gimle.mimir.rpc.StoreTransport;
import com.gimle.mimir.store.StateStore;
import com.gimle.testkit.Await;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * A real M-node {@code gimle-mimir} store cluster (real Raft over real sockets) behind N real
 * {@link ApiServer} replicas, each with its own {@link StoreClient} pointed at every store endpoint
 * -- the decoupled N:M topology that resulted from splitting the state store out into its own
 * process, independent of the control plane's own replica count. A write through *any* {@code
 * ApiServer} replica now just succeeds, regardless of which store node currently holds Raft
 * leadership: {@code StoreClient} follows the leader internally, so there is no
 * 307-redirect-to-a-peer-{@code ApiServer} behavior left to test -- that mechanism only ever made
 * sense when one {@code ApiServer} was colocated 1:1 with one Raft member, which stopped being true
 * the moment this split landed.
 */
// See ApiServerTest for why: real ApiServer + real HttpClient on a loopback ephemeral port,
// excluded from running concurrently with any other class doing the same.
@ResourceLock("gimle-controlplane-api-server-http")
// See ApiServerTest for why: ApiServer#start reads gimle.transport.protocol at construction
// time, so this class is exposed to it even though it never writes it itself.
@ResourceLock(Resources.SYSTEM_PROPERTIES)
// Real multi-node Raft cluster with real leader-election timing (awaitLeader/Await.until against
// wall-clock @Timeout budgets), the same CPU-contention flakiness RaftClusterTest had -- see its
// own comment. @Isolated makes the other two locks on this class redundant but harmless.
@Isolated
class ApiServerRaftTest {

  @TempDir Path tempDir;

  private final List<RaftNode> storeRaftNodes = new ArrayList<>();
  private final List<RaftTransport> storeRaftTransports = new ArrayList<>();
  private final List<StoreTransport> storeTransports = new ArrayList<>();
  private final List<StoreClient> storeClients = new ArrayList<>();
  private final List<ApiServer> apiServers = new ArrayList<>();
  private final HttpClient client = HttpClient.newHttpClient();
  private int clusterCounter;
  // One shared Fafnir replica for every ApiServer replica this class builds -- mirrors how a real
  // deployment points every control-plane replica at the same Fafnir replica set, rather than each
  // ApiServer replica getting its own isolated Fafnir (which would repeat exactly the per-replica
  // key-file mismatch bug this whole extraction exists to fix). Lazily started on first use since
  // not every test method in this class calls buildApiServers.
  private InProcessFafnir inProcessFafnir;

  private record StoreClusterNode(
      String raftId,
      RaftNode raftNode,
      RaftTransport raftTransport,
      StoreTransport storeTransport,
      SocketAddress clientAddress) {

    /** Simulates a hard machine crash: nothing about this node keeps answering afterward. */
    void kill() {
      storeTransport.close();
      raftTransport.close();
      raftNode.close();
    }
  }

  @AfterEach
  void tearDown() {
    for (ApiServer server : apiServers) {
      server.close();
    }
    if (inProcessFafnir != null) {
      inProcessFafnir.close();
    }
    for (StoreClient storeClient : storeClients) {
      storeClient.close();
    }
    for (StoreTransport transport : storeTransports) {
      transport.close();
    }
    for (RaftNode node : storeRaftNodes) {
      node.close();
    }
    for (RaftTransport transport : storeRaftTransports) {
      transport.close();
    }
  }

  private static InetSocketAddress reserveAddress() throws IOException {
    try (ServerSocketChannel probe = ServerSocketChannel.open()) {
      probe.bind(new InetSocketAddress("127.0.0.1", 0));
      return (InetSocketAddress) probe.getLocalAddress();
    }
  }

  /** Mirrors {@code StoreClientClusterTest}'s own cluster builder (gimle-mimir). */
  private List<StoreClusterNode> buildStoreCluster(int n) throws IOException {
    List<String> ids = new ArrayList<>();
    List<InetSocketAddress> raftAddresses = new ArrayList<>();
    List<InetSocketAddress> clientAddresses = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      ids.add("store-" + i);
      raftAddresses.add(reserveAddress());
      clientAddresses.add(reserveAddress());
    }
    Map<String, String> raftIdToClientAddress = new HashMap<>();
    for (int i = 0; i < n; i++) {
      String raftId = ids.get(i) + ":" + raftAddresses.get(i).getPort();
      raftIdToClientAddress.put(
          raftId, clientAddresses.get(i).getHostString() + ":" + clientAddresses.get(i).getPort());
    }

    List<StoreClusterNode> clusterNodes = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Map<String, RaftPeerClient> peers = new HashMap<>();
      for (int j = 0; j < n; j++) {
        if (j == i) {
          continue;
        }
        String peerRaftId = ids.get(j) + ":" + raftAddresses.get(j).getPort();
        peers.put(peerRaftId, new PeerConnection(raftAddresses.get(j)));
      }

      Path dir = tempDir.resolve("store-cluster-" + (clusterCounter++) + "-node-" + i);
      StateStore store = new StateStore();
      RaftLog raftLog = new RaftLog(dir.resolve("raft"));
      String selfRaftId = ids.get(i) + ":" + raftAddresses.get(i).getPort();
      RaftNode raftNode = new RaftNode(selfRaftId, peers, raftLog, store);
      RaftTransport raftTransport = new RaftTransport(raftNode);
      storeRaftTransports.add(raftTransport);
      raftTransport.listen(raftAddresses.get(i));
      raftNode.start();
      storeRaftNodes.add(raftNode);

      StoreNode storeNode = new StoreNode(raftNode, store, raftIdToClientAddress);
      StoreTransport storeTransport = new StoreTransport(storeNode);
      storeTransports.add(storeTransport);
      storeTransport.listen(clientAddresses.get(i));

      clusterNodes.add(
          new StoreClusterNode(
              selfRaftId, raftNode, raftTransport, storeTransport, clientAddresses.get(i)));
    }
    return clusterNodes;
  }

  /**
   * N {@code ApiServer} replicas, each its own {@link StoreClient} against every store endpoint.
   */
  private List<ApiServer> buildApiServers(int n, List<StoreClusterNode> storeCluster)
      throws IOException {
    List<SocketAddress> storeEndpoints =
        storeCluster.stream().map(StoreClusterNode::clientAddress).toList();
    if (inProcessFafnir == null) {
      StoreClient fafnirStoreClient = new StoreClient(storeEndpoints);
      storeClients.add(fafnirStoreClient);
      inProcessFafnir =
          InProcessFafnir.start(fafnirStoreClient, tempDir.resolve("fafnir-secret.key"));
    }
    List<ApiServer> servers = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      StoreClient storeClient = new StoreClient(storeEndpoints);
      storeClients.add(storeClient);
      ApiServer apiServer =
          new ApiServer(
              storeClient, 0, tempDir.resolve("secret-" + i + ".key"), inProcessFafnir.client());
      apiServer.start();
      apiServers.add(apiServer);
      servers.add(apiServer);
    }
    return servers;
  }

  private static StoreClusterNode awaitStoreLeader(List<StoreClusterNode> cluster) {
    Await.until(
        () -> cluster.stream().anyMatch(c -> c.raftNode().isLeader()), Duration.ofSeconds(5));
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
  void a_write_through_any_api_server_replica_succeeds_and_is_visible_through_every_replica()
      throws Exception {
    List<StoreClusterNode> storeCluster = buildStoreCluster(3);
    awaitStoreLeader(storeCluster);
    List<ApiServer> replicas = buildApiServers(2, storeCluster);

    HttpResponse<String> put =
        client.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + replicas.get(0).port() + "/tenants/t1"))
                .PUT(HttpRequest.BodyPublishers.ofString(tenantJson(1024, 500, 10)))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, put.statusCode());

    Await.until(
        () ->
            replicas.stream()
                .allMatch(
                    replica -> {
                      try {
                        HttpResponse<String> get =
                            client.send(
                                HttpRequest.newBuilder(
                                        URI.create(
                                            "http://localhost:" + replica.port() + "/tenants/t1"))
                                    .GET()
                                    .build(),
                                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                        return get.statusCode() == 200;
                      } catch (Exception e) {
                        return false;
                      }
                    }),
        Duration.ofSeconds(3));
  }

  @Test
  @Timeout(15)
  void a_heartbeat_through_any_api_server_replica_succeeds() throws Exception {
    List<StoreClusterNode> storeCluster = buildStoreCluster(3);
    awaitStoreLeader(storeCluster);
    List<ApiServer> replicas = buildApiServers(2, storeCluster);

    String heartbeatBody =
        """
        {"capacity": {"totalMemoryBytes": 1, "assignedMemoryBytes": 0, "totalCpuMillicores": 1, \
        "assignedCpuMillicores": 0}, "instances": []}
        """;
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:" + replicas.get(1).port() + "/nodes/agent-1/heartbeat"))
                .POST(HttpRequest.BodyPublishers.ofString(heartbeatBody))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode());
  }

  @Test
  @Timeout(30)
  void
      writes_keep_succeeding_through_the_same_api_server_replica_across_a_forced_store_leader_failover()
          throws Exception {
    List<StoreClusterNode> storeCluster = buildStoreCluster(3);
    StoreClusterNode firstStoreLeader = awaitStoreLeader(storeCluster);
    List<ApiServer> replicas = buildApiServers(2, storeCluster);
    ApiServer replica = replicas.get(0);
    String baseUrl = "http://localhost:" + replica.port();

    // Same tenant id before and after -- the second PUT is an update to an already-existing
    // tenant, not a second real tenant, so it stays permitted under plaintext's single-tenant
    // posture. This test is about write continuity across a leader failover, not multi-tenancy.
    HttpResponse<String> before =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/failover-tenant"))
                .PUT(HttpRequest.BodyPublishers.ofString(tenantJson(1, 1, 1)))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, before.statusCode());

    firstStoreLeader.kill();
    List<StoreClusterNode> remaining =
        storeCluster.stream().filter(c -> c != firstStoreLeader).toList();
    awaitStoreLeader(remaining);

    HttpResponse<String> after =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/failover-tenant"))
                .PUT(HttpRequest.BodyPublishers.ofString(tenantJson(2, 2, 2)))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, after.statusCode());
  }
}
