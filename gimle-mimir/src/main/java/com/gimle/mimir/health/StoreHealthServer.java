package com.gimle.mimir.health;

import com.gimle.core.web.HttpResponses;
import com.gimle.mimir.raft.RaftNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The store process's read-only health surface -- unauthenticated and plaintext-only, matching
 * {@code AgentGossipServer}/{@code AgentLogServer}'s own "trust the network topology" posture
 * rather than {@code FafnirServer}/{@code AndvariServer}'s mTLS-capable one: nothing served here is
 * sensitive (no key material, no secret/tenant data, just this replica's own Raft role), so the
 * complexity of a second TLS-terminating listener buys nothing. Opt-in, constructed by {@link
 * com.gimle.mimir.StoreMain} only when {@code --health-port} is given, the same "no operator ever
 * configured it needs no extra listener" precedent {@code AgentAdminServer} already established --
 * before this class existed, {@code gimle-mimir} had no HTTP surface at all, leaving a load
 * balancer or liveness probe nothing but a raw Raft-port TCP connect to check.
 */
public final class StoreHealthServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(StoreHealthServer.class);

  private final RaftNode raftNode;
  private final HttpServer server;

  public StoreHealthServer(RaftNode raftNode, int port) throws IOException {
    this.raftNode = raftNode;
    this.server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/health", this::handleHealth);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
  }

  // ---- /health ----

  /**
   * Always 200 -- this replica being reachable and able to answer at all is the liveness signal; a
   * probe distinguishing "healthy leader" from "healthy follower" reads {@code isLeader} in the
   * body rather than the status code, the same way {@code StoreNode#handleGetSnapshot} routes
   * leader-only operations without treating "I'm a follower" as an error.
   */
  private void handleHealth(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      Map<String, Object> status = new LinkedHashMap<>();
      status.put("status", "UP");
      status.put("selfId", raftNode.selfId());
      status.put("isLeader", raftNode.isLeader());
      status.put("leaderHint", raftNode.leaderHint().orElse(null));
      status.put("memberCount", raftNode.memberIds().size());
      respondJson(exchange, 200, status);
    } catch (IOException | RuntimeException e) {
      log.warn("health request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  // ---- shared plumbing, matching AgentGossipServer's own response helpers ----

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    HttpResponses.respond(exchange, status, body);
  }

  private static void respondJson(HttpExchange exchange, int status, Object value)
      throws IOException {
    HttpResponses.respondJson(exchange, status, value);
  }

  private static void respondQuietly(HttpExchange exchange, int status, String body) {
    HttpResponses.respondQuietly(exchange, status, body);
  }
}
