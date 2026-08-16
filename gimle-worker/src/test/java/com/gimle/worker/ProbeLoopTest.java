package com.gimle.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.time.TestClock;
import com.gimle.core.time.TestScheduler;
import com.gimle.testkit.Await;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Driven by a {@link TestScheduler} rather than {@code ProbeLoop}'s own real ticker, so ticks fire
 * exactly when the test advances virtual time. Two things follow, both worth more than the speed:
 *
 * <ul>
 *   <li>The intervals are the seconds-scale ones a real module manifest declares, not the 20ms
 *       stand-ins these tests needed when a real ticker had to outrun a real {@code Await} budget.
 *   <li>The assertions get stricter. "at least 3 results within 2 seconds" becomes "exactly 3
 *       results after exactly 3 intervals", and "no more than one further tick after stop()"
 *       becomes "no further ticks at all" -- both previously unprovable, because a real ticker
 *       gives no way to know whether another tick was merely late rather than absent.
 * </ul>
 *
 * <p>{@link #a_check_that_hangs_past_its_timeout_is_reported_as_a_failure} deliberately keeps real
 * waiting: the per-check timeout is enforced by a real {@code Future#get} on the module's own
 * scheduler, which is exactly the mechanism that test exists to prove.
 */
class ProbeLoopTest {

  private static final ModuleId ID =
      new ModuleId("com.gimle.example.orders", Version.parse("1.0.0"));

  /** Seconds-scale, like a real manifest's {@code probes.periodSeconds}. */
  private static final Duration INTERVAL = Duration.ofSeconds(10);

  private static final Duration TIMEOUT = Duration.ofSeconds(1);

  private final TestClock clock = new TestClock();
  private final TestScheduler ticker = new TestScheduler(clock);
  private final ProbeLoop probeLoop = new ProbeLoop(ticker);
  private final BoundedModuleScheduler scheduler = new BoundedModuleScheduler(ID, 4);

  @AfterEach
  void tearDown() {
    probeLoop.close();
    scheduler.close();
  }

  @Test
  void a_passing_check_reports_true_repeatedly() {
    List<Boolean> results = new CopyOnWriteArrayList<>();
    probeLoop.start("liveness", scheduler, () -> true, INTERVAL, TIMEOUT, results::add);

    ticker.advance(INTERVAL.multipliedBy(3));

    assertEquals(3, results.size(), "exactly one result per elapsed interval");
    assertTrue(results.stream().allMatch(Boolean::booleanValue));
  }

  @Test
  void a_failing_check_reports_false() {
    List<Boolean> results = new CopyOnWriteArrayList<>();
    probeLoop.start("liveness", scheduler, () -> false, INTERVAL, TIMEOUT, results::add);

    ticker.advance(INTERVAL);

    assertEquals(List.of(false), results);
  }

  @Test
  void a_check_that_throws_is_reported_as_a_failure_not_propagated() {
    List<Boolean> results = new CopyOnWriteArrayList<>();
    probeLoop.start(
        "liveness",
        scheduler,
        () -> {
          throw new IllegalStateException("probe blew up");
        },
        INTERVAL,
        TIMEOUT,
        results::add);

    ticker.advance(INTERVAL);

    assertEquals(List.of(false), results);
  }

  /**
   * The one case that still waits for real time on purpose -- see this class's own javadoc. The
   * tick itself is fired deterministically; only the timeout it is proving runs at real speed.
   */
  @Test
  void a_check_that_hangs_past_its_timeout_is_reported_as_a_failure() {
    List<Boolean> results = new CopyOnWriteArrayList<>();
    probeLoop.start(
        "liveness",
        scheduler,
        () -> {
          Thread.sleep(TimeUnit.SECONDS.toMillis(30));
          return true;
        },
        INTERVAL,
        Duration.ofMillis(100),
        results::add);

    ticker.advance(INTERVAL);

    assertEquals(List.of(false), results);
  }

  @Test
  void stop_halts_further_invocations_of_that_key() {
    AtomicInteger invocationCount = new AtomicInteger();
    probeLoop.start(
        "liveness",
        scheduler,
        () -> {
          invocationCount.incrementAndGet();
          return true;
        },
        INTERVAL,
        TIMEOUT,
        ignored -> {});

    ticker.advance(INTERVAL.multipliedBy(2));
    assertEquals(2, invocationCount.get());

    probeLoop.stop("liveness");
    ticker.advance(INTERVAL.multipliedBy(10));

    assertEquals(
        2,
        invocationCount.get(),
        "not one further tick after stop() -- exact, where a real ticker could only ever support"
            + " an 'at most one more in flight' approximation");
  }

  @Test
  void no_tick_fires_before_the_initial_delay_elapses() {
    Duration initialDelay = Duration.ofMinutes(1);
    List<Boolean> results = new CopyOnWriteArrayList<>();
    probeLoop.start(
        "liveness", scheduler, () -> true, INTERVAL, TIMEOUT, initialDelay, results::add);

    ticker.advance(initialDelay.minusNanos(1));
    assertTrue(results.isEmpty(), "one nanosecond short of the initial delay, nothing has fired");

    ticker.advance(Duration.ofNanos(1));
    assertEquals(1, results.size(), "and at exactly the initial delay, the first tick fires");
    assertTrue(results.get(0));
  }

  @Test
  void after_the_initial_delay_ticks_settle_onto_the_ordinary_interval() {
    Duration initialDelay = Duration.ofMinutes(1);
    List<Boolean> results = new CopyOnWriteArrayList<>();
    probeLoop.start(
        "liveness", scheduler, () -> true, INTERVAL, TIMEOUT, initialDelay, results::add);

    ticker.advance(initialDelay);
    assertEquals(1, results.size());

    ticker.advance(INTERVAL.multipliedBy(3));
    assertEquals(
        4, results.size(), "the initial delay applies once; subsequent spacing is the interval");
  }

  @Test
  void two_keys_are_scheduled_independently() {
    AtomicBoolean firstRan = new AtomicBoolean();
    AtomicBoolean secondRan = new AtomicBoolean();
    probeLoop.start("liveness", scheduler, () -> true, INTERVAL, TIMEOUT, r -> firstRan.set(true));
    probeLoop.start(
        "readiness", scheduler, () -> true, INTERVAL, TIMEOUT, r -> secondRan.set(true));

    ticker.advance(INTERVAL);

    assertTrue(firstRan.get() && secondRan.get());
  }

  /**
   * {@link Await} still has a place: this asserts on the real ticker the no-arg constructor builds,
   * so the production wiring itself stays covered rather than only the injected-scheduler path.
   */
  @Test
  void the_production_constructor_still_schedules_on_a_real_ticker() {
    try (ProbeLoop realTicker = new ProbeLoop()) {
      List<Boolean> results = new CopyOnWriteArrayList<>();
      realTicker.start(
          "liveness",
          scheduler,
          () -> true,
          Duration.ofMillis(20),
          Duration.ofSeconds(1),
          results::add);

      Await.until(() -> !results.isEmpty(), Duration.ofSeconds(2));
      assertTrue(results.get(0));
    }
  }
}
