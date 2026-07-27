package com.gimle.core.logging;

/**
 * Callback surface for closing one instance's sifted log file, kept separate from {@link
 * InstanceSiftingFileAppender} itself so callers outside {@code gimle-core} (e.g. {@code
 * gimle-worker}'s {@code WorkerRuntime}, reacting to a module uninstall) depend only on this
 * two-method JDK-typed contract and never need to reference a Logback-derived type directly.
 */
public interface InstanceLogCloser {

  void closeInstance(String deploymentName, int instanceIndex);
}
