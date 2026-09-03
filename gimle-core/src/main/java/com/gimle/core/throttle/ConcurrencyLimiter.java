package com.gimle.core.throttle;

import java.util.concurrent.Semaphore;

/**
 * Non-blocking admission gate bounding how many callers may be doing a piece of work at once,
 * regardless of how quickly or slowly any one of them runs. The sibling of {@link
 * RequestRateLimiter} and deliberately not the same thing: that one spends a budget over time and
 * hands back a retry instant, which bounds *acceptance rate* but nothing about how many already-
 * accepted callers may be in flight simultaneously -- a flood large enough to out-run acceptance-
 * time throttling (or aimed at a route with none at all) still piles up as raw concurrency until
 * whatever it contends for falls over. This limiter only ever tracks concurrency: {@link
 * #tryAcquire()} either hands back a permit immediately or refuses immediately, with no queueing in
 * between -- queueing the excess in front of an already-saturated resource just moves where the
 * pileup happens and hides it from the caller for longer, the opposite of a fast, honest rejection.
 */
public final class ConcurrencyLimiter {

  private final Semaphore permits;
  private final int maxConcurrent;

  public ConcurrencyLimiter(final int maxConcurrent) {
    if (maxConcurrent < 1) {
      throw new IllegalArgumentException("maxConcurrent must be at least 1");
    }
    this.maxConcurrent = maxConcurrent;
    // Unfair: under sustained saturation this favors overall throughput over admission order,
    // which fits an admission gate whose rejected callers are expected to simply retry rather than
    // queue for their turn.
    this.permits = new Semaphore(maxConcurrent, false);
  }

  /** Claims one slot if one is free, returning immediately either way -- never blocks. */
  public boolean tryAcquire() {
    return permits.tryAcquire();
  }

  /** Releases one slot previously claimed via a successful {@link #tryAcquire()}. */
  public void release() {
    permits.release();
  }

  /**
   * Slots currently claimed and not yet released -- an observability/test hook, not itself used.
   */
  public int inFlight() {
    return maxConcurrent - permits.availablePermits();
  }
}
