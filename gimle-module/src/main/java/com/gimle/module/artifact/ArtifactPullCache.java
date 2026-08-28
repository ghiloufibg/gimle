package com.gimle.module.artifact;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.hash.Sha256;
import com.gimle.core.module.ArtifactKind;
import com.gimle.core.module.ModuleId;
import com.gimle.core.vessel.VesselEntrypoint;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The local pull-through cache in front of the Andvari artifact registry: a {@code (moduleId,
 * version)} coordinate resolves to {@code {cacheRoot}/{name}/{version}/artifact.jar} for a
 * single-jar artifact, or to the unpacked directory {@code {cacheRoot}/{name}/{version}/bundle/}
 * for a bundle -- downloaded once and then trusted by presence alone, the {@code imagePullPolicy:
 * IfNotPresent} semantics. Presence-only trust is sound because the registry's store is immutable:
 * a coordinate's bytes (and kind) can never change once pushed, so re-hashing a cached artifact on
 * every install would be pure waste.
 *
 * <p>A download streams through a {@link DigestInputStream} into a temp file, is verified against
 * the digest the registry itself advertises ({@code X-Gimle-Artifact-Sha256}), and commits with an
 * atomic rename -- a crash mid-download can never leave a torn jar (or half-unpacked bundle
 * directory) visible at the final path, the same commit discipline the registry's own store uses on
 * the push side. A bundle is additionally unpacked into a temp sibling directory first -- with
 * every entry's destination verified to stay inside it, and the {@code gimle-entrypoint.yaml}
 * launch descriptor parsed and validated -- before the whole directory is renamed into place, so a
 * bundle that would fail to launch is never committed to the cache at all.
 *
 * <p>Deliberately takes the {@link HttpClient} per call rather than holding one: the node agent
 * rebuilds its client on certificate rotation, and a client captured at construction time would go
 * stale after the first rotation.
 */
public final class ArtifactPullCache {

  private static final Logger log = LoggerFactory.getLogger(ArtifactPullCache.class);
  private static final String SHA256_HEADER = "X-Gimle-Artifact-Sha256";
  private static final String KIND_HEADER = "X-Gimle-Artifact-Kind";
  private static final String JAR_FILE = "artifact.jar";
  private static final String BUNDLE_DIR = "bundle";
  private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(30);

  private final Path cacheRoot;

  public ArtifactPullCache(Path cacheRoot) {
    this.cacheRoot = cacheRoot;
  }

  /**
   * The cached artifact for a coordinate, empty on a cache miss -- never a network call. The kind
   * is read off what's on disk ({@code artifact.jar} file vs {@code bundle/} directory -- the two
   * can never coexist for one coordinate, since a coordinate's kind is immutable in the registry),
   * and a bundle's entrypoint is re-read from the cached directory.
   */
  public Optional<ResolvedArtifact> cached(ModuleId moduleId) {
    Path versionDir = versionDir(moduleId);
    Path jar = versionDir.resolve(JAR_FILE);
    if (Files.isRegularFile(jar)) {
      return Optional.of(new ResolvedArtifact(jar, ArtifactKind.JAR, Optional.empty()));
    }
    Path bundleDir = versionDir.resolve(BUNDLE_DIR);
    if (Files.isDirectory(bundleDir)) {
      VesselEntrypoint entrypoint = VesselEntrypointParser.parseFromBundleRoot(bundleDir);
      return Optional.of(
          new ResolvedArtifact(bundleDir, ArtifactKind.BUNDLE, Optional.of(entrypoint)));
    }
    return Optional.empty();
  }

  /**
   * Resolves a coordinate: the cached copy when present, otherwise one download-verify-commit round
   * trip against the registry at {@code andvariBaseUrl}. Every failure mode (registry unreachable,
   * coordinate unknown, digest mismatch, a bundle whose archive escapes its own root or carries no
   * valid entrypoint) throws a {@link GimleManifestException} whose message names the coordinate --
   * callers surface it rather than retry here, since the enclosing reconcile loop already re-runs
   * level-triggered.
   */
  public ResolvedArtifact resolve(HttpClient httpClient, URI andvariBaseUrl, ModuleId moduleId) {
    return resolve(httpClient, List.of(andvariBaseUrl), moduleId);
  }

  /**
   * Multi-endpoint form: tries each configured registry endpoint in order, moving to the next only
   * once one fails (unreachable, coordinate not found there, or a bad digest), so a replica that
   * hasn't yet caught up on a peer-sync tick -- or is simply down -- doesn't fail a resolution that
   * a different configured replica could have answered. Andvari has no leader to route writes
   * through the way {@code gimle-mimir}'s store does, so every configured endpoint is equally
   * eligible to answer a pull; this is the read-side counterpart to {@code AndvariClient}'s own
   * failover for the same reason.
   */
  public ResolvedArtifact resolve(
      HttpClient httpClient, List<URI> andvariBaseUrls, ModuleId moduleId) {
    Optional<ResolvedArtifact> cached = cached(moduleId);
    if (cached.isPresent()) {
      return cached.get();
    }
    if (andvariBaseUrls.isEmpty()) {
      throw new GimleManifestException(
          "no artifact registry endpoint configured to resolve " + coordinate(moduleId));
    }
    GimleManifestException lastFailure = null;
    for (URI andvariBaseUrl : andvariBaseUrls) {
      try {
        return download(httpClient, andvariBaseUrl, moduleId);
      } catch (GimleManifestException e) {
        lastFailure = e;
        log.warn(
            "failed to pull {} from {}, trying the next configured endpoint: {}",
            coordinate(moduleId),
            andvariBaseUrl,
            e.getMessage());
      }
    }
    throw lastFailure;
  }

  private ResolvedArtifact download(HttpClient httpClient, URI andvariBaseUrl, ModuleId moduleId) {
    URI uri =
        andvariBaseUrl.resolve(
            "/artifacts/" + moduleId.name() + "/" + moduleId.version().toString());
    Path versionDir = versionDir(moduleId);
    Path tempFile = null;
    try {
      HttpResponse<InputStream> response =
          httpClient.send(
              HttpRequest.newBuilder(uri).timeout(DOWNLOAD_TIMEOUT).GET().build(),
              HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() == 404) {
        throw new GimleManifestException(
            "artifact " + coordinate(moduleId) + " is not in the registry at " + andvariBaseUrl);
      }
      if (response.statusCode() != 200) {
        throw new GimleManifestException(
            "artifact registry answered "
                + response.statusCode()
                + " for "
                + coordinate(moduleId)
                + " at "
                + andvariBaseUrl);
      }
      String expectedSha256 =
          response
              .headers()
              .firstValue(SHA256_HEADER)
              .orElseThrow(
                  () ->
                      new GimleManifestException(
                          "artifact registry response for "
                              + coordinate(moduleId)
                              + " carries no "
                              + SHA256_HEADER
                              + " header; refusing an unverifiable download"));
      ArtifactKind kind =
          ArtifactKind.parse(response.headers().firstValue(KIND_HEADER).orElse(null));
      Files.createDirectories(versionDir);
      tempFile = versionDir.resolve("download-" + UUID.randomUUID() + ".tmp");
      MessageDigest digest = sha256Digest();
      try (DigestInputStream digesting = new DigestInputStream(response.body(), digest)) {
        Files.copy(digesting, tempFile, StandardCopyOption.REPLACE_EXISTING);
      }
      String actualSha256 = HexFormat.of().formatHex(digest.digest());
      if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
        throw new GimleManifestException(
            "artifact "
                + coordinate(moduleId)
                + " failed digest verification: registry advertised "
                + expectedSha256
                + " but the downloaded bytes hash to "
                + actualSha256);
      }
      long downloadedBytes = Files.size(tempFile);
      ResolvedArtifact resolved =
          kind == ArtifactKind.BUNDLE
              ? commitBundle(tempFile, versionDir, moduleId)
              : commitJar(tempFile, versionDir);
      log.info(
          "cached {} artifact {} ({} bytes) from {}",
          kind,
          coordinate(moduleId),
          downloadedBytes,
          uri);
      return resolved;
    } catch (IOException e) {
      throw new GimleManifestException(
          "failed to pull artifact "
              + coordinate(moduleId)
              + " from the registry at "
              + andvariBaseUrl
              + ": "
              + e.getMessage(),
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GimleManifestException(
          "interrupted while pulling artifact " + coordinate(moduleId), e);
    } finally {
      if (tempFile != null) {
        try {
          Files.deleteIfExists(tempFile);
        } catch (IOException e) {
          log.warn("failed to delete temp download file {}: {}", tempFile, e.getMessage());
        }
      }
    }
  }

  private ResolvedArtifact commitJar(Path tempFile, Path versionDir) throws IOException {
    Path targetJar = versionDir.resolve(JAR_FILE);
    // REPLACE_EXISTING tolerates a concurrent resolver having won the race -- immutability
    // guarantees both downloads carried identical bytes, so last-writer-wins is harmless.
    Files.move(
        tempFile, targetJar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    return new ResolvedArtifact(targetJar, ArtifactKind.JAR, Optional.empty());
  }

  /**
   * Unpacks the verified zip into a temp sibling directory, validates it there (zip-slip guard on
   * every entry, entrypoint parsed and validated), and only then renames the whole directory into
   * place -- the directory-level equivalent of {@link #commitJar}'s never-a-torn-file guarantee. A
   * concurrent resolver winning the rename race is harmless for the same immutability reason;
   * whichever directory landed carries identical content.
   */
  private ResolvedArtifact commitBundle(Path zipFile, Path versionDir, ModuleId moduleId)
      throws IOException {
    Path bundleDir = versionDir.resolve(BUNDLE_DIR);
    Path tempDir =
        Files.createDirectory(versionDir.resolve("unpack-" + UUID.randomUUID() + ".tmp"));
    try {
      unzip(zipFile, tempDir, moduleId);
      VesselEntrypoint entrypoint = VesselEntrypointParser.parseFromBundleRoot(tempDir);
      try {
        Files.move(tempDir, bundleDir);
      } catch (IOException moveFailure) {
        if (!Files.isDirectory(bundleDir)) {
          throw moveFailure;
        }
        // A concurrent resolver committed first; its content is identical, so use it.
      }
      return new ResolvedArtifact(bundleDir, ArtifactKind.BUNDLE, Optional.of(entrypoint));
    } finally {
      deleteRecursivelyIfExists(tempDir);
    }
  }

  private static void unzip(Path zipFile, Path targetDir, ModuleId moduleId) throws IOException {
    Path normalizedTarget = targetDir.normalize();
    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        Path destination = normalizedTarget.resolve(entry.getName()).normalize();
        // The zip-slip guard: an entry named to escape the extraction root ("../…", an absolute
        // path) aborts the whole unpack before anything is committed.
        if (!destination.startsWith(normalizedTarget)) {
          throw new GimleManifestException(
              "bundle "
                  + coordinate(moduleId)
                  + " contains an archive entry escaping its own root: "
                  + entry.getName());
        }
        if (entry.isDirectory()) {
          Files.createDirectories(destination);
        } else {
          Path parent = destination.getParent();
          if (parent != null) {
            Files.createDirectories(parent);
          }
          Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        zip.closeEntry();
      }
    }
  }

  private static void deleteRecursivelyIfExists(Path directory) {
    if (!Files.exists(directory)) {
      return;
    }
    try (Stream<Path> tree = Files.walk(directory)) {
      tree.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException e) {
                  log.warn("failed to delete {}: {}", path, e.getMessage());
                }
              });
    } catch (IOException e) {
      log.warn("failed to clean up {}: {}", directory, e.getMessage());
    }
  }

  private Path versionDir(ModuleId moduleId) {
    return cacheRoot.resolve(moduleId.name()).resolve(moduleId.version().toString());
  }

  private static String coordinate(ModuleId moduleId) {
    return moduleId.name() + ":" + moduleId.version().toString();
  }

  private static MessageDigest sha256Digest() {
    return Sha256.sha256Digest();
  }
}
