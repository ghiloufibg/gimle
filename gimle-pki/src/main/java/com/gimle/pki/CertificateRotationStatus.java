package com.gimle.pki;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The full result of one certificate-rotation check, in place of the bare {@code boolean} that used
 * to be the only thing a caller learned. A rotation failure is harmless right up until the
 * certificate it failed to renew expires, so the failure alone is not the interesting part: what an
 * operator needs is how many checks in a row have failed, how much validity the certificate they
 * still hold has left, and when the next attempt happens.
 *
 * @param currentNotAfter the expiry of the certificate currently on disk -- empty only when that
 *     certificate could not be read at all, which is itself a {@link
 *     CertificateRotationOutcome#FAILED} check
 * @param consecutiveFailures how many checks in a row have now failed, {@code 0} after any check
 *     that did not fail
 * @param nextCheckAt when the next check is scheduled, so the runway left can be read against a
 *     concrete retry rather than an unstated interval
 */
public record CertificateRotationStatus(
    CertificateRotationOutcome outcome,
    Optional<Instant> currentNotAfter,
    int consecutiveFailures,
    Optional<String> failureMessage,
    Optional<Instant> nextCheckAt) {

  public CertificateRotationStatus {
    if (outcome == null) {
      throw new IllegalArgumentException("outcome must not be null");
    }
    if (consecutiveFailures < 0) {
      throw new IllegalArgumentException("consecutiveFailures must not be negative");
    }
    currentNotAfter = currentNotAfter == null ? Optional.empty() : currentNotAfter;
    failureMessage = failureMessage == null ? Optional.empty() : failureMessage;
    nextCheckAt = nextCheckAt == null ? Optional.empty() : nextCheckAt;
  }

  public boolean rotated() {
    return outcome == CertificateRotationOutcome.ROTATED;
  }

  public boolean failed() {
    return outcome == CertificateRotationOutcome.FAILED;
  }

  /**
   * How much validity the certificate currently on disk still has at {@code now}. Negative once
   * that certificate has expired -- deliberately not clamped to zero, since "expired two hours ago"
   * and "expires in a moment" are different situations to an operator reading it.
   */
  public Optional<Duration> remainingValidity(Instant now) {
    return currentNotAfter.map(notAfter -> Duration.between(now, notAfter));
  }
}
