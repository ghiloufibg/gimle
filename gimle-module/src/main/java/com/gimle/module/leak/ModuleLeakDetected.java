package com.gimle.module.leak;

import com.gimle.core.module.ModuleId;
import java.time.Duration;
import java.util.Optional;

/**
 * Reported by {@link LeakTracker} when a disposed module's classloader is still reachable after its
 * detection window has elapsed, meaning it was not garbage collected as expected.
 */
public record ModuleLeakDetected(
    ModuleId id, Duration survivalTime, Optional<String> retainingPath) {

  public ModuleLeakDetected {
    if (id == null) {
      throw new IllegalArgumentException("module id must not be null");
    }
    if (survivalTime == null) {
      throw new IllegalArgumentException("survival time must not be null");
    }
    if (retainingPath == null) {
      throw new IllegalArgumentException("retaining path must be Optional.empty(), not null");
    }
  }
}
