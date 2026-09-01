package com.gimle.core.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class HealthProbesTest {

  private static HealthProbes withTiming(
      Optional<Duration> initialDelay,
      Optional<Duration> interval,
      Optional<Duration> timeout,
      OptionalInt failureThreshold) {
    return new HealthProbes(
        Optional.of("com.gimle.example.SomeProbe"),
        Optional.empty(),
        initialDelay,
        interval,
        timeout,
        failureThreshold);
  }

  @Test
  void none_declares_no_probe_and_no_timing() {
    assertTrue(HealthProbes.NONE.livenessClass().isEmpty());
    assertTrue(HealthProbes.NONE.readinessClass().isEmpty());
    assertTrue(HealthProbes.NONE.initialDelay().isEmpty());
    assertTrue(HealthProbes.NONE.interval().isEmpty());
    assertTrue(HealthProbes.NONE.timeout().isEmpty());
    assertTrue(HealthProbes.NONE.livenessFailureThreshold().isEmpty());
  }

  @Test
  void keeps_every_declared_timing() {
    HealthProbes probes =
        withTiming(
            Optional.of(Duration.ofSeconds(5)),
            Optional.of(Duration.ofSeconds(10)),
            Optional.of(Duration.ofSeconds(30)),
            OptionalInt.of(6));

    assertEquals(Duration.ofSeconds(5), probes.initialDelay().orElseThrow());
    assertEquals(Duration.ofSeconds(10), probes.interval().orElseThrow());
    assertEquals(Duration.ofSeconds(30), probes.timeout().orElseThrow());
    assertEquals(6, probes.livenessFailureThreshold().orElseThrow());
  }

  @Test
  void a_zero_initial_delay_is_accepted_because_probing_immediately_is_meaningful() {
    HealthProbes probes =
        withTiming(
            Optional.of(Duration.ZERO), Optional.empty(), Optional.empty(), OptionalInt.empty());

    assertEquals(Duration.ZERO, probes.initialDelay().orElseThrow());
  }

  @Test
  void a_negative_initial_delay_is_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            withTiming(
                Optional.of(Duration.ofSeconds(-1)),
                Optional.empty(),
                Optional.empty(),
                OptionalInt.empty()));
  }

  @Test
  void a_zero_interval_is_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            withTiming(
                Optional.empty(),
                Optional.of(Duration.ZERO),
                Optional.empty(),
                OptionalInt.empty()));
  }

  @Test
  void a_zero_timeout_is_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            withTiming(
                Optional.empty(),
                Optional.empty(),
                Optional.of(Duration.ZERO),
                OptionalInt.empty()));
  }

  @Test
  void a_negative_timeout_is_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            withTiming(
                Optional.empty(),
                Optional.empty(),
                Optional.of(Duration.ofSeconds(-2)),
                OptionalInt.empty()));
  }

  @Test
  void a_failure_threshold_below_one_is_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> withTiming(Optional.empty(), Optional.empty(), Optional.empty(), OptionalInt.of(0)));
  }

  @Test
  void null_timings_are_rejected_rather_than_treated_as_absent() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HealthProbes(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                null,
                Optional.empty(),
                OptionalInt.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HealthProbes(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                null));
  }
}
