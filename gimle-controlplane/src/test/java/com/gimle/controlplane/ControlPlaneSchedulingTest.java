package com.gimle.controlplane;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Proves {@link ControlPlaneMain#scheduleIndependentTickers} actually delivers the isolation its
 * own javadoc claims: a reconcile tick that never returns (a hung store call, the real-world
 * trigger) or that throws on every invocation must not stop certificate rotation or
 * reconciler-lease renewal from continuing to fire on their own schedule. {@code ControlPlaneMain}
 * itself wires this against a real {@code StoreClient}/{@code ApiServer}, which isn't practical to
 * stand up here -- this exercises the scheduling mechanism directly with fake tick bodies instead.
 *
 * <p>{@link Isolated}: these assertions poll real scheduled-executor tick counts against wall-clock
 * deadlines, the same CPU-contention-under-class-level-concurrency sensitivity {@code
 * RaftClusterTest} and {@code SecretStoreTest} already guard against the same way.
 */
@Isolated
class ControlPlaneSchedulingTest {

  private static final Duration TICK_INTERVAL = Duration.ofMillis(30);

  private static void awaitAtLeast(AtomicInteger counter, int threshold, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (counter.get() >= threshold) {
        return;
      }
      Thread.sleep(10);
    }
    assertTrue(
        counter.get() >= threshold,
        "expected at least " + threshold + " ticks, saw only " + counter.get());
  }

  private static void shutdownAll(List<ScheduledExecutorService> executors) {
    executors.forEach(ScheduledExecutorService::shutdownNow);
  }

  @Test
  @Timeout(15)
  void cert_rotation_and_lease_renewal_keep_ticking_while_the_reconcile_tick_is_blocked_forever()
      throws Exception {
    AtomicInteger leaseTicks = new AtomicInteger();
    AtomicInteger reconcileTicks = new AtomicInteger();
    AtomicInteger certRotationTicks = new AtomicInteger();
    CountDownLatch neverReleased = new CountDownLatch(1);

    Runnable leaseTick = leaseTicks::incrementAndGet;
    Runnable reconcileTick =
        () -> {
          reconcileTicks.incrementAndGet();
          try {
            // Simulates a reconcile tick blocked on a hung store connection: never returns until
            // the executor itself is torn down (see teardown below), the same way the real
            // reconcile tick used to block forever on a silent StoreConnection socket.
            neverReleased.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };
    Runnable certRotationTick = certRotationTicks::incrementAndGet;

    List<ScheduledExecutorService> tickers =
        ControlPlaneMain.scheduleIndependentTickers(
            leaseTick, reconcileTick, certRotationTick, TICK_INTERVAL);
    try {
      awaitAtLeast(certRotationTicks, 3, Duration.ofSeconds(5));
      awaitAtLeast(leaseTicks, 3, Duration.ofSeconds(5));
      // The reconcile tick ran exactly once and is still stuck in it -- fixed-rate scheduling
      // never starts a second execution of a task that hasn't finished its first. This is the
      // behavior being guarded against, not a side assertion: before the executor split, the
      // same block would have also frozen leaseTicks/certRotationTicks at 0 or 1 forever.
      assertTrue(
          reconcileTicks.get() == 1, "reconcile tick ran " + reconcileTicks.get() + " times");
    } finally {
      shutdownAll(tickers);
    }
  }

  @Test
  @Timeout(15)
  void cert_rotation_and_lease_renewal_keep_ticking_while_the_reconcile_tick_throws_every_time()
      throws Exception {
    AtomicInteger leaseTicks = new AtomicInteger();
    AtomicInteger reconcileTicks = new AtomicInteger();
    AtomicInteger certRotationTicks = new AtomicInteger();

    Runnable leaseTick = leaseTicks::incrementAndGet;
    Runnable reconcileTick =
        () -> {
          reconcileTicks.incrementAndGet();
          throw new RuntimeException("reconcile tick blew up");
        };
    Runnable certRotationTick = certRotationTicks::incrementAndGet;

    List<ScheduledExecutorService> tickers =
        ControlPlaneMain.scheduleIndependentTickers(
            leaseTick, reconcileTick, certRotationTick, TICK_INTERVAL);
    try {
      awaitAtLeast(certRotationTicks, 3, Duration.ofSeconds(5));
      awaitAtLeast(leaseTicks, 3, Duration.ofSeconds(5));
      assertTrue(reconcileTicks.get() >= 1, "reconcile tick never ran");
    } finally {
      shutdownAll(tickers);
    }
  }
}
