package com.gimle.core.web;

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
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/** Exercises {@link SpaStaticHandler} directly over a real loopback HTTP connection. */
class SpaStaticHandlerTest {

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
    server.createContext("/console", new SpaStaticHandler(staticRoot, shellFileName));
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
  void serves_known_extensions_with_the_correct_content_type_without_a_filesystem_probe()
      throws Exception {
    Files.writeString(staticRoot.resolve("app.js"), "export {};");
    Files.writeString(staticRoot.resolve("app.css"), "body{}");
    Files.writeString(staticRoot.resolve("app.svg"), "<svg></svg>");
    Files.write(staticRoot.resolve("app.woff2"), new byte[] {1, 2, 3});
    startWithShell("index.html");

    assertTrue(
        get("/console/app.js")
            .headers()
            .firstValue("Content-Type")
            .orElse("")
            .startsWith("text/javascript"));
    assertTrue(
        get("/console/app.css")
            .headers()
            .firstValue("Content-Type")
            .orElse("")
            .startsWith("text/css"));
    assertTrue(
        get("/console/app.svg")
            .headers()
            .firstValue("Content-Type")
            .orElse("")
            .startsWith("image/svg+xml"));
    assertTrue(
        get("/console/app.woff2")
            .headers()
            .firstValue("Content-Type")
            .orElse("")
            .startsWith("font/woff2"));
  }

  @Test
  void a_missing_asset_returns_404_rather_than_the_spa_shell() throws Exception {
    Files.writeString(staticRoot.resolve("index.html"), "<html>shell</html>");
    startWithShell("index.html");

    HttpResponse<String> response = get("/console/assets/nope-12345.js");

    assertEquals(404, response.statusCode());
    assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
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

  /**
   * Audit finding F-02 (third pass): a symlink inside {@code staticRoot} pointing at a file outside
   * it must not be served. Skipped, not failed, where the account lacks the privilege to create a
   * symlink (unprivileged Windows without Developer Mode) -- that's an environment limitation, not
   * evidence the guard works or doesn't.
   */
  @Test
  void rejects_a_symlink_that_escapes_the_static_root() throws Exception {
    Path secret = tempDir.resolve("secret.txt");
    Files.writeString(secret, "outside the static root");
    Path link = staticRoot.resolve("escape.txt");
    try {
      Files.createSymbolicLink(link, secret);
    } catch (FileSystemException | UnsupportedOperationException e) {
      Assumptions.abort("this account cannot create symlinks: " + e.getMessage());
    }
    Files.writeString(staticRoot.resolve("index.html"), "<html>shell</html>");
    startWithShell("index.html");

    HttpResponse<String> response = get("/console/escape.txt");

    assertEquals(200, response.statusCode());
    assertEquals("<html>shell</html>", response.body());
  }
}
