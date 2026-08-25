package com.gimle.andvari;

import com.gimle.andvari.ArtifactStore.StoredArtifact;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically re-digests every stored artifact against its recorded sha256, independent of whether
 * it's ever actually downloaded -- {@code AndvariServer#handleDownload}'s own per-request re-check
 * only ever catches corruption on a coordinate someone happens to pull; a version nobody has
 * fetched in months could sit silently corrupted (bit rot, a partial write a crash slipped past the
 * store's own atomic-rename discipline) with nothing to notice it. Off by default (see {@code
 * AndvariMain}'s own {@code -Dgimle.andvari.scrub.enabled} handling) since a full-store digest walk
 * is a real, if modest, disk and CPU cost an operator may not want paid on every node running
 * Andvari. Runs on its own virtual thread, the same lightweight-background-work idiom {@code
 * gimle-muninn}'s own {@code RetentionSweeper} scheduler already establishes.
 *
 * <p>Deliberately independent of {@link AndvariServer}, not owned by it -- the same relationship
 * {@code RetentionSweeper} has to {@code MuninnServer} -- and reports through {@link
 * FindingHandler} rather than reimplementing the log/quarantine/audit handling {@code
 * AndvariServer#reportIntegrityFailure} already does for {@code handleDownload}'s own re-check.
 */
final class IntegrityScrubber implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(IntegrityScrubber.class);

  /**
   * What to do with a coordinate whose on-disk bytes no longer match {@link StoredArtifact#sha256}.
   * {@link AndvariServer#reportIntegrityFailure} is the one implementation this process actually
   * wires up.
   */
  @FunctionalInterface
  interface FindingHandler {
    void onMismatch(String moduleId, String version, String expectedSha256, String actualSha256);
  }

  private final ArtifactStore artifactStore;
  private final FindingHandler findingHandler;
  private final ScheduledExecutorService scheduler;

  IntegrityScrubber(
      ArtifactStore artifactStore, FindingHandler findingHandler, Duration scrubInterval) {
    this.artifactStore = artifactStore;
    this.findingHandler = findingHandler;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-andvari-integrity-scrub").unstarted(r));
    scheduler.scheduleAtFixedRate(
        this::scrubQuietly, 0, scrubInterval.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void scrubQuietly() {
    try {
      scrub();
    } catch (RuntimeException e) {
      log.warn("integrity scrub failed: {}", e.getMessage());
    }
  }

  /**
   * Package-visible for direct testing without waiting on the scheduler's own interval. Returns the
   * number of coordinates checked.
   */
  int scrub() {
    int checked = 0;
    int corrupted = 0;
    for (String moduleId : artifactStore.moduleIds()) {
      for (StoredArtifact stored : artifactStore.versions(moduleId)) {
        checked++;
        Optional<Path> artifactFile = artifactStore.artifactFilePath(moduleId, stored.version());
        if (artifactFile.isEmpty()) {
          // meta.json survived without its artifact file (e.g. a prior quarantine interrupted
          // mid-move) -- nothing left here to digest; leave it for an operator to notice via a
          // failed GET rather than folding a second failure shape into this pass's own finding
          // handling.
          log.warn(
              "integrity scrub found {}:{} has metadata but no artifact file on disk",
              moduleId,
              stored.version());
          continue;
        }
        String actualSha256;
        try {
          actualSha256 =
              ArtifactStore.copyAndDigest(artifactFile.get(), OutputStream.nullOutputStream());
        } catch (IOException e) {
          log.warn(
              "failed to read {}:{} during integrity scrub: {}",
              moduleId,
              stored.version(),
              e.getMessage());
          continue;
        }
        if (!actualSha256.equals(stored.sha256())) {
          findingHandler.onMismatch(moduleId, stored.version(), stored.sha256(), actualSha256);
          corrupted++;
        }
      }
    }
    if (corrupted > 0) {
      log.warn("integrity scrub checked {} artifact(s), found {} corrupted", checked, corrupted);
    } else {
      log.debug("integrity scrub checked {} artifact(s), no corruption found", checked);
    }
    return checked;
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }
}
