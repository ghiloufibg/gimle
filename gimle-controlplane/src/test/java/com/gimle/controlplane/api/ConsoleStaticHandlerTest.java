package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/** Exercises {@link ConsoleStaticHandler} directly over a real loopback HTTP connection. */
class ConsoleStaticHandlerTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private Path staticRoot;
  private HttpServer server;
  private HttpClient client;
  private String baseUrl;

  @BeforeEach
  void startServer() throws IOException {
    staticRoot = tempDir.resolve("console-dist");
    Files.createDirectories(staticRoot);
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private void startWithShell(String shellFileName) throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/console", new ConsoleStaticHandler(staticRoot, shellFileName));
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    baseUrl = "http://localhost:" + server.getAddress().getPort();
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  @Test
  void serves_a_real_static_file_with_a_guessed_content_type() throws Exception {
    Files.writeString(staticRoot.resolve("app.js"), "console.log('hi');");
    startWithShell("index.html");

    HttpResponse<String> response = get("/console/app.js");

    assertEquals(200, response.statusCode());
    assertEquals("console.log('hi');", response.body());
  }

  @Test
  void falls_back_to_the_shell_file_for_an_unknown_client_side_route() throws Exception {
    Files.writeString(staticRoot.resolve("index.html"), "<html>shell</html>");
    startWithShell("index.html");

    HttpResponse<String> response = get("/console/deployments/orders-service");

    assertEquals(200, response.statusCode());
    assertEquals("<html>shell</html>", response.body());
    assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
  }

  @Test
  void prefers_the_tanstack_start_shell_file_when_present() throws Exception {
    Files.writeString(staticRoot.resolve("_shell.html"), "<html>tanstack shell</html>");
    startWithShell("_shell.html");

    HttpResponse<String> response = get("/console/some/deep/link");

    assertEquals(200, response.statusCode());
    assertEquals("<html>tanstack shell</html>", response.body());
  }

  @Test
  void rejects_a_path_traversal_attempt() throws Exception {
    Files.writeString(staticRoot.resolve("index.html"), "<html>shell</html>");
    startWithShell("index.html");

    HttpResponse<String> response = get("/console/../../secret.txt");

    assertEquals(400, response.statusCode());
  }

  @Test
  void returns_404_when_neither_the_path_nor_the_shell_file_exist() throws Exception {
    startWithShell("index.html");

    HttpResponse<String> response = get("/console/anything");

    assertEquals(404, response.statusCode());
  }
}
