package com.gimle.core.module;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The fully-qualified class names of a module's liveness and readiness probes plus that module's
 * own probe timing, every field optional. {@link #NONE} is a module with neither probe nor any
 * timing of its own.
 *
 * <p>The four timing fields mirror the manifest's {@code health:} block -- {@code
 * initialDelaySeconds}, {@code intervalSeconds}, {@code timeoutSeconds}, {@code failureThreshold}
 * -- and an absent one means "use the worker's default." Without them the worker's single cadence
 * applies identically to every module it hosts, so a module whose readiness check legitimately
 * needs longer than that default (a cold cache fill, a slow downstream dependency) fails every tick
 * indistinguishably from a genuinely broken probe; declaring its own timing here narrows the change
 * to that one module.
 *
 * <p>{@code initialDelay} is how long after a module reaches {@code ACTIVE} its first probe tick
 * fires, independent of {@code interval}. Absent means the first tick fires one interval after
 * {@code ACTIVE}, same as every interval after it.
 */
public record HealthProbes(
    Optional<String> livenessClass,
    Optional<String> readinessClass,
    Optional<Duration> initialDelay,
    Optional<Duration> interval,
    Optional<Duration> timeout,
    OptionalInt livenessFailureThreshold) {

  public static final HealthProbes NONE =
      new HealthProbes(
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          OptionalInt.empty());

  public HealthProbes {
    if (livenessClass == null || readinessClass == null) {
      throw new IllegalArgumentException("probe fields must be Optional.empty(), not null");
    }
    if (initialDelay == null || interval == null || timeout == null) {
      throw new IllegalArgumentException("probe timings must be Optional.empty(), not null");
    }
    if (livenessFailureThreshold == null) {
      throw new IllegalArgumentException(
          "livenessFailureThreshold must be OptionalInt.empty(), not null");
    }
    initialDelay.ifPresent(value -> requireNonNegative(value, "initialDelay"));
    // A zero or negative interval would schedule ticks back to back forever, and a zero or negative
    // timeout would fail every check before it could run -- both make the probe system unusable
    // rather than merely aggressive, so neither is a value a module may declare.
    interval.ifPresent(value -> requirePositive(value, "interval"));
    timeout.ifPresent(value -> requirePositive(value, "timeout"));
    if (livenessFailureThreshold.isPresent() && livenessFailureThreshold.getAsInt() < 1) {
      throw new IllegalArgumentException(
          "livenessFailureThreshold must be at least 1: " + livenessFailureThreshold.getAsInt());
    }
  }

  private static void requireNonNegative(Duration value, String field) {
    if (value.isNegative()) {
      throw new IllegalArgumentException(field + " must not be negative: " + value);
    }
  }

  private static void requirePositive(Duration value, String field) {
    if (value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(field + " must be positive: " + value);
    }
  }
}
