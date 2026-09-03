package com.gimle.hugin.model;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Polls the Service table on a virtual thread and publishes the result, so the render loop reads a
 * field rather than waiting on the {@code 1 + n} requests a full read costs.
 *
 * <p>A sibling of {@link ClusterPoller} rather than a reuse of it: the two publish different
 * payloads, and threading a type parameter through a class whose whole job is to hold one volatile
 * field would buy nothing but indirection. The failure posture is deliberately identical -- a
 * failed poll keeps the last good rows and marks them stale with the reason, because a Service
 * table that blanks on one timeout is worse than one that says how old it is.
 */
public final class ServicePoller implements AutoCloseable {

  private final ServiceReader reader;
  private final Duration interval;
  private final AtomicBoolean paused = new AtomicBoolean();
  private final AtomicBoolean running = new AtomicBoolean(true);

  private volatile ServiceSnapshot current;
  private volatile Thread thread;

  public ServicePoller(
      final ServiceReader reader, final Duration interval, final String serverAddress) {
    this.reader = reader;
    this.interval = interval;
    this.current = ServiceSnapshot.connecting(serverAddress);
  }

  public void start() {
    thread = Thread.ofVirtual().name("hugin-services").start(this::loop);
  }

  public ServiceSnapshot current() {
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
