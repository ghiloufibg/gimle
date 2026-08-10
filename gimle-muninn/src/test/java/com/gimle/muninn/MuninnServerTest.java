package com.gimle.muninn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.protocol.Json;
import com.gimle.muninn.testsupport.InProcessStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Real inbound HTTP traffic against a real {@link MuninnServer} in its default (unconfigured)
 * plaintext mode -- proving {@code TransportProtocol.fromConfig()}'s own default is what a fresh
 * process actually gets, not just what the code claims it defaults to. Same {@link ResourceLock}
 * pair {@code MuninnServerTlsTest} will use -- both classes drive real HTTP servers over real
 * loopback sockets.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-muninn-server-http")
class MuninnServerTest {

  @TempDir Path tempDir;

  private InProcessStore store;
  private MuninnServer server;
  private final HttpClient client = HttpClient.newHttpClient();
  private String baseUrl;

  @BeforeEach
  void setUp() throws Exception {
    store = InProcessStore.start(tempDir.resolve("store"));
    server = new MuninnServer(store.client(), 0, tempDir.resolve("data"));
    server.start();
    baseUrl = "http://127.0.0.1:" + server.port();
  }

  @AfterEach
  void tearDown() {
    server.close();
    store.close();
  }

  @Test
  @Timeout(10)
  void a_fresh_server_defaults_to_plaintext_and_answers_status() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/status")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    assertEquals("PLAINTEXT", body.get("transportProtocol"));
  }

  @Test
  @Timeout(10)
  void a_non_get_status_request_is_rejected() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/status"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(405, response.statusCode());
  }
}
