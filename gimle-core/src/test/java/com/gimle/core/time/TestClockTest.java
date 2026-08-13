package com.gimle.core.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * {@link TestClock} is test-support code, but it is shipped in {@code gimle-core}'s test-jar and
 * every module's time-dependent tests now depend on it being correct -- a silently broken advance
 * or a {@code withZone} that forks the timeline would produce confidently wrong results everywhere
 * downstream, so it carries its own tests like any other shared component.
 */
class TestClockTest {

  @Test
  void starts_at_a_fixed_documented_instant_so_failures_reproduce_identically() {
    assertEquals(TestClock.DEFAULT_START, new TestClock().instant());
    assertEquals(ZoneOffset.UTC, new TestClock().getZone());
  }

  @Test
  void advancing_moves_the_instant_forward_by_exactly_the_requested_amount() {
    TestClock clock = new TestClock();
    clock.advance(Duration.ofSeconds(30));
    assertEquals(TestClock.DEFAULT_START.plusSeconds(30), clock.instant());

    clock.advance(Duration.ofMillis(1500));
    assertEquals(TestClock.DEFAULT_START.plusSeconds(31).plusMillis(500), clock.instant());
  }

  @Test
  void advancing_by_zero_is_allowed_and_changes_nothing() {
    TestClock clock = new TestClock();
    clock.advance(Duration.ZERO);
    assertEquals(TestClock.DEFAULT_START, clock.instant());
  }

  @Test
  void advance_returns_this_so_it_can_be_chained_into_the_call_it_sets_up() {
    TestClock clock = new TestClock();
    assertSame(clock, clock.advance(Duration.ofSeconds(1)));
  }

  @Test
  void advancing_backwards_is_rejected_as_a_test_bug() {
    TestClock clock = new TestClock();
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> clock.advance(Duration.ofSeconds(-1)));
    assertTrue(thrown.getMessage().contains("setTo"), "the message should point at the way out");
    assertEquals(TestClock.DEFAULT_START, clock.instant(), "a rejected advance changes nothing");
  }

  @Test
  void set_to_allows_the_deliberate_backwards_jump_that_advance_refuses() {
    TestClock clock = new TestClock();
    clock.advance(Duration.ofHours(1));
    clock.setTo(TestClock.DEFAULT_START);
    assertEquals(TestClock.DEFAULT_START, clock.instant());
  }

  @Test
  void starting_now_anchors_close_enough_to_real_time_to_compare_against_system_timestamps() {
    Instant before = Instant.now();
    TestClock clock = TestClock.startingNow();
    Instant after = Instant.now();

    assertTrue(
        !clock.instant().isBefore(before) && !clock.instant().isAfter(after),
        "an anchored clock should read between two real reads taken around its construction");
  }

  /**
   * The {@link Clock#withZone} contract: a zone-shifted view must observe the same instant source,
   * not snapshot it -- otherwise advancing one silently leaves the other behind.
   */
  @Test
  void a_zone_shifted_view_shares_this_clocks_timeline_rather_than_forking_it() {
    TestClock clock = new TestClock();
    Clock inTokyo = clock.withZone(ZoneId.of("Asia/Tokyo"));
    assertEquals(ZoneId.of("Asia/Tokyo"), inTokyo.getZone());
    assertNotEquals(clock.getZone(), inTokyo.getZone());

    clock.advance(Duration.ofMinutes(10));
    assertEquals(
        clock.instant(), inTokyo.instant(), "advancing the original must move the shifted view");
  }

  @Test
  void millis_agrees_with_instant() {
    TestClock clock = new TestClock();
    clock.advance(Duration.ofSeconds(42));
    assertEquals(clock.instant().toEpochMilli(), clock.millis());
  }

  /**
   * Production code under test routinely reads the clock from a thread other than the one advancing
   * it (a reconciler tick, a probe loop), so concurrent advances must not lose updates.
   */
  @Test
  void concurrent_advances_are_all_applied() throws Exception {
    TestClock clock = new TestClock();
    int threads = 8;
    int advancesPerThread = 500;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger failures = new AtomicInteger();

    List<Thread> workers =
        IntStream.range(0, threads)
            .mapToObj(
                i ->
                    Thread.ofVirtual()
                        .unstarted(
                            () -> {
                              try {
                                start.await();
                                for (int n = 0; n < advancesPerThread; n++) {
                                  clock.advance(Duration.ofMillis(1));
                                }
                              } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                failures.incrementAndGet();
                              } finally {
                                done.countDown();
                              }
                            }))
            .toList();
    workers.forEach(Thread::start);
    start.countDown();
    assertTrue(done.await(30, TimeUnit.SECONDS), "advancing threads should finish promptly");
    assertEquals(0, failures.get());

    assertEquals(
        TestClock.DEFAULT_START.plusMillis((long) threads * advancesPerThread),
        clock.instant(),
        "every concurrent advance should be applied exactly once, none lost to a race");
  }

  @Test
  void an_injected_test_clock_arrives_by_parameter_without_any_extend_with(TestClock injected) {
    assertEquals(TestClock.DEFAULT_START, injected.instant());
  }

  @Test
  void the_injected_clock_is_a_fresh_one_per_test_method(TestClock injected) {
    // Proves no leakage from the advance the previous test method might have done: this reads the
    // default start, not whatever another test left behind.
    assertEquals(TestClock.DEFAULT_START, injected.instant());
    injected.advance(Duration.ofDays(1));
  }
}
