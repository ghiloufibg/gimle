package com.gimle.module.artifact;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.hash.Sha256;
import com.gimle.core.module.ArtifactKind;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.vessel.VesselEntrypoint;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pull-through cache against a fake registry over a real loopback socket: jar and bundle
 * download/verify/commit, the zip-slip guard, entrypoint validation before commit, and the
 * presence-only cache-hit contract (no network call on a hit).
 */
class ArtifactPullCacheTest {

  private static final ModuleId APP = new ModuleId("com.example.app", Version.parse("1.0.0"));

  @TempDir Path tempDir;

  private HttpServer registry;
  private URI baseUrl;
  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final AtomicInteger requestCount = new AtomicInteger();
  private final Map<String, byte[]> artifacts = new LinkedHashMap<>();
  private final Map<String, ArtifactKind> kinds = new LinkedHashMap<>();

  private ArtifactPullCache cache;

  @BeforeEach
  void setUp() throws IOException {
    cache = new ArtifactPullCache(tempDir.resolve("cache"));
    registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    registry.createContext(
        "/artifacts",
        exchange -> {
          requestCount.incrementAndGet();
          String coordinate = exchange.getRequestURI().getPath().substring("/artifacts/".length());
          byte[] body = artifacts.get(coordinate);
          if (body == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
          }
          exchange.getResponseHeaders().add("X-Gimle-Artifact-Sha256", Sha256.sha256Hex(body));
          exchange.getResponseHeaders().add("X-Gimle-Artifact-Kind", kinds.get(coordinate).name());
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    registry.start();
    baseUrl = URI.create("http://127.0.0.1:" + registry.getAddress().getPort());
  }

  @AfterEach
  void tearDown() {
    registry.stop(0);
  }

  private void serve(ModuleId moduleId, byte[] body, ArtifactKind kind) {
    String coordinate = moduleId.name() + "/" + moduleId.version();
    artifacts.put(coordinate, body);
    kinds.put(coordinate, kind);
  }

  private static byte[] zipOf(Map<String, String> entries) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (Map.Entry<String, String> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  private static Map<String, String> bundleEntries(String... extraNamesAndContents) {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put(VesselEntrypoint.FILE_NAME, "command: [java, -jar, app.jar]\nworkdir: .\n");
    entries.put("app.jar", "pretend-jar-bytes");
    entries.put("lib/dep.jar", "pretend-dep-bytes");
    for (int i = 0; i + 1 < extraNamesAndContents.length; i += 2) {
      entries.put(extraNamesAndContents[i], extraNamesAndContents[i + 1]);
    }
    return entries;
  }

  @Test
  @Timeout(20)
  void a_jar_resolves_to_a_cached_file_and_hits_without_a_network_call() {
    byte[] jar = "pretend-jar-bytes".getBytes(StandardCharsets.UTF_8);
    serve(APP, jar, ArtifactKind.JAR);

    ResolvedArtifact first = cache.resolve(httpClient, baseUrl, APP);
    assertEquals(ArtifactKind.JAR, first.kind());
    assertTrue(Files.isRegularFile(first.path()));

    int requestsAfterFirst = requestCount.get();
    ResolvedArtifact second = cache.resolve(httpClient, baseUrl, APP);
    assertEquals(first.path(), second.path());
    assertEquals(requestsAfterFirst, requestCount.get());
  }

  @Test
  @Timeout(20)
  void a_bundle_resolves_to_an_unpacked_directory_with_its_entrypoint() throws IOException {
    serve(APP, zipOf(bundleEntries()), ArtifactKind.BUNDLE);

    ResolvedArtifact resolved = cache.resolve(httpClient, baseUrl, APP);

    assertEquals(ArtifactKind.BUNDLE, resolved.kind());
    assertTrue(Files.isDirectory(resolved.path()));
    assertEquals(List.of("java", "-jar", "app.jar"), resolved.entrypoint().orElseThrow().command());
    assertArrayEquals(
        "pretend-dep-bytes".getBytes(StandardCharsets.UTF_8),
        Files.readAllBytes(resolved.path().resolve("lib/dep.jar")));
  }

  @Test
  @Timeout(20)
  void a_cached_bundle_hits_without_a_network_call() throws IOException {
    serve(APP, zipOf(bundleEntries()), ArtifactKind.BUNDLE);
    cache.resolve(httpClient, baseUrl, APP);
    int requestsAfterFirst = requestCount.get();

    ResolvedArtifact hit = cache.resolve(httpClient, baseUrl, APP);

    assertEquals(ArtifactKind.BUNDLE, hit.kind());
    assertTrue(hit.entrypoint().isPresent());
    assertEquals(requestsAfterFirst, requestCount.get());
  }

  @Test
  @Timeout(20)
  void a_zip_slip_entry_aborts_the_unpack_and_commits_nothing() throws IOException {
    serve(APP, zipOf(bundleEntries("../escaped.txt", "outside")), ArtifactKind.BUNDLE);

    assertThrows(GimleManifestException.class, () -> cache.resolve(httpClient, baseUrl, APP));

    assertTrue(cache.cached(APP).isEmpty());
    assertFalse(Files.exists(tempDir.resolve("escaped.txt")));
  }

  @Test
  @Timeout(20)
  void a_bundle_without_an_entrypoint_fails_before_commit() throws IOException {
    Map<String, String> entries = bundleEntries();
    entries.remove(VesselEntrypoint.FILE_NAME);
    serve(APP, zipOf(entries), ArtifactKind.BUNDLE);

    assertThrows(GimleManifestException.class, () -> cache.resolve(httpClient, baseUrl, APP));
    assertTrue(cache.cached(APP).isEmpty());
  }

  @Test
  @Timeout(20)
  void an_unknown_coordinate_fails_naming_it() {
    GimleManifestException failure =
        assertThrows(GimleManifestException.class, () -> cache.resolve(httpClient, baseUrl, APP));
    assertTrue(failure.getMessage().contains("com.example.app"));
  }
}
