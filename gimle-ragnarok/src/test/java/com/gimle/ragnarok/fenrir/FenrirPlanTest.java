package com.gimle.ragnarok.fenrir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.ragnarok.RagnarokException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Validation and seed-override behaviour of the immutable chaos plan. */
final class FenrirPlanTest {

  @Test
  void a_minimal_plan_builds() {
    final FenrirPlan plan =
        FenrirPlan.seeded(42).soakFor(Duration.ofSeconds(60)).pool(Pools.storeBounces()).build();
    assertEquals(42, plan.seed());
    assertEquals(1, plan.pools().size());
    assertTrue(plan.convergeBetweenFaults());
  }

  @Test
  void a_plan_without_a_soak_window_is_rejected() {
    final RagnarokException e =
        assertThrows(
            RagnarokException.class, () -> FenrirPlan.seeded(1).pool(Pools.storeBounces()).build());
    assertTrue(e.getMessage().contains("soak"));
  }

  @Test
  void a_plan_without_pools_is_rejected() {
    assertThrows(
        RagnarokException.class,
        () -> FenrirPlan.seeded(1).soakFor(Duration.ofSeconds(10)).build());
  }

  @Test
  void a_worker_kill_pool_without_eligible_deployments_is_rejected() {
    final RagnarokException e =
        assertThrows(
            RagnarokException.class,
            () ->
                FenrirPlan.seeded(1)
                    .soakFor(Duration.ofSeconds(10))
                    .pool(Pools.workerKills())
                    .build());
    assertTrue(e.getMessage().contains("eligible deployment"));
  }

  @Test
  void a_control_plane_bounce_dwell_at_or_above_ten_seconds_is_rejected() {
    final RagnarokException e =
        assertThrows(
            RagnarokException.class,
            () ->
                FenrirPlan.seeded(1)
                    .soakFor(Duration.ofSeconds(30))
                    .pool(Pools.controlPlaneBounces().dwell(Duration.ofSeconds(10)))
                    .build());
    assertTrue(e.getMessage().contains("node-dark"));
  }

  @Test
  void a_control_plane_bounce_dwell_under_ten_seconds_is_accepted() {
    FenrirPlan.seeded(1)
        .soakFor(Duration.ofSeconds(30))
        .pool(Pools.controlPlaneBounces().dwell(Duration.ofSeconds(9)))
        .build();
  }

  @Test
  void an_inverted_gap_range_is_rejected() {
    assertThrows(
        RagnarokException.class,
        () ->
            FenrirPlan.seeded(1)
                .soakFor(Duration.ofSeconds(30))
                .strikeEvery(Duration.ofSeconds(20), Duration.ofSeconds(10))
                .pool(Pools.storeBounces())
                .build());
  }

  @Test
  void a_duplicate_pool_kind_is_rejected() {
    assertThrows(
        RagnarokException.class,
        () ->
            FenrirPlan.seeded(1)
                .soakFor(Duration.ofSeconds(30))
                .pool(Pools.storeBounces())
                .pool(Pools.storeBounces().weight(2))
                .build());
  }

  @Test
  void a_zero_weight_pool_is_rejected() {
    assertThrows(RagnarokException.class, () -> Pools.storeBounces().weight(0));
  }

  @Test
  void the_seed_property_overrides_the_builder_seed() {
    System.setProperty(FenrirPlan.SEED_PROPERTY, "777");
    try {
      final FenrirPlan plan =
          FenrirPlan.seeded(42).soakFor(Duration.ofSeconds(10)).pool(Pools.storeBounces()).build();
      assertEquals(777, plan.seed());
    } finally {
      System.clearProperty(FenrirPlan.SEED_PROPERTY);
    }
  }
}
