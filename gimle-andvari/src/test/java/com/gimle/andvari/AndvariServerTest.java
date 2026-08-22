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
import java.nio.file.Files;
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
  // Longer than every other test in this class: awaitStatus polls for reportIntegrityFailure's
  // quarantine + durable audit write to land on a *different* virtual thread than the one the
  // test already awaited, which can take meaningfully longer than a single request under this
  // class's own concurrent-test-class parallelism (see junit.jupiter.execution.parallel.mode
  // .classes.default=concurrent in the root pom) even though it's sub-second in isolation.
  @Timeout(30)
  void a_get_against_bytes_corrupted_on_disk_still_serves_them_but_quarantines_the_coordinate()
      throws Exception {
    byte[] jar = "pretend-jar-bytes".getBytes(StandardCharsets.UTF_8);
    send(put("/artifacts/com.example.app/1.0.0", jar));
    // Simulate bit rot / a corrupted filesystem underneath the store's own atomic-rename
    // discipline: flip a byte in place on disk, bypassing put() entirely, so meta.json still
    // records the original (now-stale) sha256. Same length as the original -- handleDownload
    // declares Content-Length from meta's own (unchanged) sizeBytes, so a corruption that also
    // changed the length would desync the response framing itself, a different failure mode than
    // the digest mismatch this test means to exercise.
    byte[] corrupted = jar.clone();
    corrupted[0] ^= 0xFF;
    Path jarFile =
        tempDir
            .resolve("data")
            .resolve("artifacts")
            .resolve("com.example.app")
            .resolve("1.0.0")
            .resolve("artifact.jar");
    Files.write(jarFile, corrupted);

    // HTTP has no way to recall bytes already sent, so the one request racing the corruption is
    // still served whatever is actually on disk at read time...
    HttpResponse<byte[]> response =
        client.send(
            HttpRequest.newBuilder(uri("/artifacts/com.example.app/1.0.0")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, response.statusCode());
    assertArrayEquals(corrupted, response.body());

    // ...but the mismatch it just detected quarantines the coordinate, so every subsequent
    // request eventually finds nothing servable there instead of silently repeating the same
    // corruption. Each exchange runs on its own virtual thread (see AndvariServer's own
    // Executors.newVirtualThreadPerTaskExecutor()), so reportIntegrityFailure's quarantine +
    // durable audit write can still be in flight after the client has already finished reading
    // the (already-corrupted) response body -- poll rather than assert immediately.
    awaitStatus("/artifacts/com.example.app/1.0.0", 404);
    awaitStatus("/artifacts/com.example.app", 404);
  }

  /**
   * Polls {@code path} until it answers {@code expectedStatus} or the enclosing {@code @Timeout}
   * fires -- for asserting on work (like {@link AndvariServer#reportIntegrityFailure}'s quarantine)
   * that happens on a different virtual thread than the response the test already awaited, with no
   * other signal the test can synchronize on.
   */
  private void awaitStatus(String path, int expectedStatus) throws Exception {
    while (send(get(path)).statusCode() != expectedStatus) {
      Thread.sleep(50);
    }
  }

  @Test
  @Timeout(10)
  void a_push_over_the_configured_size_limit_is_rejected_with_413() throws Exception {
    // The shared server (from setUp) uses the 500 MiB default -- this test needs its own instance
    // constructed after gimle.andvari.maxArtifactBytes is set, since AndvariServer reads that
    // system property once, at construction time.
    System.setProperty("gimle.andvari.maxArtifactBytes", "8");
    try (InProcessStore cappedStore = InProcessStore.start(tempDir.resolve("capped-store"));
        AndvariServer cappedServer =
            new AndvariServer(cappedStore.client(), 0, tempDir.resolve("capped-data"))) {
      cappedServer.start();
      String cappedBaseUrl = "http://127.0.0.1:" + cappedServer.port();

      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create(cappedBaseUrl + "/artifacts/com.example.app/1.0.0"))
                  .PUT(
                      HttpRequest.BodyPublishers.ofByteArray(
                          "this-is-way-over-the-cap".getBytes(StandardCharsets.UTF_8)))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

      assertEquals(413, response.statusCode());
      assertEquals(
          404,
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create(cappedBaseUrl + "/artifacts/com.example.app/1.0.0"))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.discarding())
              .statusCode());
    } finally {
      System.clearProperty("gimle.andvari.maxArtifactBytes");
    }
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
  void a_pushed_tenant_round_trips_through_head_download_and_the_versions_listing()
      throws Exception {
    byte[] jar = "pretend-jar-bytes".getBytes(StandardCharsets.UTF_8);
    send(pushWithTenant("com.example.app", "1.0.0", jar, "orders-platform"));

    HttpResponse<Void> head =
        client.send(
            HttpRequest.newBuilder(uri("/artifacts/com.example.app/1.0.0"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertEquals(
        "orders-platform", head.headers().firstValue("X-Gimle-Artifact-Tenant").orElseThrow());

    HttpResponse<byte[]> downloaded =
        client.send(
            HttpRequest.newBuilder(uri("/artifacts/com.example.app/1.0.0")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(
        "orders-platform",
        downloaded.headers().firstValue("X-Gimle-Artifact-Tenant").orElseThrow());

    HttpResponse<String> versions = send(get("/artifacts/com.example.app"));
    assertTrue(versions.body().contains("orders-platform"));
  }

  @Test
  @Timeout(10)
  void an_untenanted_artifact_can_be_claimed_by_a_later_tenant_tagged_push() throws Exception {
    byte[] jar = "same-bytes".getBytes(StandardCharsets.UTF_8);
    send(put("/artifacts/com.example.app/1.0.0", jar));

    HttpResponse<String> claim = send(pushWithTenant("com.example.app", "1.0.0", jar, "billing"));

    assertEquals(200, claim.statusCode());
    assertEquals(false, Json.asObject(Json.parse(claim.body())).get("created"));
    assertEquals("billing", Json.asObject(Json.parse(claim.body())).get("tenantId"));
  }

  @Test
  @Timeout(10)
  void a_claimed_tenant_cannot_be_swapped_for_a_different_one_on_re_push() throws Exception {
    byte[] jar = "same-bytes".getBytes(StandardCharsets.UTF_8);
    send(pushWithTenant("com.example.app", "1.0.0", jar, "orders-platform"));

    HttpResponse<String> conflict =
        send(pushWithTenant("com.example.app", "1.0.0", jar, "billing"));

    assertEquals(409, conflict.statusCode());
    assertTrue(conflict.body().contains("orders-platform"));
  }

  private HttpRequest pushWithTenant(
      String moduleId, String version, byte[] body, String tenantId) {
    return HttpRequest.newBuilder(uri("/artifacts/" + moduleId + "/" + version))
        .header("X-Gimle-Artifact-Tenant", tenantId)
        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
        .build();
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
