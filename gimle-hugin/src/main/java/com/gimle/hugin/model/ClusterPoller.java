package com.gimle.hugin.model;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Polls the control plane on a virtual thread and publishes the result, so the render loop reads a
 * field rather than waiting on HTTP.
 *
 * <p>A failed poll keeps the last good snapshot and marks it stale with the reason: converge from
 * whatever state you find, including "the control plane was gone for eight seconds," rather than
 * blanking the screen the moment a request times out.
 */
public final class ClusterPoller implements AutoCloseable {

  private final SnapshotReader reader;
  private final Duration interval;
  private final AtomicBoolean paused = new AtomicBoolean();
  private final AtomicBoolean running = new AtomicBoolean(true);

  private volatile ClusterSnapshot current;
  private volatile Thread thread;

  public ClusterPoller(
      final SnapshotReader reader, final Duration interval, final String serverAddress) {
    this.reader = reader;
    this.interval = interval;
    this.current = ClusterSnapshot.connecting(serverAddress);
  }

  public void start() {
    thread = Thread.ofVirtual().name("hugin-poller").start(this::loop);
  }

  public ClusterSnapshot current() {
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
