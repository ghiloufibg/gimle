package com.gimle.hugin.model;

/**
 * A snapshot that can re-label itself as the last good data behind a now-failing poll. Implemented
 * by every payload {@link SnapshotPoller} publishes, which is the whole of what that poller needs
 * to know about them.
 */
public interface Staleable<T> {

  /** This snapshot's own rows, aged and carrying the reason the latest poll failed. */
  T stale(String reason);
}
