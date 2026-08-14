package com.gimle.andvari;

import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The immutable, content-addressed jar store behind Andvari's HTTP surface. One version is one
 * whole jar under {@code {dataRoot}/artifacts/{moduleId}/{version}/} beside a small {@code
 * meta.json}; a coordinate is never overwritten -- a re-push with different bytes is a {@link
 * PutOutcome#CONFLICT}, an identical re-push an idempotent {@link PutOutcome#IDENTICAL}. That
 * immutability is load-bearing for every downstream cache: a node that trusts a cached coordinate
 * by presence alone is only sound because the bytes behind it can never change here.
 *
 * <p>Pushes stream through a {@link DigestInputStream} into a temp file and commit with an atomic
 * rename -- never a whole-jar {@code byte[]} in memory, and never a torn file visible at the final
 * path after a crash mid-upload. Deliberately a dumb store: it does not parse {@code
 * gimle-module.yaml} or validate JPMS-ness -- that validation already lives in the worker's own
 * {@code ModuleArtifactReader}, and duplicating it here would be parallel-path drift.
 */
public final class ArtifactStore {

  /**
   * Allow-list for the {@code moduleId} and {@code version} path segments -- same traversal defense
   * as Muninn's own path-segment pattern (no {@code /} or {@code \}), with dots permitted since a
   * module id is a dotted JPMS name.
   */
  private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  private static final String JAR_FILE = "artifact.jar";
  private static final String META_FILE = "meta.json";
  private static final String SIDECARS_DIR = "sidecars";

  /** How one {@link #put} attempt ended; {@code IDENTICAL} and {@code CONFLICT} wrote nothing. */
  public enum PutOutcome {
    CREATED,
    IDENTICAL,
    CONFLICT
  }

  /** One stored version's identity and provenance, the parsed form of its {@code meta.json}. */
  public record StoredArtifact(
      String moduleId,
      String version,
      String sha256,
      long sizeBytes,
      long pushedAtEpochMilli,
      String pushedBy) {}

  /** The outcome of a push plus the store's now-authoritative metadata for the coordinate. */
  public record PutResult(PutOutcome outcome, StoredArtifact stored) {}

  private final Path artifactsRoot;
  private final Path tmpRoot;

  public ArtifactStore(Path dataRoot) throws IOException {
    this.artifactsRoot = dataRoot.resolve("artifacts");
    this.tmpRoot = dataRoot.resolve("tmp");
    Files.createDirectories(artifactsRoot);
    Files.createDirectories(tmpRoot);
  }

  /**
   * Streams {@code body} to disk, digesting as it goes, and commits it under {@code (moduleId,
   * version)} -- unless the coordinate already exists, in which case the upload is compared by
   * checksum and discarded either way (idempotent for identical bytes, refused for differing ones).
   * The commit section is synchronized so two concurrent pushes of one coordinate serialize into
   * exactly one CREATED and one IDENTICAL/CONFLICT, never a torn overwrite.
   */
  public PutResult put(String moduleId, String version, InputStream body, String pushedBy)
      throws IOException {
    requireValidSegment(moduleId, "moduleId");
    requireValidSegment(version, "version");
    Path tempFile = tmpRoot.resolve("upload-" + UUID.randomUUID() + ".jar");
    String sha256;
    long sizeBytes;
    try {
      MessageDigest digest = sha256Digest();
      try (DigestInputStream digesting = new DigestInputStream(body, digest)) {
        sizeBytes = Files.copy(digesting, tempFile, StandardCopyOption.REPLACE_EXISTING);
      }
      sha256 = HexFormat.of().formatHex(digest.digest());
      synchronized (this) {
        Optional<StoredArtifact> existing = meta(moduleId, version);
        if (existing.isPresent()) {
          PutOutcome outcome =
              existing.get().sha256().equals(sha256) ? PutOutcome.IDENTICAL : PutOutcome.CONFLICT;
          return new PutResult(outcome, existing.get());
        }
        Path versionDir = versionDir(moduleId, version);
        Files.createDirectories(versionDir);
        Files.move(
            tempFile,
            versionDir.resolve(JAR_FILE),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
        StoredArtifact stored =
            new StoredArtifact(
                moduleId, version, sha256, sizeBytes, System.currentTimeMillis(), pushedBy);
        Files.writeString(versionDir.resolve(META_FILE), Json.write(metaJson(stored)));
        return new PutResult(PutOutcome.CREATED, stored);
      }
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  /** The stored metadata for a coordinate, empty when nothing is stored there. */
  public Optional<StoredArtifact> meta(String moduleId, String version) {
    requireValidSegment(moduleId, "moduleId");
    requireValidSegment(version, "version");
    Path metaFile = versionDir(moduleId, version).resolve(META_FILE);
    if (!Files.isRegularFile(metaFile)) {
      return Optional.empty();
    }
    try {
      Map<String, Object> parsed = Json.asObject(Json.parse(Files.readString(metaFile)));
      return Optional.of(
          new StoredArtifact(
              moduleId,
              version,
              String.valueOf(parsed.get("sha256")),
              ((Number) parsed.get("sizeBytes")).longValue(),
              ((Number) parsed.get("pushedAtEpochMilli")).longValue(),
              String.valueOf(parsed.get("pushedBy"))));
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

  /** The jar's on-disk path for a coordinate, empty when nothing is stored there. */
  public Optional<Path> jarPath(String moduleId, String version) {
    requireValidSegment(moduleId, "moduleId");
    requireValidSegment(version, "version");
    Path jar = versionDir(moduleId, version).resolve(JAR_FILE);
    return Files.isRegularFile(jar) ? Optional.of(jar) : Optional.empty();
  }

  /**
   * Stores an opaque sidecar file beside a version's jar -- a Maven {@code .pom} or a
   * client-computed checksum upload (the Maven-2 repository surface's own PUTs, never anything
   * Andvari parses or trusts). Unlike {@link #put}, this has no immutability check: a re-uploaded
   * sidecar simply replaces the previous one, since sidecars carry no integrity guarantee of their
   * own to protect -- the jar's own {@code sha256} in {@link #meta} is what a reader actually
   * trusts.
   */
  public void putSidecar(String moduleId, String version, String fileName, InputStream body)
      throws IOException {
    requireValidSegment(moduleId, "moduleId");
    requireValidSegment(version, "version");
    requireValidSegment(fileName, "sidecar file name");
    Path sidecarsDir = versionDir(moduleId, version).resolve(SIDECARS_DIR);
    Files.createDirectories(sidecarsDir);
    Path tempFile = tmpRoot.resolve("sidecar-" + UUID.randomUUID());
    try {
      Files.copy(body, tempFile, StandardCopyOption.REPLACE_EXISTING);
      Files.move(
          tempFile,
          sidecarsDir.resolve(fileName),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  /** A stored sidecar's raw bytes, empty when nothing was ever uploaded under that name. */
  public Optional<byte[]> sidecar(String moduleId, String version, String fileName)
      throws IOException {
    requireValidSegment(moduleId, "moduleId");
    requireValidSegment(version, "version");
    requireValidSegment(fileName, "sidecar file name");
    Path sidecarFile = versionDir(moduleId, version).resolve(SIDECARS_DIR).resolve(fileName);
    return Files.isRegularFile(sidecarFile)
        ? Optional.of(Files.readAllBytes(sidecarFile))
        : Optional.empty();
  }

  /** Every stored version of one module, sorted by version string; empty for an unknown module. */
  public List<StoredArtifact> versions(String moduleId) {
    requireValidSegment(moduleId, "moduleId");
    Path moduleDir = artifactsRoot.resolve(moduleId);
    List<StoredArtifact> result = new ArrayList<>();
    for (String version : listDirectoryNames(moduleDir)) {
      meta(moduleId, version).ifPresent(result::add);
    }
    result.sort(Comparator.comparing(StoredArtifact::version));
    return result;
  }

  /** Every module id with at least one stored version, sorted. */
  public List<String> moduleIds() {
    List<String> result = new ArrayList<>();
    for (String moduleId : listDirectoryNames(artifactsRoot)) {
      if (!versions(moduleId).isEmpty()) {
        result.add(moduleId);
      }
    }
    result.sort(Comparator.naturalOrder());
    return result;
  }

  /** Removes one stored version; {@code false} when nothing was stored at the coordinate. */
  public boolean delete(String moduleId, String version) throws IOException {
    requireValidSegment(moduleId, "moduleId");
    requireValidSegment(version, "version");
    Path versionDir = versionDir(moduleId, version);
    if (!Files.isDirectory(versionDir)) {
      return false;
    }
    Files.deleteIfExists(versionDir.resolve(JAR_FILE));
    Files.deleteIfExists(versionDir.resolve(META_FILE));
    // A version can carry Maven-surface sidecars (a .pom, client-uploaded checksums) that put()
    // never writes and knows nothing about; deleteIfExists(versionDir) below silently no-ops on a
    // non-empty directory, which would otherwise leave that sidecar dir (and this whole version
    // directory) orphaned on disk forever after every caller believes the version is gone.
    deleteDirectoryIfExists(versionDir.resolve(SIDECARS_DIR));
    Files.deleteIfExists(versionDir);
    // A module directory left with no versions disappears from the catalog; removing the empty
    // directory keeps the filesystem in step with what moduleIds() reports.
    Path moduleDir = versionDir.getParent();
    if (moduleDir != null && listDirectoryNames(moduleDir).isEmpty()) {
      Files.deleteIfExists(moduleDir);
    }
    return true;
  }

  private Path versionDir(String moduleId, String version) {
    return artifactsRoot.resolve(moduleId).resolve(version);
  }

  private static Map<String, Object> metaJson(StoredArtifact stored) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("sha256", stored.sha256());
    json.put("sizeBytes", stored.sizeBytes());
    json.put("pushedAtEpochMilli", stored.pushedAtEpochMilli());
    json.put("pushedBy", stored.pushedBy());
    return json;
  }

  private static void deleteDirectoryIfExists(Path directory) throws IOException {
    if (!Files.isDirectory(directory)) {
      return;
    }
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
      for (Path entry : entries) {
        Files.deleteIfExists(entry);
      }
    }
    Files.deleteIfExists(directory);
  }

  private static List<String> listDirectoryNames(Path directory) {
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    List<String> names = new ArrayList<>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
      for (Path entry : entries) {
        Path fileName = entry.getFileName();
        if (fileName != null && Files.isDirectory(entry)) {
          names.add(fileName.toString());
        }
      }
    } catch (IOException e) {
      return List.of();
    }
    return names;
  }

  private static void requireValidSegment(String value, String label) {
    if (value == null || !SEGMENT.matcher(value).matches()) {
      throw new IllegalArgumentException("invalid " + label + ": " + value);
    }
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandatory in every conforming JRE; this is unreachable on a working JDK.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
