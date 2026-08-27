package com.gimle.ragnarok.target.adminapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises {@link AdminApiWorkerHandle} against a real local {@link HttpServer} standing in for a
 * node agent's own Admin Fault API -- a genuine HTTP round trip, not a mocked client.
 */
final class AdminApiWorkerHandleTest {

  private HttpServer fakeAgent;
  private String baseUrl;
  private final AtomicLong currentPid = new AtomicLong();
  private final AtomicBoolean currentAlive = new AtomicBoolean(true);
  private final AtomicReference<Map<String, Object>> lastKillRequest = new AtomicReference<>();

  @BeforeEach
  void setUp() throws IOException {
    fakeAgent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    fakeAgent.createContext("/admin/faults/workers/orders/0", this::handle);
    fakeAgent.start();
    baseUrl = "http://127.0.0.1:" + fakeAgent.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    fakeAgent.stop(0);
  }

  private void handle(final HttpExchange exchange) throws IOException {
    try {
      final String path = exchange.getRequestURI().getPath();
      if ("GET".equals(exchange.getRequestMethod()) && path.endsWith("/0")) {
        respondJson(exchange, 200, Map.of("pid", currentPid.get(), "alive", currentAlive.get()));
        return;
      }
      if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/kill")) {
        try (InputStream body = exchange.getRequestBody()) {
          lastKillRequest.set(
              Json.asObject(Json.parse(new String(body.readAllBytes(), StandardCharsets.UTF_8))));
        }
        currentAlive.set(false);
        respondJson(exchange, 200, Map.of("killed", true));
        return;
      }
      respondJson(exchange, 404, Map.of());
    } finally {
      exchange.close();
    }
  }

  private static void respondJson(final HttpExchange exchange, final int status, final Object body)
      throws IOException {
    final byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Test
  @Timeout(10)
  void is_alive_reflects_the_agents_own_current_status_for_this_exact_pid() {
    currentPid.set(4242);
    currentAlive.set(true);
    final AdminApiWorkerHandle handle =
        new AdminApiWorkerHandle(HttpClient.newHttpClient(), baseUrl, "orders", 0, 4242);

    assertTrue(handle.isAlive());
    assertEquals(4242, handle.pid());
  }

  @Test
  @Timeout(10)
  void is_alive_is_false_once_a_respawn_reports_a_different_current_pid() {
    currentPid.set(4242);
    currentAlive.set(true);
    final AdminApiWorkerHandle handle =
        new AdminApiWorkerHandle(HttpClient.newHttpClient(), baseUrl, "orders", 0, 4242);

    currentPid.set(5555); // a respawn already happened

    assertFalse(
        handle.isAlive(), "a stale handle's own captured pid no longer matches the current one");
  }

  @Test
  @Timeout(10)
  void kill_posts_the_captured_pid_to_the_agents_kill_endpoint() {
    currentPid.set(4242);
    currentAlive.set(true);
    final AdminApiWorkerHandle handle =
        new AdminApiWorkerHandle(HttpClient.newHttpClient(), baseUrl, "orders", 0, 4242);

    handle.kill();

    assertEquals(4242L, ((Number) lastKillRequest.get().get("pid")).longValue());
    assertFalse(currentAlive.get(), "the fake agent should have marked the instance dead");
  }
}
