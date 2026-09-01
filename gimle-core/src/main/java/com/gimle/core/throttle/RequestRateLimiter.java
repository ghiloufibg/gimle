package com.gimle.core.throttle;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, per-key token bucket bounding how often a key may be served at all, whatever the
 * outcome of the request. The sibling of {@link LoginThrottle}, and deliberately not the same
 * thing: {@code LoginThrottle} backs off a key that keeps *failing*, which does nothing for an
 * endpoint whose expensive requests all succeed. This one charges every admitted request a token,
 * so a caller flooding an endpoint with perfectly well-formed work is bounded too.
 *
 * <p>A bucket starts full at {@code burstCapacity} and regains one token every {@code
 * refillInterval}, so a caller may spend a whole burst at once (a real fleet all arriving in the
 * same second) and then continues at the steady rate. Callers typically hold two instances -- one
 * keyed by remote address, one on a single fixed key -- so that no single source can spend the
 * shared budget and no distributed set of sources can bypass a per-source limit.
 *
 * <p>Deliberately in-memory and per-replica, not replicated through the store, for exactly the
 * reasons {@link LoginThrottle} documents for itself: recording attacker-controlled traffic in the
 * Raft log would be worse than the gap it closes, and a per-replica bound is still a real bound.
 */
public final class RequestRateLimiter {

  /**
   * Above this many tracked keys, an admission also sweeps every bucket that has refilled
   * completely -- such a bucket is indistinguishable from a key that was never seen, so dropping it
   * loses nothing. Bounds memory against a caller that arrives from a large number of distinct
   * addresses.
   */
  private static final int SWEEP_THRESHOLD = 10_000;

  private final int burstCapacity;
  private final long refillIntervalNanos;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  public RequestRateLimiter(final int burstCapacity, final Duration refillInterval) {
    if (burstCapacity < 1) {
      throw new IllegalArgumentException("burstCapacity must be at least 1");
    }
    if (refillInterval == null || refillInterval.isNegative() || refillInterval.isZero()) {
      throw new IllegalArgumentException("refillInterval must be positive");
    }
    this.burstCapacity = burstCapacity;
    this.refillIntervalNanos = refillInterval.toNanos();
  }

  /**
   * Charges {@code key} one token, returning empty when the request is admitted and, when it is
   * not, the instant at which a token next becomes available -- the shape {@link
   * LoginThrottle#throttledUntil} already returns, so a caller responds to both the same way. A
   * rejected request costs the key nothing extra: retrying early is refused again rather than
   * pushing the wait further out.
   */
  public Optional<Instant> acquire(final String key) {
    final Instant now = Instant.now();
    sweepIfCrowded(now);
    final Instant[] retryAt = new Instant[1];
    buckets.compute(
        key,
        (unused, existing) -> {
          final double tokens = existing == null ? burstCapacity : tokensAt(existing, now);
          if (tokens >= 1.0) {
            return new Bucket(tokens - 1.0, now);
          }
          retryAt[0] = now.plusNanos((long) Math.ceil((1.0 - tokens) * refillIntervalNanos));
          return new Bucket(tokens, now);
        });
    return Optional.ofNullable(retryAt[0]);
  }

  private double tokensAt(final Bucket bucket, final Instant now) {
    final long elapsedNanos = Duration.between(bucket.updatedAt(), now).toNanos();
    if (elapsedNanos <= 0) {
      return bucket.tokens();
    }
    return Math.min(burstCapacity, bucket.tokens() + (double) elapsedNanos / refillIntervalNanos);
  }

  private void sweepIfCrowded(final Instant now) {
    if (buckets.size() <= SWEEP_THRESHOLD) {
      return;
    }
    buckets.values().removeIf(bucket -> tokensAt(bucket, now) >= burstCapacity);
  }

  private record Bucket(double tokens, Instant updatedAt) {}
}
