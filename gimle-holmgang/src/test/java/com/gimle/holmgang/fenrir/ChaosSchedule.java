package com.gimle.holmgang.fenrir;

import java.time.Duration;
import java.util.List;
import java.util.Random;

/**
 * The seeded source of a soak's strike <em>sequence</em>: the inter-fault gap and the pool chosen
 * for each strike, drawn in a fixed order from one seed so a run replays. It deliberately owns a
 * different RNG from victim selection -- which store or worker gets hit depends on live cluster
 * state, so keeping that draw separate stops it from desyncing the reproducible gap/pool sequence.
 */
final class ChaosSchedule {

  private final Random rng;
  private final long gapMinMillis;
  private final long gapMaxMillis;
  private final List<Pool> pools;
  private final int totalWeight;

  ChaosSchedule(
      final long seed, final Duration gapMin, final Duration gapMax, final List<Pool> pools) {
    this.rng = new Random(seed);
    this.gapMinMillis = gapMin.toMillis();
    this.gapMaxMillis = gapMax.toMillis();
    this.pools = pools;
    int weight = 0;
    for (final Pool pool : pools) {
      weight += pool.weight();
    }
    this.totalWeight = weight;
  }

  long nextGapMillis() {
    return gapMinMillis == gapMaxMillis
        ? gapMinMillis
        : gapMinMillis + (long) (rng.nextDouble() * (gapMaxMillis - gapMinMillis));
  }

  Pool nextPool() {
    int roll = rng.nextInt(totalWeight);
    for (final Pool pool : pools) {
      roll -= pool.weight();
      if (roll < 0) {
        return pool;
      }
    }
    // Unreachable: the weights sum to totalWeight and roll started below it.
    return pools.get(pools.size() - 1);
  }
}
