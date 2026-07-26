package com.gimle.observability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * JFR sampling itself is inherently timing-dependent (the JVM decides when to sample), so — same
 * posture as Phase 1's {@code OldObjectSampleCorrelator} tests — this verifies the deterministic
 * parts (construction, registration bookkeeping, shutdown) rather than asserting on real sample
 * arrival within a fast unit test's window.
 */
class ThreadNameJfrAttributorTest {

  private static final ModuleId ID =
      new ModuleId("com.gimle.example.orders", Version.parse("1.0.0"));

  @Test
  void construction_and_shutdown_do_not_throw() {
    assertDoesNotThrow(
        () -> {
          try (ThreadNameJfrAttributor attributor =
              new ThreadNameJfrAttributor(new SimpleMeterRegistry())) {
            // constructed and immediately closed -- proves the JFR wiring itself doesn't blow up.
          }
        });
  }

  @Test
  void register_and_unregister_module_do_not_throw() {
    try (ThreadNameJfrAttributor attributor =
        new ThreadNameJfrAttributor(new SimpleMeterRegistry())) {
      assertDoesNotThrow(() -> attributor.register_module(ID));
      assertDoesNotThrow(() -> attributor.unregister_module(ID));
    }
  }

  @Test
  void unregistering_a_module_never_registered_does_not_throw() {
    try (ThreadNameJfrAttributor attributor =
        new ThreadNameJfrAttributor(new SimpleMeterRegistry())) {
      assertDoesNotThrow(() -> attributor.unregister_module(ID));
    }
  }
}
