package com.gimle.muninn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically deletes {@link MuninnDayFileStore} day files older than a configurable retention
 * window -- the one genuinely new capability relative to {@code AgentLogServer}'s own local-disk
 * serving, which never needed one: a single node's own log files already self-bound via {@code
 * LogFileReader.configuredMaxFiles()}'s rotation-count cap, but a central aggregator accumulating
 * from every node and process in the cluster needs an explicit, age-based bound instead. Runs on
 * its own virtual thread ({@code Thread.ofVirtual()}), the same lightweight-background-work idiom
 * {@code AgentMain}'s own instance-starter thread already uses.
 */
final class RetentionSweeper implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(RetentionSweeper.class);
  private static final Pattern DAY_FILE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\.log");

  private final Path dataRoot;
  private final int retentionDays;
  private final ScheduledExecutorService scheduler;

  RetentionSweeper(Path dataRoot, int retentionDays, Duration sweepInterval) {
    this.dataRoot = dataRoot;
    this.retentionDays = retentionDays;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-muninn-retention-sweep").unstarted(r));
    scheduler.scheduleAtFixedRate(
        this::sweepQuietly, 0, sweepInterval.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void sweepQuietly() {
    try {
      sweep();
    } catch (IOException | RuntimeException e) {
      log.warn("retention sweep failed: {}", e.getMessage());
    }
  }

  /** Package-visible for direct testing without waiting on the scheduler's own interval. */
  void sweep() throws IOException {
    if (!Files.isDirectory(dataRoot)) {
      return;
    }
    LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(retentionDays);
    try (var paths = Files.walk(dataRoot)) {
      for (Path file : paths.filter(Files::isRegularFile).toList()) {
        var fileName = file.getFileName();
        if (fileName == null) {
          continue;
        }
        Matcher matcher = DAY_FILE.matcher(fileName.toString());
        if (!matcher.matches()) {
          continue;
        }
        LocalDate day = LocalDate.parse(matcher.group(1));
        if (day.isBefore(cutoff)) {
          // Files.deleteIfExists rather than delete: a concurrent sweep-and-retry, or the file
          // already having been cleaned up by an earlier pass, is a no-op here, not an error.
          Files.deleteIfExists(file);
          log.debug("retention sweep deleted {}", file);
        }
      }
    }
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }
}
