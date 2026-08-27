package com.gimle.ragnarok.fenrir;

import com.gimle.ragnarok.RagnarokException;
import java.time.Duration;

/**
 * One class of victim in a {@link FenrirPlan}: a fault kind, a relative selection weight (the
 * kube-monkey MTBF equivalent -- how often this class is chosen relative to the others), and a
 * dwell for the bounce kinds (how long the process stays dead before the harness restarts it).
 * Built fluently via {@link Pools} and refined with the overloaded {@code weight}/{@code dwell}
 * setters, e.g. {@code Pools.storeBounces().dwell(ofSeconds(5)).weight(2)}.
 *
 * <p>The setters share their names with the record accessors on purpose -- {@code weight()} reads,
 * {@code weight(int)} returns a refined copy -- so a plan reads as one fluent chain.
 */
public record Pool(FaultKind kind, int weight, Duration dwell) {

  public Pool {
    if (kind == null) {
      throw new RagnarokException("a pool must name a fault kind");
    }
    if (weight < 1) {
      throw new RagnarokException("pool weight must be >= 1, got " + weight + " for " + kind);
    }
    if (dwell == null || dwell.isNegative()) {
      throw new RagnarokException("pool dwell must be a non-negative duration for " + kind);
    }
  }

  /** A copy of this pool with a different selection weight. */
  public Pool weight(final int newWeight) {
    return new Pool(kind, newWeight, dwell);
  }

  /**
   * A copy of this pool with a different bounce dwell; ignored by {@link FaultKind#WORKER_KILL}.
   */
  public Pool dwell(final Duration newDwell) {
    return new Pool(kind, weight, newDwell);
  }

  /** Reads more naturally than {@code dwell} for a link cut: how long the link stays severed. */
  public Pool healAfter(final Duration newDwell) {
    return new Pool(kind, weight, newDwell);
  }
}
