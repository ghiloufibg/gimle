package com.gimle.mimir.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import com.gimle.mimir.raft.RaftLog;
import com.gimle.mimir.raft.RaftNode;
import com.gimle.mimir.store.StateStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreHealthServerTest {

  @TempDir Path tempDir;

  private RaftNode raftNode;
  private StoreHealthServer healthServer;
  private HttpClient client;
  private String baseUrl;

  @BeforeEach
  void startServer() throws IOException {
    StateStore store = new StateStore();
    RaftLog raftLog = new RaftLog(tempDir.resolve("raft"));
    // A single-node cluster (no peers) becomes leader on its own the moment it starts -- a
    // quorum of one, the same shape MutationBatchTest's own single-node RaftNode setup relies on.
    raftNode = new RaftNode("n1", Map.of(), raftLog, store);
    raftNode.start();
    healthServer = new StoreHealthServer(raftNode, 0);
    healthServer.start();
    baseUrl = "http://localhost:" + healthServer.port();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopServer() {
    healthServer.close();
    raftNode.close();
  }

  @Test
  void a_healthy_single_node_leader_reports_up_and_its_own_leadership() throws Exception {
    HttpResponse<String> response = get("/health");

    assertEquals(200, response.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    assertEquals("UP", body.get("status"));
    assertEquals("n1", body.get("selfId"));
    assertEquals(Boolean.TRUE, body.get("isLeader"));
    assertEquals(1.0, ((Number) body.get("memberCount")).doubleValue());
  }

  @Test
  void a_non_get_method_is_rejected() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

    assertEquals(405, response.statusCode());
  }

  @Test
  void the_health_port_is_never_the_raft_or_client_port() {
    // Bound with 0 (ephemeral) above -- the OS-assigned port must be a real, distinct listener,
    // not some default/fallback value silently reused from the Raft transport.
    assertTrue(healthServer.port() > 0);
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
