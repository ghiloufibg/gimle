package com.gimle.core.module;

import java.time.Duration;
import java.util.Optional;

/**
 * The fully-qualified class names of a module's liveness and readiness probes, each optional.
 * {@link #NONE} is a module with neither probe configured.
 *
 * <p>{@code initialDelay} is the manifest's {@code health.initialDelaySeconds}: how long after a
 * module reaches {@code ACTIVE} before its first probe tick fires, independent of the probe's own
 * {@code intervalSeconds}. Absent preserves the original behavior -- the first tick fires one
 * interval after {@code ACTIVE}, same as every interval after it.
 */
public record HealthProbes(
    Optional<String> livenessClass,
    Optional<String> readinessClass,
    Optional<Duration> initialDelay) {

  public static final HealthProbes NONE =
      new HealthProbes(Optional.empty(), Optional.empty(), Optional.empty());

  public HealthProbes {
    if (livenessClass == null || readinessClass == null) {
      throw new IllegalArgumentException("probe fields must be Optional.empty(), not null");
    }
    if (initialDelay == null) {
      throw new IllegalArgumentException("initialDelay must be Optional.empty(), not null");
    }
  }

  /** Back-compat: defaults {@code initialDelay} to {@code Optional.empty()}. */
  public HealthProbes(Optional<String> livenessClass, Optional<String> readinessClass) {
    this(livenessClass, readinessClass, Optional.empty());
  }
}
