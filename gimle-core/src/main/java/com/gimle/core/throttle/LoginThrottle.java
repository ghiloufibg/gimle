package com.gimle.core.throttle;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, per-key exponential backoff for repeated failures against a key of the caller's own
 * choosing. {@code gimle-controlplane}'s {@code ApiServer} tracks one key per username and a second
 * per remote address for login attempts, so a locked-out username doesn't block a legitimate caller
 * from a different IP, and a single IP hammering many usernames still gets throttled even though no
 * individual username crossed its own threshold. {@code gimle-fafnir} constructs its own separate
 * instance keyed by calling principal/node identity, incrementing on an authorization failure
 * rather than a login failure -- the class itself is generic over what {@code key} means, so no
 * changes are needed to support a second, independent caller.
 *
 * <p>Deliberately in-memory and per-replica, not a {@code StateMutation} replicated through the
 * store: every process using this is already multi-replica and stateless by design, and a
 * failed-attempt write would put attacker-controlled traffic straight into the Raft log -- worse
 * than the gap this closes. A distributed attacker can still spread attempts across replicas, but
 * each replica throttling independently still bounds the rate any single one will answer, and is a
 * strict improvement over no throttling at all.
 */
public final class LoginThrottle {

  private static final int DEFAULT_FAILURE_THRESHOLD = 3;
  private static final Duration DEFAULT_BASE_DELAY = Duration.ofSeconds(1);
  private static final Duration DEFAULT_MAX_DELAY = Duration.ofMinutes(5);

  /** Caps the exponent so backoff growth can't overflow a {@link Duration} multiply. */
  private static final int MAX_BACKOFF_EXPONENT = 20;

  private final int failureThreshold;
  private final Duration baseDelay;
  private final Duration maxDelay;
  private final Map<String, Attempts> byKey = new ConcurrentHashMap<>();

  public LoginThrottle() {
    this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_BASE_DELAY, DEFAULT_MAX_DELAY);
  }

  public LoginThrottle(int failureThreshold, Duration baseDelay, Duration maxDelay) {
    if (failureThreshold < 1) {
      throw new IllegalArgumentException("failureThreshold must be at least 1");
    }
    if (baseDelay == null || baseDelay.isNegative() || baseDelay.isZero()) {
      throw new IllegalArgumentException("baseDelay must be positive");
    }
    if (maxDelay == null || maxDelay.compareTo(baseDelay) < 0) {
      throw new IllegalArgumentException("maxDelay must be at least baseDelay");
    }
    this.failureThreshold = failureThreshold;
    this.baseDelay = baseDelay;
    this.maxDelay = maxDelay;
  }

  /**
   * Present (naming the instant a fresh attempt becomes allowed) while {@code key} is currently
   * throttled; empty otherwise, including for a key that's never failed at all.
   */
  public Optional<Instant> throttledUntil(String key) {
    Attempts attempts = byKey.get(key);
    if (attempts == null) {
      return Optional.empty();
    }
    Instant now = Instant.now();
    return now.isBefore(attempts.nextAllowedAttempt)
        ? Optional.of(attempts.nextAllowedAttempt)
        : Optional.empty();
  }

  /**
   * Records one more consecutive failure for {@code key}. The first {@code failureThreshold - 1}
   * failures record but impose no delay (an honest typo shouldn't lock anyone out); from the
   * threshold-th failure on, each additional failure doubles the delay, capped at {@code maxDelay}.
   */
  public void recordFailure(String key) {
    byKey.compute(
        key,
        (unused, existing) -> {
          int failures = (existing == null ? 0 : existing.failures) + 1;
          return new Attempts(failures, Instant.now().plus(backoffFor(failures)));
        });
  }

  /**
   * Clears {@code key}'s failure history -- a genuine success should not still be paying for it.
   */
  public void recordSuccess(String key) {
    byKey.remove(key);
  }

  private Duration backoffFor(int failures) {
    if (failures < failureThreshold) {
      return Duration.ZERO;
    }
    int exponent = Math.min(failures - failureThreshold, MAX_BACKOFF_EXPONENT);
    Duration delay = baseDelay.multipliedBy(1L << exponent);
    return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
  }

  private record Attempts(int failures, Instant nextAllowedAttempt) {}
}
