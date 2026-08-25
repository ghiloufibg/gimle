package com.gimle.controlplane.andvari;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.hash.Sha256;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.core.vessel.VesselProbes;
import com.gimle.core.vessel.VesselSpec;
import com.gimle.module.artifact.ArtifactPullCache;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The registry-coordinate vessel branch of {@link ArtifactResolver}: metadata-only -- the digest
 * comes from the registry's own {@code HEAD} answer and the descriptor is synthesized from the
 * {@code vessel:} block, so no {@code GET} (no download, no cache write) is ever issued for a
 * vessel, {@code JAR}-kind and {@code BUNDLE}-kind alike.
 */
class ArtifactResolverTest {

  private static final ModuleId REPORT = new ModuleId("com.example.report", Version.parse("1.0.0"));
  private static final ResourceSpec LIMIT = new ResourceSpec("64Mi", "500m");
  private static final VesselSpec VESSEL =
      new VesselSpec(List.of(), List.of(), Map.of(), List.of(), VesselProbes.NONE, LIMIT, LIMIT);

  // A genuinely valid bundle zip: the module branch's rejection under test fires only after the
  // pull cache has successfully resolved the artifact, so the served bytes must unpack cleanly.
  private static final byte[] ZIP_BYTES = validBundleZip();
  private static final String ZIP_SHA256 = Sha256.sha256Hex(ZIP_BYTES);

  private static byte[] validBundleZip() {
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
      zip.putNextEntry(new java.util.zip.ZipEntry("gimle-entrypoint.yaml"));
      zip.write("command: [java, -jar, app.jar]\n".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new java.util.zip.ZipEntry("app.jar"));
      zip.write("pretend-jar-bytes".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return bytes.toByteArray();
  }

  @TempDir Path tempDir;

  private HttpServer registry;
  private ArtifactResolver resolver;
  private AndvariClient andvariClient;
  private final List<String> seenMethods = new CopyOnWriteArrayList<>();
  private volatile boolean coordinateExists = true;

  @BeforeEach
  void setUp() throws IOException {
    registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    registry.createContext(
        "/artifacts",
        exchange -> {
          seenMethods.add(exchange.getRequestMethod());
          if (!coordinateExists) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
          }
          exchange.getResponseHeaders().add("X-Gimle-Artifact-Sha256", ZIP_SHA256);
          exchange.getResponseHeaders().add("X-Gimle-Artifact-Kind", "BUNDLE");
          if ("GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, ZIP_BYTES.length);
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(ZIP_BYTES);
            }
          } else {
            exchange.sendResponseHeaders(200, -1);
          }
          exchange.close();
        });
    registry.start();
    andvariClient = new AndvariClient("127.0.0.1:" + registry.getAddress().getPort());
    resolver =
        ArtifactResolver.withRegistry(
            andvariClient, new ArtifactPullCache(tempDir.resolve("cache")));
  }

  @AfterEach
  void tearDown() {
    andvariClient.close();
    registry.stop(0);
  }

  @Test
  @Timeout(20)
  void a_registry_vessel_resolves_metadata_only_with_no_download() {
    ModuleArtifact artifact = resolver.resolve("", REPORT, Optional.of(VESSEL));

    assertEquals(ZIP_SHA256, artifact.sha256());
    assertEquals(IsolationTier.TIER_2, artifact.descriptor().isolationTier());
    assertEquals(LIMIT, artifact.descriptor().resourceLimit());
    assertEquals(List.of("HEAD"), seenMethods);
  }

  @Test
  @Timeout(20)
  void a_missing_registry_vessel_coordinate_fails_naming_it() {
    coordinateExists = false;

    GimleManifestException failure =
        assertThrows(
            GimleManifestException.class, () -> resolver.resolve("", REPORT, Optional.of(VESSEL)));
    assertTrue(failure.getMessage().contains("com.example.report"));
  }

  @Test
  @Timeout(20)
  void a_module_spec_naming_a_bundle_coordinate_is_rejected() {
    GimleManifestException failure =
        assertThrows(
            GimleManifestException.class, () -> resolver.resolve("", REPORT, Optional.empty()));
    assertTrue(failure.getMessage().contains("vessel-only"));
  }

  @Test
  void a_local_only_resolver_still_rejects_registry_coordinates_with_a_clear_message() {
    ArtifactResolver localOnly = ArtifactResolver.localOnly();

    GimleManifestException failure =
        assertThrows(
            GimleManifestException.class, () -> localOnly.resolve("", REPORT, Optional.of(VESSEL)));
    assertTrue(failure.getMessage().contains("--andvari-endpoint"));
  }
}
