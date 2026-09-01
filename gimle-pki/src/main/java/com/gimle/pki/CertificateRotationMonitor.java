package com.gimle.pki;

import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The operator-visible half of a process's own certificate rotation: it turns each check into a
 * {@link CertificateRotationStatus}, keeps the consecutive-failure count across checks, escalates
 * the log line once a streak stops looking like a blip, and hands every status to a {@link
 * CertificateRotationListener} for a process to meter and audit.
 *
 * <p>A rotation check runs on a short interval (seconds), so logging or auditing every failure
 * would bury the signal it is meant to raise. Only three points in a failure streak are worth a
 * line: the first failure, the moment the streak crosses {@link #ESCALATION_THRESHOLD} into
 * error-worthy territory, and then a repeat no more often than {@link #REPEAT_LOG_INTERVAL} for as
 * long as it lasts. The listener sees every check regardless -- a gauge is cheap to update and
 * meaningless if it only moves at those three points.
 */
public final class CertificateRotationMonitor {

  private static final Logger log = LoggerFactory.getLogger(CertificateRotationMonitor.class);

  /** Consecutive failures after which the streak is logged at error rather than warn level. */
  private static final int ESCALATION_THRESHOLD = 3;

  /** How often an ongoing failure streak is re-logged after it has already escalated. */
  private static final Duration REPEAT_LOG_INTERVAL = Duration.ofMinutes(1);

  private final String component;
  private final Duration checkInterval;
  private final CertificateRotationListener listener;
  private final Clock clock;

  private int consecutiveFailures;
  private Instant lastFailureLoggedAt;

  public CertificateRotationMonitor(String component, Duration checkInterval) {
    this(component, checkInterval, CertificateRotationListener.NONE, Clock.systemUTC());
  }

  public CertificateRotationMonitor(
      String component, Duration checkInterval, CertificateRotationListener listener) {
    this(component, checkInterval, listener, Clock.systemUTC());
  }

  public CertificateRotationMonitor(
      String component, Duration checkInterval, CertificateRotationListener listener, Clock clock) {
    if (component == null || component.isBlank()) {
      throw new IllegalArgumentException("component must not be blank");
    }
    if (checkInterval == null || checkInterval.isNegative() || checkInterval.isZero()) {
      throw new IllegalArgumentException("checkInterval must be positive");
    }
    this.component = component;
    this.checkInterval = checkInterval;
    this.listener = listener == null ? CertificateRotationListener.NONE : listener;
    this.clock = clock == null ? Clock.systemUTC() : clock;
  }

  /** Plaintext transport: there is nothing to rotate, and no failure streak to carry forward. */
  public CertificateRotationStatus disabled() {
    return recordCheck(CertificateRotationOutcome.DISABLED, Optional.empty(), Optional.empty());
  }

  public CertificateRotationStatus notDue(X509Certificate current) {
    return recordCheck(CertificateRotationOutcome.NOT_DUE, notAfterOf(current), Optional.empty());
  }

  public CertificateRotationStatus rotated(X509Certificate issued) {
    return recordCheck(CertificateRotationOutcome.ROTATED, notAfterOf(issued), Optional.empty());
  }

  /**
   * @param current the certificate still on disk, or {@code null} when it could not be read at all
   *     -- that case is exactly the one where an operator cannot tell how much runway is left, so
   *     it is called out explicitly in the log line rather than reported as no expiry
   */
  public CertificateRotationStatus failed(String message, X509Certificate current) {
    return recordCheck(
        CertificateRotationOutcome.FAILED,
        notAfterOf(current),
        Optional.of(message == null ? "unknown error" : message));
  }

  /** How many checks in a row have failed as of the last one recorded. */
  public synchronized int consecutiveFailures() {
    return consecutiveFailures;
  }

  private synchronized CertificateRotationStatus recordCheck(
      CertificateRotationOutcome outcome,
      Optional<Instant> currentNotAfter,
      Optional<String> failureMessage) {
    Instant now = clock.instant();
    if (outcome == CertificateRotationOutcome.FAILED) {
      consecutiveFailures++;
    } else {
      if (consecutiveFailures > 0) {
        log.info(
            "{} certificate rotation check recovered after {} consecutive failures",
            component,
            consecutiveFailures);
      }
      consecutiveFailures = 0;
      lastFailureLoggedAt = null;
    }
    CertificateRotationStatus status =
        new CertificateRotationStatus(
            outcome,
            currentNotAfter,
            consecutiveFailures,
            failureMessage,
            Optional.of(now.plus(checkInterval)));
    logStatus(status, now);
    notifyListener(status);
    return status;
  }

  private void logStatus(CertificateRotationStatus status, Instant now) {
    switch (status.outcome()) {
      case DISABLED -> {
        // Nothing to say: a plaintext cluster has no certificate lifecycle to report on, and this
        // runs on a seconds-scale interval.
      }
      case NOT_DUE ->
          log.debug(
              "{} certificate is not due for renewal; valid until {}",
              component,
              status.currentNotAfter().map(Object::toString).orElse("unknown"));
      case ROTATED ->
          log.info(
              "{} certificate rotated; the new certificate is valid until {}",
              component,
              status.currentNotAfter().map(Object::toString).orElse("unknown"));
      case FAILED -> logFailure(status, now);
    }
  }

  private void logFailure(CertificateRotationStatus status, Instant now) {
    boolean escalated = status.consecutiveFailures() >= ESCALATION_THRESHOLD;
    if (!shouldLogFailure(status, now, escalated)) {
      return;
    }
    lastFailureLoggedAt = now;
    String runway = describeRunway(status, now);
    String nextCheck = status.nextCheckAt().map(Object::toString).orElse("unknown");
    if (escalated) {
      log.error(
          "{} certificate rotation has failed {} times in a row (last error: {}); the certificate"
              + " in use {} and will not be renewed until a check succeeds; next attempt at {}",
          component,
          status.consecutiveFailures(),
          status.failureMessage().orElse("unknown error"),
          runway,
          nextCheck);
    } else {
      log.warn(
          "{} certificate rotation check failed: {}; the certificate in use {}; next attempt at {}",
          component,
          status.failureMessage().orElse("unknown error"),
          runway,
          nextCheck);
    }
  }

  private boolean shouldLogFailure(
      CertificateRotationStatus status, Instant now, boolean escalated) {
    if (status.consecutiveFailures() == 1 || status.consecutiveFailures() == ESCALATION_THRESHOLD) {
      return true;
    }
    if (!escalated) {
      return false;
    }
    return lastFailureLoggedAt == null
        || !now.isBefore(lastFailureLoggedAt.plus(REPEAT_LOG_INTERVAL));
  }

  private static String describeRunway(CertificateRotationStatus status, Instant now) {
    Optional<Duration> remaining = status.remainingValidity(now);
    if (remaining.isEmpty()) {
      return "could not be read, so its remaining validity is unknown";
    }
    Duration left = remaining.get();
    Instant notAfter = status.currentNotAfter().orElseThrow();
    if (left.isNegative()) {
      return "expired at " + notAfter;
    }
    return "is still valid until " + notAfter + " (" + describe(left) + " of runway left)";
  }

  private static String describe(Duration duration) {
    long hours = duration.toHours();
    if (hours >= 48) {
      return duration.toDays() + "d";
    }
    if (hours >= 1) {
      return hours + "h";
    }
    return duration.toMinutes() + "m";
  }

  private void notifyListener(CertificateRotationStatus status) {
    try {
      listener.onCheck(status);
    } catch (RuntimeException e) {
      // A meter update or an audit append must never be able to break the rotation loop that
      // produced it -- the loop is what keeps the certificate alive.
      log.warn("{} certificate rotation listener failed: {}", component, e.getMessage(), e);
    }
  }

  private static Optional<Instant> notAfterOf(X509Certificate certificate) {
    return certificate == null
        ? Optional.empty()
        : Optional.of(certificate.getNotAfter().toInstant());
  }
}
