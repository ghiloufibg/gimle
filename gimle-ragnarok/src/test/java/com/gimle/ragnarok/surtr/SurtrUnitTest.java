package com.gimle.ragnarok.surtr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.ragnarok.surtr.Measurements.ApiCall;
import com.gimle.ragnarok.surtr.Measurements.ApiStat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure-logic pieces: percentiles, templating, API-stat aggregation, and rate limiting. */
final class SurtrUnitTest {

  @Test
  void percentiles_use_nearest_rank_over_the_sample() {
    final Percentiles p =
        Percentiles.of(List.of(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L));
    assertEquals(10, p.count());
    assertEquals(50, p.p50());
    assertEquals(100, p.p95());
    assertEquals(100, p.p99());
    assertEquals(100, p.max());
  }

  @Test
  void empty_percentiles_are_all_zero() {
    final Percentiles p = Percentiles.of(List.of());
    assertEquals(0, p.count());
    assertEquals(0, p.max());
  }

  @Test
  void templating_expands_iteration_and_job_placeholders() {
    final ObjectTemplate template =
        new ObjectTemplate(
            ObjectTemplate.Kind.DEPLOYMENT,
            "{{job}}-greeter-{{iteration}}",
            java.util.Optional.of("tenant-{{iteration}}"),
            java.util.Optional.of("greeter-provider"),
            1);
    final ObjectTemplate expanded = template.expand("fill", 7);
    assertEquals("fill-greeter-7", expanded.name());
    assertEquals("tenant-7", expanded.tenant().orElseThrow());
  }

  @Test
  void api_stats_group_by_endpoint_and_count_errors() {
    final List<ApiStat> stats =
        Measurements.aggregate(
            List.of(
                new ApiCall("/deployments", "PUT", 10, 200),
                new ApiCall("/deployments", "PUT", 30, 500),
                new ApiCall("/tenants", "PUT", 5, 200)));
    assertEquals(2, stats.size());
    final ApiStat deployments =
        stats.stream().filter(s -> s.endpoint().equals("/deployments")).findFirst().orElseThrow();
    assertEquals(2, deployments.count());
    assertEquals(1, deployments.errorCount());
  }

  @Test
  void the_token_bucket_paces_submissions_to_the_target_rate() {
    // 20 qps, burst 5: the first 5 are immediate, the next 5 must take at least ~4 refill periods
    // (0.25s at 20/s). A generous lower bound keeps this off wall-clock flakiness.
    final TokenBucket bucket = new TokenBucket(20, 5);
    final long start = System.nanoTime();
    for (int i = 0; i < 10; i++) {
      bucket.acquire();
    }
    final long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
    assertTrue(elapsedMillis >= 150, "expected pacing to take >= 150ms, took " + elapsedMillis);
  }
}
