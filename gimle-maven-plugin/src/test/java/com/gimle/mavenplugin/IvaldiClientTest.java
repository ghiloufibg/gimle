package com.gimle.mavenplugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link IvaldiClient}'s two real calls -- {@code isHealthy}/{@code shutdown} -- against a stub
 * HTTP server. Unlike {@code SagaClient}, {@code IvaldiClient} carries no ingest/import calls, so
 * this covers a smaller surface than {@code SagaClientTest} by design, not by omission.
 */
class IvaldiClientTest {

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void health_check_is_true_when_the_endpoint_answers_200() throws Exception {
    server = serverRespondingWith("/api/health", 200);
    assertTrue(clientFor(server).isHealthy());
  }

  @Test
  void health_check_is_false_when_nothing_is_listening() throws Exception {
    assertFalse(new IvaldiClient("http://127.0.0.1:" + unusedPort()).isHealthy());
  }

  @Test
  void shutdown_is_true_when_the_endpoint_answers_2xx() throws Exception {
    server = serverRespondingWith("/api/shutdown", 200);
    assertTrue(clientFor(server).shutdown());
  }

  @Test
  void shutdown_is_false_when_the_endpoint_is_not_implemented() throws Exception {
    server = serverRespondingWith("/api/shutdown", 404);
    assertFalse(clientFor(server).shutdown());
  }

  @Test
  void shutdown_is_false_when_nothing_is_listening() throws Exception {
    assertFalse(new IvaldiClient("http://127.0.0.1:" + unusedPort()).shutdown());
  }

  private static HttpServer serverRespondingWith(String path, int status) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        path,
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
    return server;
  }

  private static IvaldiClient clientFor(HttpServer server) {
    return new IvaldiClient("http://127.0.0.1:" + server.getAddress().getPort());
  }

  private static int unusedPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
