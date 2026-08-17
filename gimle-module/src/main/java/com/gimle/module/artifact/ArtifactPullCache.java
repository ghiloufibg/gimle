package com.gimle.module.artifact;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.hash.Sha256;
import com.gimle.core.module.ModuleId;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The local pull-through cache in front of the Andvari artifact registry: a {@code (moduleId,
 * version)} coordinate resolves to {@code {cacheRoot}/{name}/{version}/artifact.jar}, downloaded
 * once and then trusted by presence alone -- the {@code imagePullPolicy: IfNotPresent} semantics.
 * Presence-only trust is sound because the registry's store is immutable: a coordinate's bytes can
 * never change once pushed, so re-hashing a cached jar on every install would be pure waste.
 *
 * <p>A download streams through a {@link DigestInputStream} into a temp file, is verified against
 * the digest the registry itself advertises ({@code X-Gimle-Artifact-Sha256}), and commits with an
 * atomic rename -- a crash mid-download can never leave a torn jar visible at the final path, the
 * same commit discipline the registry's own store uses on the push side.
 *
 * <p>Deliberately takes the {@link HttpClient} per call rather than holding one: the node agent
 * rebuilds its client on certificate rotation, and a client captured at construction time would go
 * stale after the first rotation.
 */
public final class ArtifactPullCache {

  private static final Logger log = LoggerFactory.getLogger(ArtifactPullCache.class);
  private static final String SHA256_HEADER = "X-Gimle-Artifact-Sha256";

  private final Path cacheRoot;

  public ArtifactPullCache(Path cacheRoot) {
    this.cacheRoot = cacheRoot;
  }

  /** The cached jar for a coordinate, empty on a cache miss -- never a network call. */
  public Optional<Path> cachedJar(ModuleId moduleId) {
    Path jar = jarPath(moduleId);
    return Files.isRegularFile(jar) ? Optional.of(jar) : Optional.empty();
  }

  /**
   * Resolves a coordinate to a local jar path: the cached copy when present, otherwise one
   * download-verify-commit round trip against the registry at {@code andvariBaseUrl}. Every failure
   * mode (registry unreachable, coordinate unknown, digest mismatch) throws a {@link
   * GimleManifestException} whose message names the coordinate -- callers surface it rather than
   * retry here, since the enclosing reconcile loop already re-runs level-triggered.
   */
  public Path resolve(HttpClient httpClient, URI andvariBaseUrl, ModuleId moduleId) {
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
  public Path resolve(HttpClient httpClient, List<URI> andvariBaseUrls, ModuleId moduleId) {
    Optional<Path> cached = cachedJar(moduleId);
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

  private Path download(HttpClient httpClient, URI andvariBaseUrl, ModuleId moduleId) {
    URI uri =
        andvariBaseUrl.resolve(
            "/artifacts/" + moduleId.name() + "/" + moduleId.version().toString());
    Path versionDir = versionDir(moduleId);
    Path targetJar = versionDir.resolve("artifact.jar");
    Path tempFile = null;
    try {
      HttpResponse<InputStream> response =
          httpClient.send(
              HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),
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
      // REPLACE_EXISTING tolerates a concurrent resolver having won the race -- immutability
      // guarantees both downloads carried identical bytes, so last-writer-wins is harmless.
      Files.move(
          tempFile, targetJar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      log.info(
          "cached artifact {} ({} bytes) from {}",
          coordinate(moduleId),
          Files.size(targetJar),
          uri);
      return targetJar;
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

  private Path jarPath(ModuleId moduleId) {
    return versionDir(moduleId).resolve("artifact.jar");
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
