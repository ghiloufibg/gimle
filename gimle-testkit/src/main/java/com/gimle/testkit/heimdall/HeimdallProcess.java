package com.gimle.testkit.heimdall;

/**
 * The minimal shape {@link Heimdall} needs of a spawned cluster process: enough to report its
 * identity in a forensic report and to distinguish an expected teardown kill from a genuine crash.
 * Deliberately not the richer process-handle interface a real cluster fixture builds for itself
 * (spawn/restart/kill/log-file access, ...) -- {@code role()} returns a plain label rather than a
 * fixture-specific enum precisely so this module never depends on one.
 */
public interface HeimdallProcess {

  /** A short label identifying the process's kind (e.g. {@code "STORE"}, {@code "AGENT"}). */
  String role();

  /** Stable identity within the cluster (e.g. {@code "store-0"}, {@code "controlplane-1"}). */
  String id();

  boolean isAlive();

  /** True when the most recent exit (or in-flight kill) was initiated by the harness itself. */
  boolean exitWasExpected();

  /** Registers a callback invoked whenever the underlying process exits. */
  void onExit(Runnable callback);
}
