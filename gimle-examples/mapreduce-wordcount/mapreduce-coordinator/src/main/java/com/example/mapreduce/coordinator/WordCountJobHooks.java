package com.example.mapreduce.coordinator;

import com.example.mapreduce.MapReduceWorker;
import com.gimle.core.exception.GimleClusterException;
import com.gimle.module.lifecycle.CompletionStatus;
import com.gimle.module.lifecycle.JobHooks;
import com.gimle.module.lifecycle.ModuleContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The reduce phase, and the fan-out that drives the map phase: this Job-kind module's whole {@link
 * #run} is one distributed map-reduce computation over a corpus sized to look like a genuine
 * batch-processing workload (hundreds of chunks, hundreds of thousands of lines by default -- see
 * the config table in this class and README.md), not a toy handful of records.
 *
 * <p>Each chunk's {@code mapChunk} call re-resolves mapreduce-worker via {@link
 * ModuleContext#lookupService} immediately before making it -- never once up front and reused
 * across every chunk -- so the fabric's own load balancer picks a (potentially different) live
 * replica per chunk, the same "re-resolve every time" posture gimle-examples/greeter-consumer
 * already establishes for a different reason (never getting stuck on a redeployed provider's stale
 * endpoint). Here it's also the mechanism that actually spreads {@code chunkCount} chunks of real
 * work across every mapreduce-worker replica currently registered, rather than pinning them all to
 * whichever one instance happened to answer first.
 *
 * <p>Every chunk's map call runs on its own virtual thread, bounded by a {@link Semaphore} to at
 * most {@code mapreduce.maxInFlight} concurrent outstanding calls -- at enterprise-workload chunk
 * counts, submitting all of them at once would open thousands of simultaneous fabric round trips
 * against a fixed handful of worker replicas: cheap for the virtual threads themselves, but exactly
 * the kind of unbounded fan-out a real production caller never sends at a downstream service it
 * doesn't own. Bounding it here is the same "a caller controls its own concurrency" discipline a
 * real batch pipeline applies against any shared backend.
 *
 * <p>Not every chunk has to succeed for the job as a whole to: {@code mapreduce.maxFailureRatio}
 * tolerates a bounded fraction of chunk failures (a replica that crashed mid-run, a transient
 * lookup miss that outlasted its retries) without failing the entire multi-hour-scale batch over a
 * handful of stragglers -- the same production posture a real large-scale batch job takes, as
 * opposed to "any single failure aborts everything."
 */
public final class WordCountJobHooks implements JobHooks {

  private static final Logger log = LoggerFactory.getLogger(WordCountJobHooks.class);

  // Defaults sized to look like a genuine batch workload out of the box, with no tenant config
  // required: 200 chunks x 2,000 lines = 400,000 lines, roughly 4 million word occurrences at this
  // corpus's own average line length -- see SyntheticCorpus. Every one of these is overridable via
  // tenant config (see README.md's "Config" table) for pushing the load further still.
  private static final int DEFAULT_CHUNK_COUNT = 200;
  private static final int DEFAULT_LINES_PER_CHUNK = 2_000;
  private static final int DEFAULT_MAX_IN_FLIGHT = 32;
  private static final double DEFAULT_MAX_FAILURE_RATIO = 0.05;

  private static final int TOP_WORDS_REPORTED = 20;
  private static final int MAX_LOOKUP_ATTEMPTS = 5;
  private static final Duration LOOKUP_RETRY_DELAY = Duration.ofSeconds(2);

  @Override
  public CompletionStatus run(ModuleContext ctx) {
    int chunkCount = intConfig(ctx, "mapreduce.chunks", DEFAULT_CHUNK_COUNT);
    int linesPerChunk = intConfig(ctx, "mapreduce.linesPerChunk", DEFAULT_LINES_PER_CHUNK);
    int maxInFlight = intConfig(ctx, "mapreduce.maxInFlight", DEFAULT_MAX_IN_FLIGHT);
    double maxFailureRatio =
        doubleConfig(ctx, "mapreduce.maxFailureRatio", DEFAULT_MAX_FAILURE_RATIO);

    List<List<String>> chunks = SyntheticCorpus.generate(chunkCount, linesPerChunk);
    long totalLines = (long) chunkCount * linesPerChunk;

    log.info(
        "mapreduce-coordinator dispatching {} chunks ({} lines each, {} lines total) across the"
            + " fabric, at most {} in flight at once",
        chunkCount,
        linesPerChunk,
        totalLines,
        maxInFlight);

    Instant start = Instant.now();
    Map<String, Long> reduced = new ConcurrentHashMap<>();
    AtomicInteger failedChunks = new AtomicInteger();
    Semaphore inFlight = new Semaphore(maxInFlight);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Optional<Map<String, Long>>>> futures = new ArrayList<>(chunks.size());
      for (List<String> chunk : chunks) {
        futures.add(executor.submit(() -> mapChunkBounded(ctx, chunk, inFlight)));
      }
      for (Future<Optional<Map<String, Long>>> future : futures) {
        Optional<Map<String, Long>> partial = awaitFuture(future);
        if (partial.isPresent()) {
          partial.get().forEach((word, count) -> reduced.merge(word, count, Long::sum));
        } else {
          failedChunks.incrementAndGet();
        }
      }
    }

    Duration elapsed = Duration.between(start, Instant.now());
    double failureRatio = failedChunks.get() / (double) chunkCount;

    if (reduced.isEmpty()) {
      log.error(
          "mapreduce-coordinator produced no result: all {} chunks failed (no mapreduce-worker"
              + " replica ever answered)",
          chunkCount);
      return CompletionStatus.FAILED;
    }

    logReport(reduced, chunkCount, failedChunks.get(), totalLines, elapsed);

    if (failureRatio > maxFailureRatio) {
      log.error(
          "mapreduce-coordinator failing the job: {} of {} chunks failed ({}%), over the"
              + " configured tolerance of {}%",
          failedChunks.get(), chunkCount, pct(failureRatio), pct(maxFailureRatio));
      return CompletionStatus.FAILED;
    }
    if (failedChunks.get() > 0) {
      log.warn(
          "mapreduce-coordinator succeeded with a degraded result: {} of {} chunks failed ({}%),"
              + " within the configured tolerance of {}%",
          failedChunks.get(), chunkCount, pct(failureRatio), pct(maxFailureRatio));
    }
    return CompletionStatus.SUCCEEDED;
  }

  /** Acquires {@code inFlight} before dispatching, and always releases it afterward. */
  private Optional<Map<String, Long>> mapChunkBounded(
      ModuleContext ctx, List<String> chunk, Semaphore inFlight) {
    try {
      inFlight.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
    try {
      return mapChunk(ctx, chunk);
    } finally {
      inFlight.release();
    }
  }

  /**
   * Retries a fresh {@code lookupService} + {@code mapChunk} call up to {@link
   * #MAX_LOOKUP_ATTEMPTS} times, {@link #LOOKUP_RETRY_DELAY} apart, for this one chunk -- the same
   * "raced a fresh cluster boot" tolerance gimle-examples/orders-platform's own
   * OrdersReportJobHooks already establishes, extended here to also retry a call that resolved a
   * replica but then failed mid-call (redeployed/restarted since lookup), rather than only an
   * empty lookup. An empty {@link Optional} here excludes just this one chunk from the reduced
   * result rather than failing the whole job outright -- {@link #run}'s own failure-ratio check is
   * what decides whether that many exclusions are still within tolerance.
   */
  private Optional<Map<String, Long>> mapChunk(ModuleContext ctx, List<String> chunk) {
    for (int attempt = 1; attempt <= MAX_LOOKUP_ATTEMPTS; attempt++) {
      Optional<MapReduceWorker> worker;
      try {
        worker = ctx.lookupService(MapReduceWorker.class);
      } catch (GimleClusterException e) {
        // Nobody in the cluster has exported MapReduceWorker yet.
        worker = Optional.empty();
      }
      if (worker.isPresent()) {
        try {
          return Optional.of(worker.get().mapChunk(chunk));
        } catch (RuntimeException e) {
          log.warn("mapChunk call failed, retrying against a fresh lookup: {}", e.getMessage());
        }
      }
      if (attempt < MAX_LOOKUP_ATTEMPTS) {
        sleep(LOOKUP_RETRY_DELAY);
      }
    }
    log.warn(
        "giving up on one chunk after {} attempts; excluding it from the reduced result",
        MAX_LOOKUP_ATTEMPTS);
    return Optional.empty();
  }

  private void logReport(
      Map<String, Long> reduced, int chunkCount, int failedChunks, long totalLines,
      Duration elapsed) {
    long totalOccurrences = reduced.values().stream().mapToLong(Long::longValue).sum();
    List<Map.Entry<String, Long>> topWords =
        reduced.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(TOP_WORDS_REPORTED)
            .toList();

    double elapsedSeconds = Math.max(elapsed.toMillis(), 1) / 1000.0;
    long linesPerSecond = Math.round(totalLines / elapsedSeconds);
    long wordsPerSecond = Math.round(totalOccurrences / elapsedSeconds);

    StringBuilder report = new StringBuilder();
    report
        .append("mapreduce-coordinator reduced ")
        .append(chunkCount)
        .append(" chunks (")
        .append(failedChunks)
        .append(" failed) into ")
        .append(reduced.size())
        .append(" distinct words, ")
        .append(totalOccurrences)
        .append(" total occurrences from ")
        .append(totalLines)
        .append(" lines, in ")
        .append(elapsed.toMillis())
        .append("ms (")
        .append(linesPerSecond)
        .append(" lines/s, ")
        .append(wordsPerSecond)
        .append(" words/s). Top ")
        .append(topWords.size())
        .append(" words: ");
    for (int i = 0; i < topWords.size(); i++) {
      if (i > 0) {
        report.append(", ");
      }
      Map.Entry<String, Long> entry = topWords.get(i);
      report.append(entry.getKey()).append('=').append(entry.getValue());
    }
    log.info("{}", report);
  }

  private static Optional<Map<String, Long>> awaitFuture(
      Future<Optional<Map<String, Long>>> future) {
    try {
      return future.get();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      log.warn("a chunk's map task threw unexpectedly: {}", cause.getMessage());
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  private static int intConfig(ModuleContext ctx, String key, int defaultValue) {
    return ctx.config(key).map(String::strip).map(Integer::parseInt).orElse(defaultValue);
  }

  private static double doubleConfig(ModuleContext ctx, String key, double defaultValue) {
    return ctx.config(key).map(String::strip).map(Double::parseDouble).orElse(defaultValue);
  }

  private static long pct(double ratio) {
    return Math.round(ratio * 100);
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
