package com.gimle.mavenplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SagaClientTest {

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void ingest_posts_the_event_line_as_ndjson() throws Exception {
    AtomicReference<String> receivedBody = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/ingest",
        exchange -> {
          receivedBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();

    clientFor(server).ingest("{\"kind\":\"RunStarted\"}");
    assertEquals("{\"kind\":\"RunStarted\"}\n", receivedBody.get());
  }

  @Test
  void import_report_names_the_module_derived_from_the_reports_target_path(@TempDir Path dir)
      throws Exception {
    Path report = dir.resolve("gimle-module/target/surefire-reports/TEST-Example.xml");
    Files.createDirectories(report.getParent());
    Files.writeString(report, "<testsuite tests=\"1\"/>");
    AtomicReference<String> receivedQuery = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/import",
        exchange -> {
          receivedQuery.set(exchange.getRequestURI().getQuery());
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();

    clientFor(server).importReport("r1", report);
    assertEquals("runId=r1&module=gimle-module", receivedQuery.get());
  }

  @Test
  void import_report_posts_the_file_body_under_the_run_id_query(@TempDir Path dir)
      throws Exception {
    Path report = dir.resolve("TEST-Example.xml");
    Files.writeString(report, "<testsuite tests=\"1\"/>");
    AtomicReference<String> receivedQuery = new AtomicReference<>();
    AtomicReference<String> receivedBody = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/import",
        exchange -> {
          receivedQuery.set(exchange.getRequestURI().getQuery());
          receivedBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();

    clientFor(server).importReport("2026-08-16T10-00-00_abc1234", report);
    assertEquals("runId=2026-08-16T10-00-00_abc1234", receivedQuery.get());
    assertEquals("<testsuite tests=\"1\"/>", receivedBody.get());
  }

  @Test
  void a_non_2xx_ingest_response_is_an_error() throws Exception {
    server = serverRespondingWith("/api/ingest", 500);
    MojoExecutionException failure =
        assertThrows(
            MojoExecutionException.class, () -> clientFor(server).ingest("{\"kind\":\"x\"}"));
    assertTrue(failure.getMessage().contains("500"));
  }

  @Test
  void an_unreachable_server_is_an_ingest_error() throws Exception {
    SagaClient client = new SagaClient("http://127.0.0.1:" + unusedPort());
    assertThrows(MojoExecutionException.class, () -> client.ingest("{\"kind\":\"x\"}"));
  }

  @Test
  void health_check_is_false_when_nothing_is_listening() throws Exception {
    assertFalse(new SagaClient("http://127.0.0.1:" + unusedPort()).isHealthy());
  }

  @Test
  void shutdown_is_false_when_the_endpoint_is_not_implemented() throws Exception {
    server = serverRespondingWith("/api/shutdown", 404);
    assertFalse(clientFor(server).shutdown());
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

  private static SagaClient clientFor(HttpServer server) {
    return new SagaClient("http://127.0.0.1:" + server.getAddress().getPort());
  }

  private static int unusedPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
