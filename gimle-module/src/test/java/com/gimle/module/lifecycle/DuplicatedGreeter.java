package com.gimle.module.lifecycle;

/**
 * Test-only fixture for {@link SimpleServiceRegistryTest}'s cross-classloader determinism case -- a
 * standalone top-level interface (not nested) so it can be redefined under an isolated class loader
 * and used as a {@link java.lang.reflect.Proxy} target without visibility complications.
 */
public interface DuplicatedGreeter {
  String greet();
}
