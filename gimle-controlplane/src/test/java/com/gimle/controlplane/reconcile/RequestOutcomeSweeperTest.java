package com.gimle.controlplane.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.RequestOutcomeRecord;
import com.gimle.mimir.store.StateStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The retention sweep's convergence and its restraint about proposing when nothing is expired. */
class RequestOutcomeSweeperTest {

  private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
  private static final Duration RETENTION = Duration.ofMinutes(15);

  private final StateStore store = new StateStore();
  private final List<StateMutation> proposed = new ArrayList<>();

  private final MutationSink recordingSink =
      mutation -> {
        proposed.add(mutation);
        return mutation.applyTo(store);
      };

  private void record(String requestId, Instant recordedAt) {
    store.putRequestOutcome(
        new RequestOutcomeRecord(requestId, "alice", 200, "ok", recordedAt.toEpochMilli()));
  }

  private RequestOutcomeSweeper sweeper() {
    return new RequestOutcomeSweeper(
        store, recordingSink, RETENTION, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void the_sweep_removes_an_expired_receipt_but_not_a_fresh_one() {
    record("req-expired01", NOW.minus(Duration.ofMinutes(20)));
    record("req-fresh0001", NOW.minus(Duration.ofMinutes(5)));

    sweeper().reconcileOnce();

    assertTrue(store.getRequestOutcome("req-expired01").isEmpty());
    assertTrue(store.getRequestOutcome("req-fresh0001").isPresent());
  }

  @Test
  void a_tick_with_nothing_expired_proposes_no_mutation_at_all() {
    record("req-fresh0001", NOW.minus(Duration.ofMinutes(5)));

    sweeper().reconcileOnce();

    assertEquals(List.of(), proposed);
  }

  /**
   * Level-triggered: the sweep's own outcome depends only on what the table currently holds, so a
   * table left untouched across arbitrarily many missed ticks is emptied by the very next one --
   * there is no per-receipt progress to accumulate.
   */
  @Test
  void a_single_tick_converges_a_table_of_receipts_that_all_expired_long_ago() {
    for (int i = 0; i < 25; i++) {
      record("req-old-%04d".formatted(i), NOW.minus(Duration.ofHours(6 + i)));
    }

    sweeper().reconcileOnce();

    assertEquals(0, store.countRequestOutcomesBefore(NOW.toEpochMilli()));
    assertEquals(1, proposed.size(), "one sweep entry, not one per expired receipt");
  }
}
