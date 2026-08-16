package com.gimle.agent;

import com.gimle.core.protocol.Json;
import com.gimle.fabric.cluster.GossipMember;
import com.gimle.fabric.cluster.MemberState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The agent's read-only gossip-membership HTTP surface: this node's own current SWIM view (member
 * id, status, incarnation) as tracked by its {@link GossipMember}. Previously observable only
 * indirectly -- grepping a "is now DEAD" log line, or a test waiting out a real timeout and
 * asserting a negative -- this exposes the live table directly, the same {@code kubectl get
 * nodes}-shaped read {@code AgentLogServer} already provides for logs.
 */
final class AgentGossipServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(AgentGossipServer.class);

  private final GossipMember gossipMember;
  private final HttpServer server;

  AgentGossipServer(GossipMember gossipMember, int port) throws IOException {
    this.gossipMember = gossipMember;
    this.server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/gossip/members", this::handleMembers);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
  }

  void start() {
    server.start();
  }

  int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
  }

  // ---- /gossip/members ----

  private void handleMembers(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      List<Map<String, Object>> members =
          gossipMember.members().stream().map(AgentGossipServer::toJson).toList();
      respondJson(exchange, 200, Map.of("members", members));
    } catch (IOException | RuntimeException e) {
      log.warn("gossip members request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private static Map<String, Object> toJson(MemberState state) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("nodeId", state.id().nodeId());
    entry.put("status", state.status().name());
    entry.put("incarnation", state.incarnation());
    return entry;
  }

  // ---- shared plumbing, matching AgentLogServer's own response helpers ----

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void respondJson(HttpExchange exchange, int status, Object value)
      throws IOException {
    byte[] bytes = Json.write(value).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void respondQuietly(HttpExchange exchange, int status, String body) {
    try {
      respond(exchange, status, body);
    } catch (IOException e) {
      log.warn("failed to write error response: {}", e.getMessage());
    }
  }
}
