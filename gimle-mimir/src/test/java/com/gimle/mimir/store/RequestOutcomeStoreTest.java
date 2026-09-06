package com.gimle.mimir.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The request-idempotency receipt table's own store-level behaviour: durability across a
 * snapshot/restore, the retention sweep's cutoff, and first-write-wins on a repeated id.
 */
class RequestOutcomeStoreTest {

  private static RequestOutcomeRecord record(String requestId, long recordedAt) {
    return new RequestOutcomeRecord(requestId, "alice", 200, "{\"revision\":4}", recordedAt);
  }

  @Test
  void a_recorded_outcome_survives_a_snapshot_and_restore_round_trip() {
    StateStore source = new StateStore();
    source.putRequestOutcome(record("req-00000001", 1_000L));

    StateStore restored = new StateStore();
    restored.restoreFromSnapshot(source.snapshot());

    Optional<RequestOutcomeRecord> found = restored.getRequestOutcome("req-00000001");
    assertTrue(found.isPresent(), "a receipt lost to a snapshot install would silently re-execute");
    assertEquals("alice", found.get().principalName());
    assertEquals(200, found.get().statusCode());
    assertEquals("{\"revision\":4}", found.get().responseBody());
    assertEquals(1_000L, found.get().recordedAtEpochMilli());
  }

  @Test
  void the_sweep_removes_an_expired_receipt_but_keeps_a_fresh_one() {
    StateStore store = new StateStore();
    store.putRequestOutcome(record("req-expired01", 1_000L));
    store.putRequestOutcome(record("req-fresh0001", 5_000L));

    assertEquals(1, store.countRequestOutcomesBefore(4_000L));
    store.sweepRequestOutcomesBefore(4_000L);

    assertTrue(store.getRequestOutcome("req-expired01").isEmpty());
    assertTrue(store.getRequestOutcome("req-fresh0001").isPresent());
    assertEquals(0, store.countRequestOutcomesBefore(4_000L));
  }

  @Test
  void a_receipt_recorded_exactly_at_the_cutoff_is_kept() {
    StateStore store = new StateStore();
    store.putRequestOutcome(record("req-boundary1", 4_000L));

    store.sweepRequestOutcomesBefore(4_000L);

    assertTrue(store.getRequestOutcome("req-boundary1").isPresent());
  }

  @Test
  void the_first_recorded_outcome_for_an_id_wins_over_a_later_one() {
    StateStore store = new StateStore();
    store.putRequestOutcome(record("req-00000002", 1_000L));
    store.putRequestOutcome(
        new RequestOutcomeRecord("req-00000002", "mallory", 500, "later", 2_000L));

    RequestOutcomeRecord found = store.getRequestOutcome("req-00000002").orElseThrow();
    assertEquals("alice", found.principalName());
    assertEquals(200, found.statusCode());
  }

  @Test
  void an_unknown_request_id_reads_back_as_absent() {
    StateStore store = new StateStore();
    assertFalse(store.getRequestOutcome("req-never-seen").isPresent());
  }
}
