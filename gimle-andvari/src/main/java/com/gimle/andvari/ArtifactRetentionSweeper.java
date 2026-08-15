package com.gimle.andvari;

import com.gimle.andvari.ArtifactStore.StoredArtifact;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically retires old versions of a module once either configured policy is exceeded: more
 * than {@code maxVersionsPerModule} pushes retained, or a push older than {@code maxAge}. Every jar
 * in this store is immutable and never overwritten (see {@link ArtifactStore}'s own javadoc), so
 * without this a store only ever grows -- an operator who wants "keep the last 10 releases" or
 * "nothing older than 90 days" needs an explicit sweep, not a side effect of push/delete. Off by
 * default (see {@code AndvariMain}'s own {@code -Dgimle.andvari.retention.enabled} handling), since
 * retiring a version is a real, irreversible storage decision an operator must opt into, not a
 * default this process should make silently. Runs on its own virtual thread, the same
 * lightweight-background-work idiom {@code gimle-muninn}'s own {@code RetentionSweeper} and this
 * module's own {@link IntegrityScrubber} already establish.
 *
 * <p>Deliberately independent of {@link AndvariServer}, not owned by it -- the same relationship
 * {@link IntegrityScrubber} has to it -- and reports each retirement through {@link
 * RetiredVersionHandler} rather than reimplementing the delete/audit handling {@code
 * AndvariServer#reportRetentionRetirement} already does.
 */
final class ArtifactRetentionSweeper implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ArtifactRetentionSweeper.class);

  /**
   * What to do with a version this sweep decided to retire. {@link
   * AndvariServer#reportRetentionRetirement} is the one implementation this process actually wires
   * up -- it performs the actual {@link ArtifactStore#delete} and the dual audit, this sweeper only
   * decides which coordinates qualify.
   */
  @FunctionalInterface
  interface RetiredVersionHandler {
    void onRetire(String moduleId, String version, String reason);
  }

  private final ArtifactStore artifactStore;
  private final RetiredVersionHandler retiredVersionHandler;
  private final int maxVersionsPerModule;
  private final Duration maxAge;
  private final ScheduledExecutorService scheduler;

  /**
   * @param maxVersionsPerModule keep at most this many of a module's most-recently-pushed versions;
   *     {@code <= 0} disables the count-based policy entirely.
   * @param maxAge retire a version once it's older than this since it was pushed; {@code null}
   *     disables the age-based policy entirely.
   */
  ArtifactRetentionSweeper(
      ArtifactStore artifactStore,
      RetiredVersionHandler retiredVersionHandler,
      int maxVersionsPerModule,
      Duration maxAge,
      Duration sweepInterval) {
    this.artifactStore = artifactStore;
    this.retiredVersionHandler = retiredVersionHandler;
    this.maxVersionsPerModule = maxVersionsPerModule;
    this.maxAge = maxAge;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-andvari-retention-sweep").unstarted(r));
    scheduler.scheduleAtFixedRate(
        this::sweepQuietly, 0, sweepInterval.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void sweepQuietly() {
    try {
      sweep();
    } catch (RuntimeException e) {
      log.warn("retention sweep failed: {}", e.getMessage());
    }
  }

  /**
   * Package-visible for direct testing without waiting on the scheduler's own interval. Returns the
   * number of versions retired. Orders each module's versions by {@link
   * StoredArtifact#pushedAtEpochMilli} rather than {@link ArtifactStore#versions}'s own semver
   * order -- "the last 10 releases" means the 10 most recently pushed, not the 10 highest-numbered,
   * and the two can disagree (e.g. a hotfix backported to an old branch after a newer version
   * already exists).
   */
  int sweep() {
    int retired = 0;
    long ageCutoffMillis =
        maxAge != null ? System.currentTimeMillis() - maxAge.toMillis() : Long.MIN_VALUE;
    for (String moduleId : artifactStore.moduleIds()) {
      List<StoredArtifact> versions = new ArrayList<>(artifactStore.versions(moduleId));
      versions.sort(Comparator.comparingLong(StoredArtifact::pushedAtEpochMilli));
      int overCountBy =
          maxVersionsPerModule > 0 ? Math.max(0, versions.size() - maxVersionsPerModule) : 0;
      for (int i = 0; i < versions.size(); i++) {
        StoredArtifact stored = versions.get(i);
        boolean overCount = i < overCountBy;
        boolean overAge = maxAge != null && stored.pushedAtEpochMilli() < ageCutoffMillis;
        if (!overCount && !overAge) {
          continue;
        }
        String reason = overCount && overAge ? "count+age" : overCount ? "count" : "age";
        retiredVersionHandler.onRetire(moduleId, stored.version(), reason);
        retired++;
      }
    }
    if (retired > 0) {
      log.info("retention sweep retired {} version(s)", retired);
    } else {
      log.debug("retention sweep found nothing to retire");
    }
    return retired;
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }
}
