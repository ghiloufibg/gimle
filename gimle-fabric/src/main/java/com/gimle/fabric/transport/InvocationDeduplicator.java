package com.gimle.fabric.transport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Listener-side duplicate suppression keyed by a request's own {@code correlationId}: the first
 * request carrying a given id runs the real dispatch and its answer is remembered; a second request
 * carrying the same id within {@link #window} gets that same answer back instead of the target
 * method being invoked twice.
 *
 * <p>This is what makes a client-side retry meaningful rather than merely hopeful. A caller that
 * lost the answer to a request it had already written retries with the identical correlation id, so
 * if the target did execute the first attempt the retry observes that first outcome; only if the
 * target genuinely never ran it does the retry execute anything. Nothing else on this wire carries
 * enough information to tell those two cases apart.
 *
 * <p>A duplicate that arrives while the original is still running waits for it rather than racing
 * it -- the retry is by definition a caller that already gave up waiting, and its own deadline
 * bounds the wait from that side. Bounded two ways so a long-lived listener can't grow without
 * limit: entries older than {@link #window} stop being honoured, and the table is capped at {@link
 * #maxEntries}, evicting oldest-first once it fills. An id whose dispatch produced no response
 * frame at all (the supplier itself threw) is dropped rather than remembered, since there is
 * nothing to replay.
 */
final class InvocationDeduplicator {

  /**
   * Comfortably longer than a client's whole retry sequence ({@link FabricClient#DEFAULT_TIMEOUT}
   * per attempt, a small bounded number of attempts), so a retry always lands inside the window of
   * the attempt it is retrying. Longer than that buys nothing: no caller is still retrying by then.
   */
  static final Duration DEFAULT_WINDOW = Duration.ofSeconds(60);

  /**
   * Cap on remembered correlation ids. Each entry is one long plus one response frame, and only
   * in-window ids are ever consulted, so this bounds the table's memory rather than expressing any
   * traffic expectation.
   */
  static final int DEFAULT_MAX_ENTRIES = 4096;

  private record Entry(CompletableFuture<FabricFrame> response, Instant recordedAt) {}

  private final Duration window;
  private final int maxEntries;
  private final Clock clock;

  // Insertion-ordered so the crowded-table sweep can evict genuinely oldest-first rather than
  // arbitrarily. Guarded by lock -- held only for table bookkeeping, never across a dispatch.
  private final Map<Long, Entry> entries = new LinkedHashMap<>();
  private final Object lock = new Object();

  InvocationDeduplicator() {
    this(DEFAULT_WINDOW, DEFAULT_MAX_ENTRIES, Clock.systemUTC());
  }

  InvocationDeduplicator(Duration window, int maxEntries, Clock clock) {
    if (maxEntries < 1) {
      throw new IllegalArgumentException("maxEntries must be at least 1: " + maxEntries);
    }
    this.window = window;
    this.maxEntries = maxEntries;
    this.clock = clock;
  }

  /**
   * Runs {@code dispatch} for a correlation id not seen inside the current window, or replays the
   * answer already recorded for one that was.
   */
  FabricFrame dispatchOnce(long correlationId, Supplier<FabricFrame> dispatch) {
    final Entry existing;
    final Entry claimed;
    synchronized (lock) {
      Instant now = clock.instant();
      sweepIfCrowded(now);
      Entry current = entries.get(correlationId);
      if (current != null && current.recordedAt().plus(window).isAfter(now)) {
        existing = current;
        claimed = null;
      } else {
        existing = null;
        claimed = new Entry(new CompletableFuture<>(), now);
        entries.put(correlationId, claimed);
      }
    }
    return existing != null
        ? replay(correlationId, existing, dispatch)
        : runAndRemember(correlationId, claimed, dispatch);
  }

  private FabricFrame replay(long correlationId, Entry existing, Supplier<FabricFrame> dispatch) {
    try {
      return existing.response().join();
    } catch (CompletionException | CancellationException e) {
      // The remembered attempt never produced a response frame, so there is nothing to replay --
      // let this request run for real instead of inheriting a failure the caller never saw.
      synchronized (lock) {
        entries.remove(correlationId, existing);
      }
      return dispatch.get();
    }
  }

  private FabricFrame runAndRemember(
      long correlationId, Entry claimed, Supplier<FabricFrame> dispatch) {
    try {
      FabricFrame response = dispatch.get();
      claimed.response().complete(response);
      return response;
    } catch (RuntimeException e) {
      synchronized (lock) {
        entries.remove(correlationId, claimed);
      }
      claimed.response().completeExceptionally(e);
      throw e;
    }
  }

  /**
   * Expiry is enforced at read time in {@link #dispatchOnce}, so this exists purely to keep the
   * table's memory bounded: it runs only once the table is actually full, dropping everything past
   * the window first and then the oldest surviving entries until there is room again.
   */
  private void sweepIfCrowded(Instant now) {
    if (entries.size() < maxEntries) {
      return;
    }
    entries.values().removeIf(entry -> !entry.recordedAt().plus(window).isAfter(now));
    Iterator<Map.Entry<Long, Entry>> oldestFirst = entries.entrySet().iterator();
    while (entries.size() >= maxEntries && oldestFirst.hasNext()) {
      oldestFirst.next();
      oldestFirst.remove();
    }
  }
}
