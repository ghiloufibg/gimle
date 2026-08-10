package com.gimle.fafnir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.web.BundledSpa;
import com.gimle.fafnir.testsupport.InProcessStore;
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
 * Fafnir's own web console, served at {@code /console} -- mirrors {@code
 * ApiServerTest#console_static_files_are_served_once_wired}'s synthetic-directory shape, plus one
 * test the control-plane side doesn't have: resolving the *real* bundled {@code
 * gimle-fafnir-console} jar (a genuine dependency of this module, not a test fixture) via {@link
 * BundledSpa}, proving the whole Bun-build-to-served-HTTP-response pipeline end to end rather than
 * just the static-handler mechanics in isolation.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-fafnir-server-http")
class FafnirServerConsoleTest {

  @TempDir Path tempDir;

  private InProcessStore store;
  private FafnirServer server;
  private final HttpClient client = HttpClient.newHttpClient();
  private String baseUrl;

  @BeforeEach
  void setUp() throws Exception {
    store = InProcessStore.start(tempDir.resolve("store"));
    FafnirCrypto crypto = new FafnirCrypto(store.client(), tempDir.resolve("keys/secret.key"));
    server = new FafnirServer(crypto, 0);
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

    HttpResponse<String> deepLink = get("/console/secrets");
    assertEquals(200, deepLink.statusCode());
    assertEquals("<html>shell</html>", deepLink.body());
  }

  @Test
  @Timeout(10)
  void the_real_bundled_console_jar_resolves_and_serves_its_own_index_html() throws Exception {
    Optional<Path> consoleRoot =
        BundledSpa.resolve(FafnirServer.class.getClassLoader(), "fafnir-console/index.html");
    assertTrue(
        consoleRoot.isPresent(),
        "expected gimle-fafnir-console's built dist/ to be on the test classpath as a real"
            + " dependency of gimle-fafnir -- see that module's own pom.xml");
    server.serveConsole(consoleRoot.get());

    HttpResponse<String> index = get("/console/");

    assertEquals(200, index.statusCode());
    assertTrue(
        index.body().contains("Gimlé Fafnir Vault"),
        "expected the real built index.html's own <title>, not a stub: " + index.body());
  }
}
