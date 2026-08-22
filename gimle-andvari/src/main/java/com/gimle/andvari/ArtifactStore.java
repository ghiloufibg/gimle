package com.gimle.andvari;

import com.gimle.core.hash.Sha256;
import com.gimle.core.io.SizeLimitedInputStream;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);

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
      String pushedBy,
      Optional<String> tenantId) {}

  /** The outcome of a push plus the store's now-authoritative metadata for the coordinate. */
  public record PutResult(PutOutcome outcome, StoredArtifact stored) {}

  /**
   * Thrown by {@link #put} once a push's body has streamed past {@code maxArtifactBytes} --
   * unchecked (see {@link SizeLimitedInputStream}) so it propagates straight out of {@link
   * Files#copy} without {@code put} needing to catch and re-signal it through {@link PutResult},
   * which has no "rejected, no coordinate to attach" shape the way {@link PutOutcome} does. {@code
   * put}'s own {@code finally} block still cleans up the partial temp file, exactly as it does for
   * every other exception.
   */
  public static final class ArtifactTooLargeException extends RuntimeException {
    ArtifactTooLargeException(long maxArtifactBytes) {
      super("upload exceeds the maximum allowed artifact size of " + maxArtifactBytes + " bytes");
    }
  }

  private final Path artifactsRoot;
  private final Path tmpRoot;
  private final Path quarantineRoot;
  private final long maxArtifactBytes;

  public ArtifactStore(Path dataRoot) throws IOException {
    this(dataRoot, Long.MAX_VALUE);
  }

  /**
   * @param maxArtifactBytes the largest body {@link #put} accepts, checked while streaming rather
   *     than against a client-declared (and unenforceable, under chunked transfer encoding) {@code
   *     Content-Length}; see {@link SizeLimitedInputStream}.
   */
  public ArtifactStore(Path dataRoot, long maxArtifactBytes) throws IOException {
    this.artifactsRoot = dataRoot.resolve("artifacts");
    this.tmpRoot = dataRoot.resolve("tmp");
    this.quarantineRoot = dataRoot.resolve("quarantine");
    this.maxArtifactBytes = maxArtifactBytes;
    Files.createDirectories(artifactsRoot);
    Files.createDirectories(tmpRoot);
    Files.createDirectories(quarantineRoot);
    sweepOrphanedTempFiles(tmpRoot);
  }

  /**
   * Deletes everything already sitting in {@code tmpRoot} at construction time. {@link #put} and
   * {@link #putSidecar} both stream into a temp file there before an atomic rename into place,
   * cleaning up their own temp file in a {@code finally} block -- but a hard process kill (OOM,
   * SIGKILL, host crash) mid-upload skips that {@code finally} entirely and leaves the partial file
   * behind forever, since nothing else ever revisits {@code tmpRoot} on its own. This constructor
   * runs once per process before any upload can possibly be in flight, so anything already there is
   * provably an orphan from a previous run, never a live upload racing this sweep. Best-effort: a
   * failure here is logged, not thrown -- an unswept orphan wastes disk but never corrupts the
   * store (it sits outside every {@code (moduleId, version)} directory {@link #meta}/{@link
   * #jarPath} ever read from), so it must not block startup.
   */
  private static void sweepOrphanedTempFiles(Path tmpRoot) {
    int swept = 0;
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(tmpRoot)) {
      for (Path entry : entries) {
        try {
          if (Files.deleteIfExists(entry)) {
            swept++;
          }
        } catch (IOException e) {
          log.warn("failed to sweep orphaned temp file {}: {}", entry, e.getMessage());
        }
      }
    } catch (IOException e) {
      log.warn("failed to list temp directory {} for orphan sweep: {}", tmpRoot, e.getMessage());
      return;
    }
    if (swept > 0) {
      log.info(
          "swept {} orphaned temp upload(s) left behind by a previous run under {}",
          swept,
          tmpRoot);
    }
  }

  /**
   * Streams {@code body} to disk, digesting as it goes, and commits it under {@code (moduleId,
   * version)} -- unless the coordinate already exists, in which case the upload is compared by
   * checksum and discarded either way (idempotent for identical bytes, refused for differing ones).
   * The commit section is synchronized so two concurrent pushes of one coordinate serialize into
   * exactly one CREATED and one IDENTICAL/CONFLICT, never a torn overwrite. Throws {@link
   * ArtifactTooLargeException} if {@code body} streams past {@code maxArtifactBytes} -- the partial
   * temp file is still cleaned up by the {@code finally} below, same as any other failure mid-push.
   *
   * <p>{@code tenantId} is part of a coordinate's identity the same way its digest is, with one
   * allowed exception: a coordinate first pushed with no tenant can be claimed by a later push that
   * supplies one (a metadata-only backfill into {@code meta.json}, still reported as {@code
   * IDENTICAL} since the jar's own bytes never change), but a tenant already on record can never be
   * swapped for a different one -- that re-push is a {@code CONFLICT}, exactly like a differing
   * digest, since an immutable coordinate's ownership shouldn't change out from under whoever
   * already trusts it. Authorizing the claim itself (does this caller actually hold write
   * permission for the tenant being claimed) is {@code AndvariServer}'s job, not this store's.
   */
  /**
   * Convenience overload for an untenanted push -- equivalent to passing {@link Optional#empty}.
   */
  public PutResult put(String moduleId, String version, InputStream body, String pushedBy)
      throws IOException {
    return put(moduleId, version, body, pushedBy, Optional.empty());
  }

  public PutResult put(
      String moduleId, String version, InputStream body, String pushedBy, Optional<String> tenantId)
      throws IOException {
    requireValidSegment(moduleId, "moduleId");
    requireValidSegment(version, "version");
    Path tempFile = tmpRoot.resolve("upload-" + UUID.randomUUID() + ".jar");
    String sha256;
    long sizeBytes;
    try {
      MessageDigest digest = sha256Digest();
      try (DigestInputStream digesting =
          new DigestInputStream(
              new SizeLimitedInputStream(
                  body,
                  maxArtifactBytes,
                  exceeded -> new ArtifactTooLargeException(maxArtifactBytes)),
              digest)) {
        sizeBytes = Files.copy(digesting, tempFile, StandardCopyOption.REPLACE_EXISTING);
      }
      sha256 = HexFormat.of().formatHex(digest.digest());
      synchronized (this) {
        Optional<StoredArtifact> existing = meta(moduleId, version);
        if (existing.isPresent()) {
          return putOverExisting(moduleId, version, sha256, tenantId, existing.get());
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
                moduleId,
                version,
                sha256,
                sizeBytes,
                System.currentTimeMillis(),
                pushedBy,
                tenantId);
        Files.writeString(versionDir.resolve(META_FILE), Json.write(metaJson(stored)));
        return new PutResult(PutOutcome.CREATED, stored);
      }
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  /**
   * The re-push branch of {@link #put}, once a coordinate is already known to exist: a differing
   * digest is always a {@code CONFLICT}. A matching digest is ordinarily {@code IDENTICAL} and
   * writes nothing -- except when the existing artifact has no recorded tenant and this push
   * supplies one, the one allowed backfill {@link #put}'s own javadoc describes, which still
   * reports {@code IDENTICAL} (no new bytes) but rewrites {@code meta.json} to claim the tenant. A
   * matching digest whose requested tenant conflicts with an already-recorded, different tenant is
   * a {@code CONFLICT} too, even though the bytes match -- ownership, once set, is as immutable as
   * content.
   */
  private PutResult putOverExisting(
      String moduleId,
      String version,
      String sha256,
      Optional<String> requestedTenant,
      StoredArtifact existing)
      throws IOException {
    if (!existing.sha256().equals(sha256)) {
      return new PutResult(PutOutcome.CONFLICT, existing);
    }
    if (existing.tenantId().isPresent()
        && requestedTenant.isPresent()
        && !existing.tenantId().get().equals(requestedTenant.get())) {
      return new PutResult(PutOutcome.CONFLICT, existing);
    }
    if (existing.tenantId().isEmpty() && requestedTenant.isPresent()) {
      StoredArtifact claimed =
          new StoredArtifact(
              moduleId,
              version,
              existing.sha256(),
              existing.sizeBytes(),
              existing.pushedAtEpochMilli(),
              existing.pushedBy(),
              requestedTenant);
      Files.writeString(
          versionDir(moduleId, version).resolve(META_FILE), Json.write(metaJson(claimed)));
      return new PutResult(PutOutcome.IDENTICAL, claimed);
    }
    return new PutResult(PutOutcome.IDENTICAL, existing);
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
              String.valueOf(parsed.get("pushedBy")),
              // Absent on every meta.json written before tenant tagging existed -- parses as
              // untenanted rather than failing, the same backward-compatibility posture every
              // other optional field added to a persisted format in this codebase already takes.
              Optional.ofNullable((String) parsed.get("tenantId"))));
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
   * Copies {@code source}'s bytes to {@code out} while digesting them, returning the streamed
   * bytes' own hex sha256 -- the read-time counterpart to {@link #put}'s digest-while-streaming
   * discipline (same {@link DigestInputStream} idiom, same never-buffer-the-whole-file-in-memory
   * constraint). Used both by {@code AndvariServer#handleDownload}'s per-request integrity re-check
   * (streaming to the response body) and by a periodic full-store scrub (streaming to {@link
   * OutputStream#nullOutputStream()}, discarding the bytes and keeping only the digest). This
   * method only ever reports what was actually read; the caller decides what "expected" means and
   * what to do about a mismatch against {@link StoredArtifact#sha256}.
   */
  public static String copyAndDigest(Path source, OutputStream out) throws IOException {
    MessageDigest digest = sha256Digest();
    try (DigestInputStream digesting =
        new DigestInputStream(Files.newInputStream(source), digest)) {
      digesting.transferTo(out);
    }
    return HexFormat.of().formatHex(digest.digest());
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

  /**
   * Every stored version of one module, sorted oldest-to-newest by {@link #compareVersions}
   * (semver-aware, not lexicographic); empty for an unknown module. {@link
   * AndvariServer#generateMavenMetadataXml} relies on this ascending order to read the last element
   * as the actual latest/release version.
   */
  public List<StoredArtifact> versions(String moduleId) {
    requireValidSegment(moduleId, "moduleId");
    Path moduleDir = artifactsRoot.resolve(moduleId);
    List<StoredArtifact> result = new ArrayList<>();
    for (String version : listDirectoryNames(moduleDir)) {
      meta(moduleId, version).ifPresent(result::add);
    }
    result.sort(Comparator.comparing(StoredArtifact::version, ArtifactStore::compareVersions));
    return result;
  }

  /**
   * Semver-aware version comparison via {@link Version#parse}/{@link Version#compareTo} -- plain
   * {@link String#compareTo} would sort {@code "1.10.0"} before {@code "1.2.0"} lexicographically,
   * silently naming the wrong version as "latest" in {@link AndvariServer#generateMavenMetadataXml}
   * the moment any module reaches a double-digit major/minor/patch segment. Falls back to a plain
   * string comparison when either side isn't {@code major.minor.patch[-qualifier]}-shaped, rather
   * than throwing: {@code version} is only path-segment-validated at push time (see {@link
   * #SEGMENT}), not semver-validated, so a non-semver coordinate (e.g. a bare build number) must
   * still sort deterministically instead of failing every catalog/versions/repository request for
   * that module.
   */
  private static int compareVersions(String a, String b) {
    try {
      return Version.parse(a).compareTo(Version.parse(b));
    } catch (IllegalArgumentException e) {
      return a.compareTo(b);
    }
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

  /**
   * Moves a stored version's entire versionDir (jar, meta.json, and any sidecars) out of the live
   * store and into {@code {dataRoot}/quarantine/{moduleId}/{version}-{epochMillis}/} -- called when
   * a digest re-check (on a GET, or during a periodic full-store scrub; see {@code
   * AndvariServer#reportIntegrityFailure}) finds the on-disk bytes no longer hash to what {@link
   * #meta} recorded, which can only mean bit rot or a partial write this store's own atomic-rename
   * discipline should have prevented but a corrupted filesystem can still produce underneath it.
   * Quarantining rather than deleting outright preserves the corrupted bytes for forensic
   * inspection; the coordinate itself disappears from {@link #versions}/{@link #moduleIds} exactly
   * like a real {@link #delete}, so a re-push is required before it's servable again. Best-effort:
   * a failure to move is logged, not thrown -- a quarantine that couldn't complete leaves the
   * corrupted version still discoverable, which is a strictly worse but not a newly-broken state
   * relative to not attempting quarantine at all.
   */
  public boolean quarantine(String moduleId, String version) {
    requireValidSegment(moduleId, "moduleId");
    requireValidSegment(version, "version");
    Path versionDir = versionDir(moduleId, version);
    if (!Files.isDirectory(versionDir)) {
      return false;
    }
    try {
      Path destination =
          quarantineRoot.resolve(moduleId).resolve(version + "-" + System.currentTimeMillis());
      Path destinationParent = destination.getParent();
      if (destinationParent != null) {
        Files.createDirectories(destinationParent);
      }
      Files.move(versionDir, destination);
      Path moduleDir = versionDir.getParent();
      if (moduleDir != null && listDirectoryNames(moduleDir).isEmpty()) {
        Files.deleteIfExists(moduleDir);
      }
      log.warn("quarantined {}:{} to {}", moduleId, version, destination);
      return true;
    } catch (IOException e) {
      log.warn("failed to quarantine {}:{}: {}", moduleId, version, e.getMessage());
      return false;
    }
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
    stored.tenantId().ifPresent(tenantId -> json.put("tenantId", tenantId));
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
    return Sha256.sha256Digest();
  }
}
