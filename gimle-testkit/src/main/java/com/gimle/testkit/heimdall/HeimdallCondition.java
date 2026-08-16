package com.gimle.testkit.heimdall;

import com.gimle.testkit.TestkitException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One registered condition. {@link #await} blocks until the event satisfying it arrives; the
 * duration is a deadline for declaring failure, not a poll interval -- the call returns the moment
 * the satisfying view, log line, or process event fires. On timeout it throws a {@link
 * HeimdallConditionError} carrying the full forensic report; if a cluster process died
 * unexpectedly, it fails immediately with the death as the cause instead of running out the clock.
 * {@link #future} exposes the same completion for composition.
 */
public final class HeimdallCondition {

  private final Heimdall heimdall;
  private final String description;
  private final Optional<String> deploymentHint;
  private final CompletableFuture<Void> future;

  HeimdallCondition(
      final Heimdall heimdall,
      final String description,
      final Optional<String> deploymentHint,
      final CompletableFuture<Void> future) {
    this.heimdall = heimdall;
    this.description = description;
    this.deploymentHint = deploymentHint;
    this.future = future;
  }

  public void await(final Duration timeout) {
    try {
      future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (final TimeoutException e) {
      throw heimdall.timeoutError("condition not met: " + description, timeout, deploymentHint);
    } catch (final ExecutionException e) {
      if (e.getCause() instanceof HeimdallConditionError error) {
        throw error;
      }
      throw new TestkitException("condition failed: " + description, e.getCause());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TestkitException("interrupted awaiting condition: " + description, e);
    }
  }

  public CompletableFuture<Void> future() {
    return future;
  }

  public String description() {
    return description;
  }
}
