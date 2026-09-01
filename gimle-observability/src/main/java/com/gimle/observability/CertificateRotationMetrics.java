package com.gimle.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The alertable form of a process's own certificate-rotation health, registered into whichever
 * {@link MeterRegistry} that process already ships to Muninn. Two gauges carry everything an
 * operator needs to page on: how many rotation checks have failed in a row, and how much validity
 * the certificate currently in use still has. A failing rotation is harmless while that second
 * gauge is comfortable and an outage in the making once it isn't, which is precisely why neither
 * number is useful without the other.
 *
 * <p>Deliberately takes the outcome as a plain {@code String} rather than depending on {@code
 * gimle-pki}'s own enum, the same posture {@link WorkerMetrics#recordCircuitBreakerTransition}
 * takes toward {@code gimle-fabric}'s breaker states: the name stays with the enum that defines it.
 */
public final class CertificateRotationMetrics {

  private static final String CHECKS = "gimle.certificate.rotation.checks";
  private static final String CONSECUTIVE_FAILURES =
      "gimle.certificate.rotation.consecutive.failures";
  private static final String REMAINING_SECONDS = "gimle.certificate.remaining.seconds";

  private final MeterRegistry registry;
  private final AtomicLong consecutiveFailures = new AtomicLong();
  private final AtomicLong remainingSeconds = new AtomicLong();

  public CertificateRotationMetrics() {
    this(new SimpleMeterRegistry());
  }

  public CertificateRotationMetrics(MeterRegistry registry) {
    this.registry = registry;
    // Registered up front rather than on first check: an operator asking "is this process's
    // certificate rotation healthy" needs a zero to read, not an absent meter to interpret.
    registry.gauge(CONSECUTIVE_FAILURES, Tags.empty(), consecutiveFailures, AtomicLong::get);
    registry.gauge(REMAINING_SECONDS, Tags.empty(), remainingSeconds, AtomicLong::get);
  }

  public MeterRegistry registry() {
    return registry;
  }

  /**
   * @param remainingValidity how long the certificate in use is still valid for, empty only when it
   *     could not be read at all -- in which case the gauge keeps its last known value rather than
   *     dropping to zero, since an unreadable certificate is a reason to distrust the reading, not
   *     evidence that the certificate expired
   */
  public void recordCheck(
      String outcome, int consecutiveFailureCount, Optional<Duration> remainingValidity) {
    Counter.builder(CHECKS).tags(Tags.of("outcome", outcome)).register(registry).increment();
    consecutiveFailures.set(consecutiveFailureCount);
    remainingValidity.ifPresent(remaining -> remainingSeconds.set(remaining.toSeconds()));
  }

  /** Cumulative checks recorded with this outcome, {@code 0} if none ever was. */
  public double checkCount(String outcome) {
    Counter counter = registry.find(CHECKS).tags(Tags.of("outcome", outcome)).counter();
    return counter == null ? 0.0 : counter.count();
  }

  public double consecutiveFailures() {
    return consecutiveFailures.get();
  }

  public double remainingSeconds() {
    return remainingSeconds.get();
  }
}
