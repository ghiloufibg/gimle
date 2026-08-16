package com.gimle.hilmir.store;

import com.gimle.core.exception.GimleRaftException;
import com.gimle.hilmir.HilmirException;
import java.io.PrintStream;
import java.time.Duration;

/**
 * Bounded retry for {@code StoreClient#addServer}/{@code #removeServer}: both throw {@link
 * GimleRaftException} for genuine unreachability and for a transient "another membership change is
 * still in flight" rejection alike -- the two are not distinguishable from the exception alone (see
 * {@code StoreClient#addServer}'s own javadoc), so a caller that wants add/remove to tolerate the
 * transient case has to retry regardless of which one actually happened, then give up and surface
 * the last attempt's exception once the bound is exhausted.
 */
final class StoreRetry {

  private static final int MAX_ATTEMPTS = 10;
  private static final Duration SLEEP_BETWEEN_ATTEMPTS = Duration.ofMillis(200);

  /** Print one progress line once retrying has gone on long enough to be worth mentioning. */
  private static final int PROGRESS_AFTER_ATTEMPT = 3;

  private StoreRetry() {}

  static void run(final String description, final PrintStream out, final Runnable operation) {
    GimleRaftException lastFailure = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        operation.run();
        return;
      } catch (final GimleRaftException e) {
        lastFailure = e;
        if (attempt == PROGRESS_AFTER_ATTEMPT) {
          out.println("retrying " + description + " (a membership change may still be settling)");
        }
        if (attempt < MAX_ATTEMPTS) {
          sleep(description);
        }
      }
    }
    throw lastFailure;
  }

  private static void sleep(final String description) {
    try {
      Thread.sleep(SLEEP_BETWEEN_ATTEMPTS.toMillis());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HilmirException("interrupted while retrying " + description, e);
    }
  }
}
