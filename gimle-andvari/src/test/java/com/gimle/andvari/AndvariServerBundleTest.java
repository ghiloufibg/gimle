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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * The bundle side of {@link AndvariServer}'s HTTP surface: the {@code X-Gimle-Artifact-Kind}
 * header on push and read, the zip content type, the kind field in the versions listing, and the
 * maven repository surface's refusal to serve a bundle under a jar-shaped path.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-andvari-server-http")
class AndvariServerBundleTest {

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

  private URI uri(String path) {
    return URI.create(baseUrl + path);
  }

  private HttpResponse<String> pushBundle(String path, byte[] zip) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(path))
            .header("X-Gimle-Artifact-Kind", "BUNDLE")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(zip))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @Timeout(10)
  void a_bundle_push_round_trips_with_kind_header_and_zip_content_type() throws Exception {
    byte[] zip = "pretend-zip-bytes".getBytes(StandardCharsets.UTF_8);

    HttpResponse<String> pushed = pushBundle("/artifacts/com.example.report/1.0.0", zip);
    assertEquals(200, pushed.statusCode());
    Map<String, Object> pushedBody = Json.asObject(Json.parse(pushed.body()));
    assertEquals(true, pushedBody.get("created"));
    assertEquals("BUNDLE", pushedBody.get("kind"));

    HttpResponse<Void> head =
        client.send(
            HttpRequest.newBuilder(uri("/artifacts/com.example.report/1.0.0"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertEquals(200, head.statusCode());
    assertEquals("BUNDLE", head.headers().firstValue("X-Gimle-Artifact-Kind").orElseThrow());
    assertEquals("application/zip", head.headers().firstValue("Content-Type").orElseThrow());

    HttpResponse<byte[]> downloaded =
        client.send(
            HttpRequest.newBuilder(uri("/artifacts/com.example.report/1.0.0")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, downloaded.statusCode());
    assertArrayEquals(zip, downloaded.body());
    assertEquals("BUNDLE", downloaded.headers().firstValue("X-Gimle-Artifact-Kind").orElseThrow());
  }

  @Test
  @Timeout(10)
  void a_jar_push_reports_its_kind_in_headers_and_listing() throws Exception {
    byte[] jar = "pretend-jar-bytes".getBytes(StandardCharsets.UTF_8);
    client.send(
        HttpRequest.newBuilder(uri("/artifacts/com.example.app/1.0.0"))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(jar))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    HttpResponse<String> versions =
        client.send(
            HttpRequest.newBuilder(uri("/artifacts/com.example.app")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, versions.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(versions.body()));
    List<Map<String, Object>> entries = Json.asObjectList(body.get("versions"));
    assertEquals("JAR", entries.get(0).get("kind"));
  }

  @Test
  @Timeout(10)
  void a_kind_flip_re_push_is_refused_with_409() throws Exception {
    byte[] bytes = "same-bytes".getBytes(StandardCharsets.UTF_8);
    client.send(
        HttpRequest.newBuilder(uri("/artifacts/com.example.app/1.0.0"))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    HttpResponse<String> flipped = pushBundle("/artifacts/com.example.app/1.0.0", bytes);
    assertEquals(409, flipped.statusCode());
    assertTrue(flipped.body().contains("kind"));
  }

  @Test
  @Timeout(10)
  void an_unknown_kind_header_is_a_400() throws Exception {
    HttpResponse<String> pushed =
        client.send(
            HttpRequest.newBuilder(uri("/artifacts/com.example.app/1.0.0"))
                .header("X-Gimle-Artifact-Kind", "TARBALL")
                .PUT(HttpRequest.BodyPublishers.ofString("bytes"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(400, pushed.statusCode());
  }

  @Test
  @Timeout(10)
  void the_maven_repository_surface_never_serves_a_bundle() throws Exception {
    byte[] zip = "pretend-zip-bytes".getBytes(StandardCharsets.UTF_8);
    pushBundle("/artifacts/com.example.report/1.0.0", zip);

    HttpResponse<String> jarShaped =
        client.send(
            HttpRequest.newBuilder(
                    uri("/repository/com/example/report/1.0.0/report-1.0.0.jar"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(404, jarShaped.statusCode());

    HttpResponse<String> metadata =
        client.send(
            HttpRequest.newBuilder(uri("/repository/com/example/report/maven-metadata.xml"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(404, metadata.statusCode());
  }
}
