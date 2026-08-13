package com.gimle.pki;

import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * When a certificate should be renewed: a randomized point within the last 20-30% of the
 * certificate's validity window, not a fixed threshold -- if many nodes were provisioned at the
 * same moment, a fixed threshold means they all try to renew in the same instant, a self-inflicted
 * thundering herd against the control plane. The random point is picked once, at construction, and
 * cached -- a caller polling {@link #isDue} repeatedly (e.g. once per tick) must see a stable
 * answer, not a new coin flip every time.
 */
public final class RenewalSchedule {

  private static final double WINDOW_START_FRACTION = 0.70;
  private static final double WINDOW_END_FRACTION = 0.80;

  private final Instant renewAt;

  private RenewalSchedule(Instant renewAt) {
    this.renewAt = renewAt;
  }

  public static RenewalSchedule of(X509Certificate certificate) {
    Instant notBefore = certificate.getNotBefore().toInstant();
    Instant notAfter = certificate.getNotAfter().toInstant();
    Duration validity = Duration.between(notBefore, notAfter);
    double fraction =
        WINDOW_START_FRACTION
            + ThreadLocalRandom.current().nextDouble()
                * (WINDOW_END_FRACTION - WINDOW_START_FRACTION);
    Instant renewAt = notBefore.plusMillis((long) (validity.toMillis() * fraction));
    return new RenewalSchedule(renewAt);
  }

  public boolean isDue(Instant now) {
    return !now.isBefore(renewAt);
  }

  public Instant renewAt() {
    return renewAt;
  }
}
