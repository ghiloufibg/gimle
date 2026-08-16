package com.gimle.mavenplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The reuse-vs-spawn decision in {@link SagaServer#ensureRunning}, against a stub HTTP server. */
class SagaServerTest {

  private final Log log = new SystemStreamLog();
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void a_healthy_server_is_reused_without_spawning() throws Exception {
    server = serverRespondingWith(200);
    AtomicBoolean spawned = new AtomicBoolean();
    SagaServer.Ensured ensured =
        SagaServer.ensureRunning(
            clientFor(server),
            () -> {
              spawned.set(true);
              return FakeProcess.alive();
            },
            Duration.ofSeconds(5),
            log);
    assertEquals(SagaServer.Ensured.REUSED, ensured);
    assertFalse(spawned.get());
  }

  @Test
  void an_initially_unhealthy_server_is_spawned_and_awaited() throws Exception {
    AtomicInteger healthCalls = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/health",
        exchange -> {
          exchange.sendResponseHeaders(healthCalls.incrementAndGet() <= 2 ? 503 : 200, -1);
          exchange.close();
        });
    server.start();
    AtomicBoolean spawned = new AtomicBoolean();
    SagaServer.Ensured ensured =
        SagaServer.ensureRunning(
            clientFor(server),
            () -> {
              spawned.set(true);
              return FakeProcess.alive();
            },
            Duration.ofSeconds(10),
            log);
    assertEquals(SagaServer.Ensured.SPAWNED, ensured);
    assertTrue(spawned.get());
  }

  @Test
  void a_spawned_server_that_dies_before_becoming_healthy_fails_with_its_exit_code()
      throws Exception {
    SagaClient client = new SagaClient("http://127.0.0.1:" + unusedPort());
    MojoExecutionException failure =
        assertThrows(
            MojoExecutionException.class,
            () ->
                SagaServer.ensureRunning(
                    client, () -> FakeProcess.exited(7), Duration.ofSeconds(10), log));
    assertTrue(failure.getMessage().contains("exited with code 7"));
  }

  @Test
  void a_server_that_never_becomes_healthy_times_out() throws Exception {
    server = serverRespondingWith(503);
    MojoExecutionException failure =
        assertThrows(
            MojoExecutionException.class,
            () ->
                SagaServer.ensureRunning(
                    clientFor(server), FakeProcess::alive, Duration.ofSeconds(1), log));
    assertTrue(failure.getMessage().contains("did not become healthy"));
  }

  private static HttpServer serverRespondingWith(int status) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/health",
        exchange -> {
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
    return server;
  }

  private static SagaClient clientFor(HttpServer server) {
    return new SagaClient("http://127.0.0.1:" + server.getAddress().getPort());
  }

  private static int unusedPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
