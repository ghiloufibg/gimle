package com.gimle.ragnarok.fenrir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The seeded strike sequence is reproducible and honours pool weights and the gap range. */
final class ChaosScheduleTest {

  private static List<Pool> palette() {
    return List.of(Pools.workerKills(), Pools.storeBounces(), Pools.leaderBounces());
  }

  private static ChaosSchedule schedule(final long seed) {
    return new ChaosSchedule(seed, Duration.ofSeconds(10), Duration.ofSeconds(30), palette());
  }

  @Test
  void the_same_seed_draws_the_same_gap_and_pool_sequence() {
    final ChaosSchedule a = schedule(1234);
    final ChaosSchedule b = schedule(1234);
    for (int i = 0; i < 50; i++) {
      assertEquals(a.nextGapMillis(), b.nextGapMillis(), "gap " + i);
      assertEquals(a.nextPool().kind(), b.nextPool().kind(), "pool " + i);
    }
  }

  @Test
  void different_seeds_diverge() {
    final List<FaultKind> first = drawKinds(schedule(1), 40);
    final List<FaultKind> second = drawKinds(schedule(2), 40);
    assertTrue(!first.equals(second), "distinct seeds should not draw an identical sequence");
  }

  @Test
  void gaps_stay_within_the_declared_range() {
    final ChaosSchedule schedule = schedule(99);
    for (int i = 0; i < 100; i++) {
      final long gap = schedule.nextGapMillis();
      assertTrue(gap >= 10_000 && gap < 30_000, "gap out of range: " + gap);
    }
  }

  @Test
  void a_fixed_gap_is_returned_verbatim() {
    final ChaosSchedule schedule =
        new ChaosSchedule(
            5, Duration.ofSeconds(15), Duration.ofSeconds(15), List.of(Pools.storeBounces()));
    assertEquals(15_000, schedule.nextGapMillis());
  }

  @Test
  void weight_biases_selection_toward_the_heavier_pool() {
    final ChaosSchedule schedule =
        new ChaosSchedule(
            7,
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            List.of(Pools.workerKills().weight(9), Pools.storeBounces().weight(1)));
    int workerKills = 0;
    final int draws = 1000;
    for (int i = 0; i < draws; i++) {
      if (schedule.nextPool().kind() == FaultKind.WORKER_KILL) {
        workerKills++;
      }
    }
    // Weight 9:1 -- the heavy pool should dominate by a wide, stable margin.
    assertTrue(
        workerKills > draws * 3 / 4, "expected worker kills to dominate, got " + workerKills);
  }

  private static List<FaultKind> drawKinds(final ChaosSchedule schedule, final int count) {
    final List<FaultKind> kinds = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      schedule.nextGapMillis();
      kinds.add(schedule.nextPool().kind());
    }
    return kinds;
  }
}
