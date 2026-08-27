package com.gimle.ragnarok.surtr;

import com.gimle.ragnarok.RagnarokException;

/**
 * A simple token-bucket rate limiter pacing submissions to a target QPS with an initial burst.
 * Without it a scale test measures the HTTP accept queue rather than the scheduler. Single-threaded
 * by design -- Surtr submits serially, so no synchronization is needed.
 */
final class TokenBucket {

  private final double refillPerNano;
  private final double capacity;
  private double tokens;
  private long lastNanos;

  TokenBucket(final double qps, final int burst) {
    this.refillPerNano = qps / 1_000_000_000.0;
    this.capacity = burst;
    this.tokens = burst;
    this.lastNanos = System.nanoTime();
  }

  /** Blocks until a token is available, then consumes it. */
  void acquire() {
    refill();
    while (tokens < 1.0) {
      final long waitNanos = (long) Math.ceil((1.0 - tokens) / refillPerNano);
      sleepNanos(waitNanos);
      refill();
    }
    tokens -= 1.0;
  }

  private void refill() {
    final long now = System.nanoTime();
    tokens = Math.min(capacity, tokens + (now - lastNanos) * refillPerNano);
    lastNanos = now;
  }

  private static void sleepNanos(final long nanos) {
    if (nanos <= 0) {
      return;
    }
    try {
      Thread.sleep(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RagnarokException("rate limiter wait was interrupted", e);
    }
  }
}
