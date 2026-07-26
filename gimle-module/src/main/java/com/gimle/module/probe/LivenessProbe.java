package com.gimle.module.probe;

/**
 * Implemented by a hosted module to answer "is this instance alive," called directly by the
 * worker's probe loop — no socket, no serialization, no sidecar. Deliberately parameterless: a
 * module needing shared state with its hooks can implement both interfaces on one class and capture
 * what it needs during {@code on_install}. That class's package must be {@code exports}ed/{@code
 * opens}ed, same JPMS constraint as {@code ModuleLifecycleHooks}.
 */
public interface LivenessProbe {

  boolean is_alive();
}
