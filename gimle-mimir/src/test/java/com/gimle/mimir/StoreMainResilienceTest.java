package com.gimle.mimir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.pki.CertificateRotationMonitor;
import com.gimle.pki.OwnCertificateRotator;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * What {@link StoreMain} does when the network under it misbehaves: a certificate-rotation check
 * that fails must not take the ticker down with it -- this replica's Raft membership outlives its
 * certificate only for as long as renewal keeps running.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class StoreMainResilienceTest {

  private static final String PROTOCOL_PROPERTY = "gimle.transport.protocol";

  @AfterEach
  void clearTransportProperty() {
    System.clearProperty(PROTOCOL_PROPERTY);
  }

  @Test
  @Timeout(30)
  void a_rotation_check_that_fails_keeps_the_ticker_running() throws Exception {
    // TLS is on but gimle.tls.certFile and friends are unset, so TlsSettings.fromConfig() throws
    // on every tick -- the same shape as a certificate file that is momentarily absent while an
    // external tool replaces the pair.
    System.setProperty(PROTOCOL_PROPERTY, "tls");
    AtomicInteger ticks = new AtomicInteger();
    CountDownLatch thirdTick = new CountDownLatch(3);
    ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
    try {
      ticker.scheduleAtFixedRate(
          () -> {
            ticks.incrementAndGet();
            thirdTick.countDown();
            StoreMain.rotationTick(
                rotator(), URI.create("https://127.0.0.1:1/bootstrap/csr"), null, null);
          },
          0,
          20,
          TimeUnit.MILLISECONDS);

      assertTrue(
          thirdTick.await(10, TimeUnit.SECONDS),
          "a failing rotation check must not cancel the repeating task; it ran only "
              + ticks.get()
              + " time(s)");
    } finally {
      ticker.shutdownNow();
    }
  }

  @Test
  @Timeout(30)
  void a_rotation_check_that_fails_never_propagates() {
    System.setProperty(PROTOCOL_PROPERTY, "tls");
    assertDoesNotThrow(
        () ->
            StoreMain.rotationTick(
                rotator(), URI.create("https://127.0.0.1:1/bootstrap/csr"), null, null));
  }

  /**
   * A rotator with no listener of its own -- this suite is about what escapes a tick, not about
   * what the monitor reports.
   */
  private static OwnCertificateRotator rotator() {
    return new OwnCertificateRotator(
        new CertificateRotationMonitor("store 127.0.0.1:1", Duration.ofSeconds(2)));
  }
}
