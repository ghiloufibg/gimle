package com.gimle.hugin.model;

import com.gimle.cli.CliException;
import com.gimle.cli.CliExitCode;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Polls the cluster-wide audit trail on a virtual thread and publishes the result, so the render
 * loop reads a field rather than waiting on a request.
 *
 * <p>A third sibling of {@link ClusterPoller} and {@link ServicePoller}, with the identical failure
 * posture: a failed poll keeps the last good rows and marks them stale with the reason, rather than
 * blanking a feed on one timeout.
 */
public final class ActivityPoller implements AutoCloseable {

  private final ActivityReader reader;
  private final Duration interval;
  private final AtomicBoolean paused = new AtomicBoolean();
  private final AtomicBoolean running = new AtomicBoolean(true);

  private volatile ActivitySnapshot current;
  private volatile Thread thread;

  public ActivityPoller(
      final ActivityReader reader, final Duration interval, final String serverAddress) {
    this.reader = reader;
    this.interval = interval;
    this.current = ActivitySnapshot.connecting(serverAddress);
  }

  public void start() {
    thread = Thread.ofVirtual().name("hugin-activity").start(this::loop);
  }

  public ActivitySnapshot current() {
    return current;
  }

  public boolean paused() {
    return paused.get();
  }

  public void togglePaused() {
    paused.set(!paused.get());
    if (!paused.get()) {
      refreshNow();
    }
  }

  /** Wakes the poll loop so the next read happens now instead of at the next interval. */
  public void refreshNow() {
    Thread pollThread = thread;
    if (pollThread != null) {
      LockSupport.unpark(pollThread);
    }
  }

  /** One poll, run inline. Separate from the loop so a test can drive it without a thread. */
  public void pollOnce() {
    try {
      current = reader.read();
    } catch (CliException e) {
      // The audit trail is the one read here gated on a permission of its own, and a caller
      // without it is a normal situation to report rather than a failure to retry: an empty feed
      // would read as a quiet cluster, which is the opposite of the truth.
      current =
          e.exitCode() == CliExitCode.FORBIDDEN
              ? ActivitySnapshot.forbidden(current.serverAddress())
              : current.stale(describe(e));
    } catch (RuntimeException e) {
      current = current.stale(describe(e));
    }
  }

  private void loop() {
    while (running.get()) {
      if (!paused.get()) {
        pollOnce();
      }
      LockSupport.parkNanos(this, interval.toNanos());
    }
  }

  private static String describe(final RuntimeException e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }

  @Override
  public void close() {
    running.set(false);
    refreshNow();
  }
}
