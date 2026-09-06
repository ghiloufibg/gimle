package com.gimle.controlplane.reconcile;

import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.StoreReader;
import java.time.Clock;
import java.time.Duration;

/**
 * Bounds the request-idempotency receipt table: every tick, removes the receipts older than the
 * retention window, so the table is sized by the recent rate of keyed writes rather than by the
 * cluster's whole history.
 *
 * <p>Level-triggered like every other reconciler here, and stateless with it: the cutoff is
 * recomputed from the current time on each pass and one sweep removes every receipt past it, so
 * this converges from any starting state -- a table left untouched for a week is emptied by the
 * first pass after it, exactly as it would have been by a hundred missed passes. It runs on the
 * existing reconcile tick, under the same reconciler-leader lease, rather than on a thread of its
 * own: one sweep per tick is far more often than the fifteen-minute window needs.
 *
 * <p>A pass that finds nothing to remove proposes nothing at all. A sweep is a replicated log entry
 * like any other write, and proposing one unconditionally would append an entry that changes no
 * state on every tick forever.
 */
public final class RequestOutcomeSweeper {

  private final StoreReader store;
  private final MutationSink mutations;
  private final Duration retention;
  private final Clock clock;

  public RequestOutcomeSweeper(
      StoreReader store, MutationSink mutations, Duration retention, Clock clock) {
    this.store = store;
    this.mutations = mutations;
    this.retention = retention;
    this.clock = clock;
  }

  public void reconcileOnce() {
    long cutoffEpochMilli = clock.instant().minus(retention).toEpochMilli();
    if (store.countRequestOutcomesBefore(cutoffEpochMilli) == 0) {
      return;
    }
    mutations.propose(new StateMutation.SweepRequestOutcomes(cutoffEpochMilli));
  }
}
