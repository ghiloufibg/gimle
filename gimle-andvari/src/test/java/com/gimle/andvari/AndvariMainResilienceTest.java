package com.gimle.andvari;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gimle.pki.CertificateRotationMonitor;
import com.gimle.pki.OwnCertificateRotator;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.slf4j.LoggerFactory;

/**
 * What {@link AndvariMain} does when the network under it misbehaves: a certificate-rotation check
 * that fails must not take the ticker down with it, and a store endpoint hostname that does not
 * resolve on the second this process starts must not be baked in as permanently unresolvable.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AndvariMainResilienceTest {

  private static final String PROTOCOL_PROPERTY = "gimle.transport.protocol";

  /** The {@code .invalid} TLD is reserved as never-resolvable. */
  private static final String UNRESOLVABLE_HOST = "andvari-store.invalid";

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
            AndvariMain.rotationTick(
                rotator(), URI.create("https://127.0.0.1:1/bootstrap/csr"), null);
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
            AndvariMain.rotationTick(
                rotator(), URI.create("https://127.0.0.1:1/bootstrap/csr"), null));
  }

  @Test
  @Timeout(30)
  void an_already_resolvable_store_endpoint_is_returned_untouched() {
    List<SocketAddress> endpoints = List.of(new InetSocketAddress("127.0.0.1", 9090));

    assertEquals(endpoints, AndvariMain.awaitResolvableStoreEndpoints(endpoints));
  }

  @Test
  @Timeout(60)
  void a_store_endpoint_that_does_not_resolve_is_retried_instead_of_kept_unresolved()
      throws Exception {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AndvariMain.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    AtomicReference<List<SocketAddress>> returned = new AtomicReference<>();
    Thread resolver =
        Thread.ofPlatform()
            .start(
                () ->
                    returned.set(
                        AndvariMain.awaitResolvableStoreEndpoints(
                            List.of(new InetSocketAddress(UNRESOLVABLE_HOST, 9090)))));
    try {
      // Well past the first backoff: a name that still does not resolve has to leave this call
      // waiting, not hand back an address that can never connect to anything.
      resolver.join(Duration.ofSeconds(3));
      assertTrue(resolver.isAlive(), "resolution should still be retrying");
      assertNull(returned.get(), "nothing may be handed back while the name is unresolvable");
      assertTrue(
          appender.list.stream()
              .map(ILoggingEvent::getFormattedMessage)
              .anyMatch(message -> message.contains(UNRESOLVABLE_HOST)),
          "each attempt should say which hostname it could not resolve");
    } finally {
      resolver.interrupt();
      resolver.join(Duration.ofSeconds(10));
      logger.detachAppender(appender);
    }
    assertFalse(resolver.isAlive(), "an interrupt should end the retry loop");
  }

  /** Backoff doubles and then holds at its ceiling, so a long outage keeps retrying cheaply. */
  @Test
  void resolution_backoff_doubles_up_to_a_ceiling() {
    assertEquals(Duration.ofSeconds(2), AndvariMain.nextResolutionBackoff(Duration.ofSeconds(1)));
    assertEquals(Duration.ofSeconds(30), AndvariMain.nextResolutionBackoff(Duration.ofSeconds(20)));
    assertEquals(Duration.ofSeconds(30), AndvariMain.nextResolutionBackoff(Duration.ofSeconds(30)));
  }

  /**
   * A rotator with no listener of its own -- this suite is about what escapes a tick, not about
   * what the monitor reports.
   */
  private static OwnCertificateRotator rotator() {
    return new OwnCertificateRotator(
        new CertificateRotationMonitor("andvari", Duration.ofSeconds(2)));
  }
}
