package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.protocol.Json;
import com.gimle.fabric.cluster.GossipConfig;
import com.gimle.fabric.cluster.GossipMember;
import com.gimle.fabric.cluster.MemberId;
import com.gimle.fabric.cluster.MemberStatus;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Contract test for the agent's read-only gossip-membership HTTP surface: the JSON shape a caller
 * -- console, CLI, or a future test asserting on real SWIM convergence without a blind sleep --
 * reads this node's own current membership table through.
 */
class AgentGossipServerTest {

  private static final GossipConfig FAST_CONFIG =
      new GossipConfig(
          Duration.ofMillis(80),
          Duration.ofMillis(40),
          Duration.ofMillis(200),
          2,
          6,
          Duration.ofSeconds(30),
          Duration.ofSeconds(30));

  private GossipMember gossipMember;
  private GossipMember peer;
  private AgentGossipServer server;
  private final HttpClient httpClient = HttpClient.newHttpClient();

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
    if (gossipMember != null) {
      gossipMember.close();
    }
    if (peer != null) {
      peer.close();
    }
  }

  private void startServer(GossipMember member) throws IOException {
    gossipMember = member;
    server = new AgentGossipServer(member, 0);
    server.start();
  }

  @Test
  void reports_the_lone_self_member_alive_at_incarnation_zero() throws Exception {
    startServer(
        new GossipMember(
            new MemberId("node-a", new InetSocketAddress("127.0.0.1", 0)),
            GossipConfig.defaults()));

    Map<String, Object> body = get("/gossip/members");
    List<Map<String, Object>> members = Json.asObjectList(body.get("members"));

    assertEquals(1, members.size());
    Map<String, Object> self = members.get(0);
    assertEquals("node-a", self.get("nodeId"));
    assertEquals(MemberStatus.ALIVE.name(), self.get("status"));
    assertEquals(0L, ((Number) self.get("incarnation")).longValue());
  }

  @Test
  @Timeout(15)
  void reflects_a_peer_learned_through_real_swim_convergence() throws Exception {
    startServer(
        new GossipMember(
            new MemberId("node-a", new InetSocketAddress("127.0.0.1", 0)), FAST_CONFIG));
    peer =
        new GossipMember(
            new MemberId("node-b", new InetSocketAddress("127.0.0.1", 0)), FAST_CONFIG);
    gossipMember.start();
    peer.start();
    peer.join(List.of(gossipMember.self().gossipAddress()));

    await(
        () -> {
          try {
            List<Map<String, Object>> members =
                Json.asObjectList(get("/gossip/members").get("members"));
            return members.stream()
                .anyMatch(
                    m ->
                        "node-b".equals(m.get("nodeId"))
                            && MemberStatus.ALIVE.name().equals(m.get("status")));
          } catch (Exception e) {
            return false;
          }
        },
        Duration.ofSeconds(10));
  }

  private static void await(BooleanSupplier condition, Duration timeout)
      throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    while (!condition.getAsBoolean()) {
      if (Instant.now().isAfter(deadline)) {
        throw new AssertionError("condition not met within " + timeout);
      }
      Thread.sleep(50);
    }
  }

  @Test
  void rejects_non_get_methods() throws Exception {
    startServer(
        new GossipMember(
            new MemberId("node-a", new InetSocketAddress("127.0.0.1", 0)),
            GossipConfig.defaults()));

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/gossip/members"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(405, response.statusCode());
  }

  private Map<String, Object> get(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .GET()
            .build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(
        200, response.statusCode(), "unexpected status for " + path + ": " + response.body());
    return Json.asObject(Json.parse(response.body()));
  }
}
