package com.gimle.core.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** See {@link TestClockTest} for why shared test-support code carries its own tests. */
class TestSchedulerTest {

  @Test
  void a_one_shot_task_does_not_run_before_its_delay_elapses(TestClock clock) {
    TestScheduler scheduler = new TestScheduler(clock);
    AtomicInteger runs = new AtomicInteger();
    scheduler.schedule(runs::incrementAndGet, 10, TimeUnit.SECONDS);

    assertEquals(0, scheduler.advance(Duration.ofSeconds(9)));
    assertEquals(0, runs.get());

    assertEquals(1, scheduler.advance(Duration.ofSeconds(1)));
    assertEquals(1, runs.get());
  }

  @Test
  void a_one_shot_task_runs_exactly_once_however_far_time_advances(TestClock clock) {
    TestScheduler scheduler = new TestScheduler(clock);
    AtomicInteger runs = new AtomicInteger();
    scheduler.schedule(runs::incrementAndGet, 1, TimeUnit.SECONDS);

    scheduler.advance(Duration.ofHours(1));
    assertEquals(1, runs.get());
    assertEquals(0, scheduler.pendingTaskCount());
  }

  /**
   * The regression guard for a real bug in the first draft of {@link TestScheduler}: the periodic
   * branch ran the task and then the reschedule step ran it a second time, so every tick
   * double-fired. A count-based assertion catches that where a "did it fire at all" one would not.
   */
  @Test
  void a_fixed_rate_task_fires_exactly_once_per_period_not_twice(TestClock clock) {
    TestScheduler scheduler = new TestScheduler(clock);
    AtomicInteger runs = new AtomicInteger();
    scheduler.scheduleAtFixedRate(runs::incrementAndGet, 1, 1, TimeUnit.SECONDS);

    scheduler.advance(Duration.ofSeconds(1));
    assertEquals(1, runs.get(), "exactly one tick after one period");

    scheduler.advance(Duration.ofSeconds(1));
    assertEquals(2, runs.get(), "exactly two ticks after two periods");

    scheduler.advance(Duration.ofSeconds(10));
    assertEquals(12, runs.get(), "ten more periods, ten more ticks -- no doubling, no drift");
  }

  @Test
  void a_periodic_task_replays_every_tick_in_a_long_advance_rather_than_collapsing_to_one(
      TestClock clock) {
    TestScheduler scheduler = new TestScheduler(clock);
    List<Instant> observed = new ArrayList<>();
    scheduler.scheduleAtFixedRate(() -> observed.add(clock.instant()), 1, 1, TimeUnit.SECONDS);

    scheduler.advance(Duration.ofSeconds(5));

    assertEquals(5, observed.size());
    // Each tick must observe its own scheduled instant, not the end of the advance window.
    for (int i = 0; i < observed.size(); i++) {
      assertEquals(TestClock.DEFAULT_START.plusSeconds(i + 1L), observed.get(i));
    }
  }

  @Test
  void fixed_delay_measures_from_completion_so_a_slow_tick_pushes_the_next_one_out(
      TestClock clock) {
    TestScheduler scheduler = new TestScheduler(clock);
    List<Instant> observed = new ArrayList<>();
    // Each run "takes" 2s of virtual time, so with a 1s fixed delay the spacing becomes 3s.
    scheduler.scheduleWithFixedDelay(
        () -> {
          observed.add(clock.instant());
          clock.advance(Duration.ofSeconds(2));
        },
        1,
        1,
        TimeUnit.SECONDS);

    scheduler.advance(Duration.ofSeconds(10));

    assertEquals(
        List.of(
            TestClock.DEFAULT_START.plusSeconds(1),
            TestClock.DEFAULT_START.plusSeconds(4),
            TestClock.DEFAULT_START.plusSeconds(7),
            TestClock.DEFAULT_START.plusSeconds(10)),
        observed);
  }

  @Test
  void fixed_rate_does_not_drift_when_a_tick_consumes_time(TestClock clock) {
    TestScheduler scheduler = new TestScheduler(clock);
    List<Instant> observed = new ArrayList<>();
    scheduler.scheduleAtFixedRate(
        () -> {
          observed.add(clock.instant());
          clock.advance(Duration.ofMillis(500));
        },
        1,
        1,
        TimeUnit.SECONDS);

    scheduler.advance(Duration.ofSeconds(3));

    assertEquals(
        List.of(
            TestClock.DEFAULT_START.plusSeconds(1),
            TestClock.DEFAULT_START.plusSeconds(2),
            TestClock.DEFAULT_START.plusSeconds(3)),
        observed,
        "fixed rate keeps the original cadence regardless of how long each tick took");
  }

  @Test
  void a_cancelled_periodic_task_stops_firing(TestClock clock) {
    TestScheduler scheduler = new TestScheduler(clock);
    AtomicInteger runs = new AtomicInteger();
    ScheduledFuture<?> handle =
        scheduler.scheduleAtFixedRate(runs::incrementAndGet, 1, 1, TimeUnit.SECONDS);

    scheduler.advance(Duration.ofSeconds(2));
    assertEquals(2, runs.get());

    handle.cancel(true);
    scheduler.advance(Duration.ofSeconds(10));
    assertEquals(2, runs.get(), "no further ticks after cancellation");
    assertEquals(0, scheduler.pendingTaskCount(), "and the cancelled task is not left pending");
  }

  @Test
  void tasks_due_at_the_same_instant_run_in_submission_order(TestClock clock) {
    TestScheduler scheduler = new TestScheduler(clock);
    List<String> order = new ArrayList<>();
    scheduler.schedule(() -> order.add("first"), 1, TimeUnit.SECONDS);
    scheduler.schedule(() -> order.add("second"), 1, TimeUnit.SECONDS);
    scheduler.schedule(() -> order.add("third"), 1, TimeUnit.SECONDS);

    scheduler.advance(Duration.ofSeconds(1));

    assertEquals(List.of("first", "second", "third"), order);
  }

  @Test
  void the_clock_ends_the_advance_at_the_requested_target_even_with_no_tasks(TestClock clock) {
    TestScheduler scheduler = new TestScheduler(clock);
    scheduler.advance(Duration.ofMinutes(5));
    assertEquals(TestClock.DEFAULT_START.plusSeconds(300), clock.instant());
  }

  @Test
  void submitted_work_waits_for_a_drain_rather_than_running_on_submission(TestClock clock) {
    TestScheduler scheduler = new TestScheduler(clock);
    AtomicInteger runs = new AtomicInteger();
    scheduler.execute(runs::incrementAndGet);
    assertEquals(0, runs.get(), "nothing runs until the test says so");

    assertEquals(1, scheduler.runUntilIdle());
    assertEquals(1, runs.get());
  }

  @Test
  void a_submitted_callables_result_is_readable_after_a_drain(TestClock clock) {
    TestScheduler scheduler = new TestScheduler(clock);
    Future<String> future = scheduler.submit(() -> "computed");
    scheduler.runUntilIdle();
    assertEquals("computed", TestScheduler.resultOf(future));
  }

  @Test
  void pending_due_times_report_the_schedule_without_running_anything(TestClock clock) {
    TestScheduler scheduler = new TestScheduler(clock);
    scheduler.schedule(() -> {}, 30, TimeUnit.SECONDS);
    scheduler.schedule(() -> {}, 10, TimeUnit.SECONDS);

    assertEquals(
        List.of(TestClock.DEFAULT_START.plusSeconds(10), TestClock.DEFAULT_START.plusSeconds(30)),
        scheduler.pendingDueTimes(),
        "earliest first, regardless of submission order");
  }

  @Test
  void advancing_backwards_is_rejected(TestClock clock) {
    TestScheduler scheduler = new TestScheduler(clock);
    assertThrows(IllegalArgumentException.class, () -> scheduler.advance(Duration.ofSeconds(-1)));
  }

  @Test
  void shutdown_now_discards_pending_work(TestClock clock) {
    TestScheduler scheduler = new TestScheduler(clock);
    AtomicInteger runs = new AtomicInteger();
    scheduler.scheduleAtFixedRate(runs::incrementAndGet, 1, 1, TimeUnit.SECONDS);

    assertFalse(scheduler.isShutdown());
    scheduler.shutdownNow();

    assertTrue(scheduler.isShutdown());
    assertTrue(scheduler.isTerminated());
    scheduler.advance(Duration.ofMinutes(1));
    assertEquals(0, runs.get());
  }

  @Test
  void unsupported_bulk_operations_fail_loudly_rather_than_pretending(TestClock clock) {
    TestScheduler scheduler = new TestScheduler(clock);
    UnsupportedOperationException thrown =
        assertThrows(
            UnsupportedOperationException.class, () -> scheduler.invokeAll(List.of(() -> "a")));
    assertTrue(thrown.getMessage().contains("TestScheduler"));
  }
}
