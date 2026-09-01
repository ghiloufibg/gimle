package com.gimle.skald.directory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The in-memory cache {@link ControlPlaneServicePoller} refreshes and {@link
 * com.gimle.skald.SkaldServer} reads on every query. A single {@code volatile} map swap on refresh
 * means a query never blocks on (or observes a half-updated view of) a poll in progress. No
 * per-name rotation state: every answer carries the full endpoint set (see {@link
 * ServiceDirectory#resolveAll}), so there is nothing to rotate.
 *
 * <p>Also tracks the two pieces of state that turn "a poll merely ran" into "the data is still
 * trustworthy": when a poll last actually replaced this cache's contents ({@link #replaceAll}, only
 * ever called on success), and how many polls have failed in a row since ({@link
 * #recordPollFailure}, reset by the next success). Both are read by {@link
 * com.gimle.skald.SkaldServer} to decide whether to keep answering confidently from data that may
 * no longer reflect reality.
 */
public final class CachingServiceDirectory implements ServiceDirectory {

  private final Clock clock;

  /**
   * Baseline for {@link #timeSinceLastSuccess()} before the very first successful poll -- treating
   * "never yet refreshed" as staleness accruing from construction, rather than a special-cased
   * infinite/absent value, keeps every caller's arithmetic uniform (a freshly started, still-empty
   * directory is maximally stale, not exempt from the staleness check).
   */
  private final Instant startedAt;

  private volatile Map<String, List<HostPort>> endpointsByName = Map.of();
  private volatile Instant lastSuccessAt;
  private final AtomicInteger consecutiveFailures = new AtomicInteger();

  public CachingServiceDirectory() {
    this(Clock.systemUTC());
  }

  public CachingServiceDirectory(Clock clock) {
    this.clock = clock;
    this.startedAt = clock.instant();
  }

  /**
   * Replaces the entire cache with {@code next} (qualified service name to its live endpoints).
   * Called once per successful poll cycle, never merged incrementally -- the control plane's own
   * listing is always treated as the complete, current set of services. Marks this instant as the
   * last successful refresh and clears the consecutive-failure count.
   *
   * <p>A name mapped to an empty list is a first-class entry, not a no-op: it records a Service
   * that genuinely exists with no live endpoints right now, which resolves differently from a name
   * absent from the map altogether (see {@link #resolveAll}).
   */
  public void replaceAll(Map<String, List<HostPort>> next) {
    this.endpointsByName = Map.copyOf(next);
    this.lastSuccessAt = clock.instant();
    this.consecutiveFailures.set(0);
  }

  /**
   * Records that a poll attempt failed without touching the cached data at all -- the pre-existing,
   * still-correct posture of leaving stale-but-possibly-still-right data in place rather than
   * flipping every cached name to NXDOMAIN over one bad poll. Only the staleness bookkeeping moves.
   */
  public void recordPollFailure() {
    consecutiveFailures.incrementAndGet();
  }

  @Override
  public Optional<List<HostPort>> resolveAll(String qualifiedServiceName) {
    // One map read, not a contains-then-get pair: the map reference is swapped wholesale by a
    // poll, so two reads could straddle a refresh and report a name as known with no endpoints
    // that the very same refresh had actually removed.
    return Optional.ofNullable(endpointsByName.get(qualifiedServiceName));
  }

  @Override
  public Duration timeSinceLastSuccess() {
    Instant reference = lastSuccessAt != null ? lastSuccessAt : startedAt;
    Duration elapsed = Duration.between(reference, clock.instant());
    // A test clock or an out-of-order call could in principle yield a negative gap; treat that as
    // "as fresh as it gets" rather than surfacing a nonsensical negative staleness.
    return elapsed.isNegative() ? Duration.ZERO : elapsed;
  }

  @Override
  public int consecutiveFailures() {
    return consecutiveFailures.get();
  }
}
