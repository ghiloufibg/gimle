package com.gimle.andvari;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.andvari.testsupport.InProcessStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * The Maven-2-shaped {@code /repository/**} surface driven exactly the way a stock {@code mvn
 * deploy}/{@code mvn install} round-trip would: jar PUT/GET, a {@code .pom} sidecar, checksum
 * sidecars, and the generated {@code maven-metadata.xml} -- all landing on the same {@link
 * ArtifactStore} coordinates the operational {@code /artifacts/*} surface uses.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-andvari-server-http")
class AndvariServerMavenRepositoryTest {

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
  void a_jar_pushed_through_the_repository_path_is_readable_from_the_operational_surface()
      throws Exception {
    byte[] jar = "pretend-jar-bytes".getBytes(StandardCharsets.UTF_8);

    HttpResponse<String> pushed =
        send(put("/repository/com/gimle/examples/greeter/provider/1.0.0/provider-1.0.0.jar", jar));
    assertEquals(200, pushed.statusCode());

    HttpResponse<byte[]> downloaded =
        client.send(
            HttpRequest.newBuilder(uri("/artifacts/com.gimle.examples.greeter.provider/1.0.0"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, downloaded.statusCode());
    assertArrayEquals(jar, downloaded.body());
  }

  @Test
  @Timeout(10)
  void a_jar_pushed_through_the_operational_surface_is_downloadable_via_the_repository_path()
      throws Exception {
    byte[] jar = "same-store-different-door".getBytes(StandardCharsets.UTF_8);
    send(put("/artifacts/com.gimle.app/1.0.0", jar));

    HttpResponse<byte[]> downloaded =
        client.send(
            HttpRequest.newBuilder(uri("/repository/com/gimle/app/1.0.0/app-1.0.0.jar"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());

    assertEquals(200, downloaded.statusCode());
    assertArrayEquals(jar, downloaded.body());
  }

  @Test
  @Timeout(10)
  void a_differing_re_push_through_the_repository_path_is_still_refused_as_immutable()
      throws Exception {
    send(
        put(
            "/repository/com/gimle/app/1.0.0/app-1.0.0.jar",
            "original".getBytes(StandardCharsets.UTF_8)));

    HttpResponse<String> conflict =
        send(
            put(
                "/repository/com/gimle/app/1.0.0/app-1.0.0.jar",
                "tampered".getBytes(StandardCharsets.UTF_8)));

    assertEquals(409, conflict.statusCode());
  }

  @Test
  @Timeout(10)
  void the_jar_checksum_is_always_server_computed_and_ignores_an_uploaded_sidecar()
      throws Exception {
    byte[] jar = "real-bytes".getBytes(StandardCharsets.UTF_8);
    send(put("/repository/com/gimle/app/1.0.0/app-1.0.0.jar", jar));
    // A client uploading its own (wrong) checksum sidecar must never poison what GET serves back.
    send(
        put(
            "/repository/com/gimle/app/1.0.0/app-1.0.0.jar.sha256",
            "not-a-real-digest".getBytes(StandardCharsets.UTF_8)));

    HttpResponse<String> checksum =
        send(get("/repository/com/gimle/app/1.0.0/app-1.0.0.jar.sha256"));

    assertEquals(200, checksum.statusCode());
    assertEquals(sha256Of(jar), checksum.body());
  }

  @Test
  @Timeout(10)
  void a_pom_sidecar_round_trips_opaquely() throws Exception {
    String pom = "<project><modelVersion>4.0.0</modelVersion></project>";

    HttpResponse<String> pushed =
        send(
            put(
                "/repository/com/gimle/app/1.0.0/app-1.0.0.pom",
                pom.getBytes(StandardCharsets.UTF_8)));
    assertEquals(200, pushed.statusCode());

    HttpResponse<String> fetched = send(get("/repository/com/gimle/app/1.0.0/app-1.0.0.pom"));
    assertEquals(200, fetched.statusCode());
    assertEquals(pom, fetched.body());
  }

  @Test
  @Timeout(10)
  void every_maven_path_answers_head_the_same_way_it_answers_get() throws Exception {
    send(
        put(
            "/repository/com/gimle/app/1.0.0/app-1.0.0.jar",
            "v1".getBytes(StandardCharsets.UTF_8)));
    send(
        put(
            "/repository/com/gimle/app/1.0.0/app-1.0.0.pom",
            "<project/>".getBytes(StandardCharsets.UTF_8)));

    // A resolving Maven client HEADs a .pom exactly as it HEADs the jar; answering 405 for one
    // shape and 200 for the other reads to that client as a broken repository.
    assertEquals(200, send(head("/repository/com/gimle/app/1.0.0/app-1.0.0.jar")).statusCode());
    assertEquals(200, send(head("/repository/com/gimle/app/1.0.0/app-1.0.0.pom")).statusCode());
    assertEquals(
        200, send(head("/repository/com/gimle/app/1.0.0/app-1.0.0.jar.sha256")).statusCode());
    assertEquals(200, send(head("/repository/com/gimle/app/maven-metadata.xml")).statusCode());
    assertEquals(
        404, send(head("/repository/com/gimle/app/1.0.0/app-1.0.0-sources.jar")).statusCode());
  }

  @Test
  @Timeout(10)
  void maven_metadata_lists_every_pushed_version_and_names_the_latest() throws Exception {
    send(
        put(
            "/repository/com/gimle/app/1.0.0/app-1.0.0.jar",
            "v1".getBytes(StandardCharsets.UTF_8)));
    send(
        put(
            "/repository/com/gimle/app/2.0.0/app-2.0.0.jar",
            "v2".getBytes(StandardCharsets.UTF_8)));

    HttpResponse<String> metadata = send(get("/repository/com/gimle/app/maven-metadata.xml"));

    assertEquals(200, metadata.statusCode());
    assertTrue(metadata.body().contains("<groupId>com.gimle</groupId>"));
    assertTrue(metadata.body().contains("<artifactId>app</artifactId>"));
    assertTrue(metadata.body().contains("<version>1.0.0</version>"));
    assertTrue(metadata.body().contains("<version>2.0.0</version>"));
    assertTrue(metadata.body().contains("<latest>2.0.0</latest>"));
  }

  @Test
  @Timeout(10)
  void maven_metadata_checksum_is_computed_over_the_generated_document() throws Exception {
    send(
        put(
            "/repository/com/gimle/app/1.0.0/app-1.0.0.jar",
            "v1".getBytes(StandardCharsets.UTF_8)));

    HttpResponse<String> metadata = send(get("/repository/com/gimle/app/maven-metadata.xml"));
    HttpResponse<String> checksum =
        send(get("/repository/com/gimle/app/maven-metadata.xml.sha256"));

    assertEquals(200, checksum.statusCode());
    assertEquals(sha256Of(metadata.body().getBytes(StandardCharsets.UTF_8)), checksum.body());
  }

  @Test
  @Timeout(10)
  void maven_metadata_for_an_unknown_module_is_404() throws Exception {
    assertEquals(404, send(get("/repository/com/gimle/ghost/maven-metadata.xml")).statusCode());
  }

  @Test
  @Timeout(10)
  void a_maven_metadata_put_from_the_deploy_lifecycle_is_a_no_op_success() throws Exception {
    send(
        put(
            "/repository/com/gimle/app/1.0.0/app-1.0.0.jar",
            "v1".getBytes(StandardCharsets.UTF_8)));

    // A real `mvn deploy` PUTs its own locally-computed maven-metadata.xml (and checksum
    // sidecars) as the last step of its deploy lifecycle, even though the server always
    // regenerates this file itself -- that PUT must not fail the whole deploy.
    HttpResponse<String> puttedXml =
        send(
            put(
                "/repository/com/gimle/app/maven-metadata.xml",
                "<metadata><stale/></metadata>".getBytes(StandardCharsets.UTF_8)));
    HttpResponse<String> puttedChecksum =
        send(
            put(
                "/repository/com/gimle/app/maven-metadata.xml.sha256",
                "not-a-real-digest".getBytes(StandardCharsets.UTF_8)));

    assertEquals(200, puttedXml.statusCode());
    assertEquals(200, puttedChecksum.statusCode());

    // The server's own freshly-computed metadata is unaffected by the discarded upload.
    HttpResponse<String> metadata = send(get("/repository/com/gimle/app/maven-metadata.xml"));
    assertEquals(200, metadata.statusCode());
    assertTrue(metadata.body().contains("<version>1.0.0</version>"));
    assertTrue(!metadata.body().contains("stale"));
  }

  @Test
  @Timeout(10)
  void a_single_segment_module_has_an_empty_group_id_in_the_generated_metadata() throws Exception {
    send(put("/repository/hello/1.0.0/hello-1.0.0.jar", "v1".getBytes(StandardCharsets.UTF_8)));

    HttpResponse<String> metadata = send(get("/repository/hello/maven-metadata.xml"));

    assertEquals(200, metadata.statusCode());
    assertTrue(metadata.body().contains("<groupId></groupId>"));
    assertTrue(metadata.body().contains("<artifactId>hello</artifactId>"));
  }

  private URI uri(String path) {
    return URI.create(baseUrl + path);
  }

  private HttpRequest get(String path) {
    return HttpRequest.newBuilder(uri(path)).GET().build();
  }

  private HttpRequest head(String path) {
    return HttpRequest.newBuilder(uri(path))
        .method("HEAD", HttpRequest.BodyPublishers.noBody())
        .build();
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
