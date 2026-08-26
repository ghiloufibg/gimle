package com.gimle.andvari;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.andvari.testsupport.InProcessStore;
import com.gimle.core.web.BundledSpa;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Andvari's own web console, served at {@code /console} -- mirrors {@code FafnirServerConsoleTest}
 * exactly: the synthetic-directory static-handler mechanics, the bare-root redirect, and resolving
 * the *real* bundled {@code gimle-andvari-console} jar (a genuine dependency of this module, not a
 * test fixture) via {@link BundledSpa}, proving the whole Bun-build-to-served-HTTP-response
 * pipeline end to end.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-andvari-server-http")
class AndvariServerConsoleTest {

  @TempDir Path tempDir;

  private InProcessStore store;
  private AndvariServer server;
  private final HttpClient client = HttpClient.newHttpClient();
  private String baseUrl;

  @BeforeEach
  void setUp() throws Exception {
    store = InProcessStore.start(tempDir.resolve("store"));
    server = new AndvariServer(store.client(), 0, tempDir.resolve("data"));
    server.start();
    baseUrl = "http://127.0.0.1:" + server.port();
  }

  @AfterEach
  void tearDown() {
    server.close();
    store.close();
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  @Test
  @Timeout(10)
  void console_static_files_are_served_once_wired() throws Exception {
    Path consoleRoot = tempDir.resolve("console-dist");
    Files.createDirectories(consoleRoot);
    Files.writeString(consoleRoot.resolve("index.html"), "<html>shell</html>");
    Files.writeString(consoleRoot.resolve("app.js"), "console.log('hi');");
    server.serveConsole(consoleRoot);

    HttpResponse<String> asset = get("/console/app.js");
    assertEquals(200, asset.statusCode());
    assertEquals("console.log('hi');", asset.body());

    HttpResponse<String> deepLink = get("/console/versions");
    assertEquals(200, deepLink.statusCode());
    assertEquals("<html>shell</html>", deepLink.body());
  }

  @Test
  @Timeout(10)
  void the_bare_root_redirects_to_the_console_once_wired() throws Exception {
    Path consoleRoot = tempDir.resolve("console-dist-root-redirect");
    Files.createDirectories(consoleRoot);
    Files.writeString(consoleRoot.resolve("index.html"), "<html>shell</html>");
    server.serveConsole(consoleRoot);

    HttpResponse<String> response = get("/");

    assertEquals(302, response.statusCode());
    assertEquals("/console", response.headers().firstValue("Location").orElse(""));
  }

  @Test
  @Timeout(10)
  void the_real_bundled_console_jar_resolves_and_serves_its_own_index_html() throws Exception {
    Optional<Path> consoleRoot =
        BundledSpa.resolve(AndvariServer.class.getClassLoader(), "andvari-console/index.html");
    assertTrue(
        consoleRoot.isPresent(),
        "expected gimle-andvari-console's built dist/ to be on the test classpath as a real"
            + " dependency of gimle-andvari -- see that module's own pom.xml");
    server.serveConsole(consoleRoot.get());

    HttpResponse<String> index = get("/console/");

    assertEquals(200, index.statusCode());
    assertTrue(
        index.body().contains("Gimlé Andvari Registry"),
        "expected the real built index.html's own <title>, not a stub: " + index.body());
  }
}
