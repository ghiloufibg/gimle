package com.gimle.andvari;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.andvari.testsupport.InProcessStore;
import com.gimle.core.protocol.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Real inbound HTTP traffic against a real {@link AndvariServer} in its default (unconfigured)
 * plaintext mode: the full push/head/pull/list/delete round-trip an operator and a node agent
 * drive, plus the immutability refusal and the error paths. Same {@link ResourceLock} pair {@code
 * AndvariServerTlsTest} uses -- both classes drive real HTTP servers over real loopback sockets.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-andvari-server-http")
class AndvariServerTest {

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

  @Test
  @Timeout(10)
  void a_fresh_server_defaults_to_plaintext_and_answers_status() throws Exception {
    HttpResponse<String> response = send(get("/status"));

    assertEquals(200, response.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    assertEquals("PLAINTEXT", body.get("transportProtocol"));
  }

  @Test
  @Timeout(10)
  void push_head_and_download_round_trip_with_the_digest_in_the_header() throws Exception {
    byte[] jar = "pretend-jar-bytes".getBytes(StandardCharsets.UTF_8);
    String expectedSha = sha256Of(jar);

    HttpResponse<String> pushed = send(put("/artifacts/com.example.app/1.0.0", jar));
    assertEquals(200, pushed.statusCode());
    Map<String, Object> pushedBody = Json.asObject(Json.parse(pushed.body()));
    assertEquals(true, pushedBody.get("created"));
    assertEquals(expectedSha, pushedBody.get("sha256"));

    HttpResponse<Void> head =
        client.send(
            HttpRequest.newBuilder(uri("/artifacts/com.example.app/1.0.0"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertEquals(200, head.statusCode());
    assertEquals(expectedSha, head.headers().firstValue("X-Gimle-Artifact-Sha256").orElseThrow());

    HttpResponse<byte[]> downloaded =
        client.send(
            HttpRequest.newBuilder(uri("/artifacts/com.example.app/1.0.0")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, downloaded.statusCode());
    assertArrayEquals(jar, downloaded.body());
    assertEquals(
        expectedSha, downloaded.headers().firstValue("X-Gimle-Artifact-Sha256").orElseThrow());
  }

  @Test
  @Timeout(10)
  void the_catalog_and_version_listing_reflect_pushed_artifacts() throws Exception {
    send(put("/artifacts/com.example.app/1.0.0", "v1".getBytes(StandardCharsets.UTF_8)));
    send(put("/artifacts/com.example.app/2.0.0", "v2".getBytes(StandardCharsets.UTF_8)));

    HttpResponse<String> catalog = send(get("/artifacts"));
    assertEquals(200, catalog.statusCode());
    assertTrue(catalog.body().contains("com.example.app"));

    HttpResponse<String> versions = send(get("/artifacts/com.example.app"));
    assertEquals(200, versions.statusCode());
    assertTrue(versions.body().contains("1.0.0"));
    assertTrue(versions.body().contains("2.0.0"));
  }

  @Test
  @Timeout(10)
  void a_differing_re_push_is_refused_as_immutable() throws Exception {
    send(put("/artifacts/com.example.app/1.0.0", "original".getBytes(StandardCharsets.UTF_8)));

    HttpResponse<String> conflict =
        send(put("/artifacts/com.example.app/1.0.0", "tampered".getBytes(StandardCharsets.UTF_8)));

    assertEquals(409, conflict.statusCode());
    assertTrue(conflict.body().contains("immutable"));
  }

  @Test
  @Timeout(10)
  void an_identical_re_push_is_idempotent() throws Exception {
    byte[] jar = "same-bytes".getBytes(StandardCharsets.UTF_8);
    send(put("/artifacts/com.example.app/1.0.0", jar));

    HttpResponse<String> second = send(put("/artifacts/com.example.app/1.0.0", jar));

    assertEquals(200, second.statusCode());
    assertEquals(false, Json.asObject(Json.parse(second.body())).get("created"));
  }

  @Test
  @Timeout(10)
  void delete_removes_the_artifact() throws Exception {
    send(put("/artifacts/com.example.app/1.0.0", "v1".getBytes(StandardCharsets.UTF_8)));

    HttpResponse<String> deleted =
        send(HttpRequest.newBuilder(uri("/artifacts/com.example.app/1.0.0")).DELETE().build());
    assertEquals(200, deleted.statusCode());

    assertEquals(404, send(get("/artifacts/com.example.app/1.0.0")).statusCode());
    assertEquals(404, send(get("/artifacts/com.example.app")).statusCode());
  }

  @Test
  @Timeout(10)
  void unknown_coordinates_and_malformed_paths_answer_404() throws Exception {
    assertEquals(404, send(get("/artifacts/com.example.ghost/1.0.0")).statusCode());
    assertEquals(404, send(get("/artifacts/com.example.ghost")).statusCode());
    assertEquals(404, send(get("/artifacts/a/b/c")).statusCode());
  }

  @Test
  @Timeout(10)
  void an_invalid_path_segment_answers_400() throws Exception {
    assertEquals(
        400,
        send(put("/artifacts/com.example.app/..", "x".getBytes(StandardCharsets.UTF_8)))
            .statusCode());
  }

  @Test
  @Timeout(10)
  void a_wrong_method_answers_405() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(uri("/artifacts"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());

    assertEquals(405, response.statusCode());
  }

  private URI uri(String path) {
    return URI.create(baseUrl + path);
  }

  private HttpRequest get(String path) {
    return HttpRequest.newBuilder(uri(path)).GET().build();
  }

  private HttpRequest put(String path, byte[] body) {
    return HttpRequest.newBuilder(uri(path))
        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
        .build();
  }

  private HttpResponse<String> send(HttpRequest request) throws Exception {
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static String sha256Of(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
