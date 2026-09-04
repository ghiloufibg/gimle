package com.gimle.hugin.model;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Polls one read on a virtual thread and publishes the result, so the render loop reads a field
 * rather than waiting on I/O: a slow or unreachable control plane costs freshness, not
 * responsiveness, and {@code q} still quits immediately.
 *
 * <p>A failed poll keeps the last good rows and marks them stale with the failure's own reason,
 * because a table that blanks on one timeout is worse than one that says how old it is. That is the
 * only thing this class knows about its payload -- hence {@link Staleable} rather than three copies
 * of this loop, one per screen, drifting apart as each gains a fix the others don't.
 */
public final class SnapshotPoller<T extends Staleable<T>> implements AutoCloseable {

  private final Supplier<T> read;
  private final Duration interval;
  private final String threadName;
  private final AtomicBoolean paused = new AtomicBoolean();
  private final AtomicBoolean running = new AtomicBoolean(true);

  private volatile T current;
  private volatile Thread thread;

  public SnapshotPoller(
      final Supplier<T> read, final T initial, final Duration interval, final String threadName) {
    this.read = read;
    this.current = initial;
    this.interval = interval;
    this.threadName = threadName;
  }

  /**
   * A poller that reads once and then only when asked, for a screen whose read is too expensive to
   * repeat on a clock and whose answer does not change on its own between two keystrokes.
   *
   * <p>Still a poller rather than a bare thread, so such a screen keeps the same staleness
   * reporting, pausing and {@code r} refresh every other one has instead of growing its own.
   */
  public static <T extends Staleable<T>> SnapshotPoller<T> onDemand(
      final Supplier<T> read, final T initial, final String threadName) {
    return new SnapshotPoller<>(read, initial, Duration.ZERO, threadName);
  }

  public void start() {
    thread = Thread.ofVirtual().name(threadName).start(this::loop);
  }

  public T current() {
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
      current = read.get();
    } catch (RuntimeException e) {
      current = current.stale(describe(e));
    }
  }

  private void loop() {
    while (running.get()) {
      if (!paused.get()) {
        pollOnce();
      }
      // A zero interval means "never on a clock": park until something asks, which is what an
      // on-demand poller waits for and the one case a timed park would get wrong by re-reading.
      if (interval.isZero()) {
        LockSupport.park(this);
      } else {
        LockSupport.parkNanos(this, interval.toNanos());
      }
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
