package com.gimle.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CertificateRotationMetricsTest {

  @Test
  void both_gauges_are_registered_before_any_check_has_been_recorded() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new CertificateRotationMetrics(registry);

    // An absent meter is not an answer to "is rotation healthy" -- a zero is.
    assertNotNull(registry.find("gimle.certificate.rotation.consecutive.failures").gauge());
    assertNotNull(registry.find("gimle.certificate.remaining.seconds").gauge());
  }

  @Test
  void each_outcome_gets_its_own_counter() {
    CertificateRotationMetrics metrics = new CertificateRotationMetrics();

    metrics.recordCheck("NOT_DUE", 0, Optional.of(Duration.ofDays(10)));
    metrics.recordCheck("NOT_DUE", 0, Optional.of(Duration.ofDays(10)));
    metrics.recordCheck("FAILED", 1, Optional.of(Duration.ofDays(10)));

    assertEquals(2.0, metrics.checkCount("NOT_DUE"));
    assertEquals(1.0, metrics.checkCount("FAILED"));
    assertEquals(0.0, metrics.checkCount("ROTATED"));
  }

  @Test
  void the_failure_gauge_tracks_the_streak_up_and_back_down_again() {
    CertificateRotationMetrics metrics = new CertificateRotationMetrics();

    metrics.recordCheck("FAILED", 1, Optional.of(Duration.ofHours(48)));
    metrics.recordCheck("FAILED", 2, Optional.of(Duration.ofHours(47)));
    assertEquals(2.0, metrics.consecutiveFailures());

    metrics.recordCheck("ROTATED", 0, Optional.of(Duration.ofDays(30)));
    assertEquals(0.0, metrics.consecutiveFailures());
    assertEquals(Duration.ofDays(30).toSeconds(), metrics.remainingSeconds());
  }

  @Test
  void an_unreadable_certificate_leaves_the_remaining_validity_gauge_at_its_last_known_value() {
    CertificateRotationMetrics metrics = new CertificateRotationMetrics();
    metrics.recordCheck("NOT_DUE", 0, Optional.of(Duration.ofHours(12)));

    metrics.recordCheck("FAILED", 1, Optional.empty());

    assertEquals(Duration.ofHours(12).toSeconds(), metrics.remainingSeconds());
    assertEquals(1.0, metrics.consecutiveFailures());
  }

  @Test
  void an_expired_certificate_reports_negative_remaining_seconds() {
    CertificateRotationMetrics metrics = new CertificateRotationMetrics();

    metrics.recordCheck("FAILED", 5, Optional.of(Duration.ofHours(-2)));

    assertEquals(Duration.ofHours(-2).toSeconds(), metrics.remainingSeconds());
  }
}
