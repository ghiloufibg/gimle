package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ResourceSpec;
import org.junit.jupiter.api.Test;

class CapacityTrackerTest {

  private static final ResourceSpec SMALL = new ResourceSpec("100Mi", "500m");

  private static CapacityTracker tracker() {
    return new CapacityTracker(1_000_000_000L, 4000L);
  }

  @Test
  void try_assign_succeeds_within_capacity_and_is_reflected_in_the_snapshot() {
    CapacityTracker tracker = tracker();

    assertTrue(tracker.tryAssign("orders-service#0", SMALL));

    CapacityTracker.Snapshot snapshot = tracker.snapshot();
    assertEquals(SMALL.memoryBytes(), snapshot.assignedMemoryBytes());
    assertEquals(SMALL.cpuMillicores(), snapshot.assignedCpuMillicores());
  }

  @Test
  void try_assign_fails_once_it_would_exceed_total_capacity() {
    CapacityTracker tracker = new CapacityTracker(SMALL.memoryBytes(), SMALL.cpuMillicores());
    assertTrue(tracker.tryAssign("orders-service#0", SMALL));

    assertFalse(tracker.tryAssign("orders-service#1", SMALL));
  }

  @Test
  void try_assign_rejects_a_key_already_holding_a_reservation() {
    CapacityTracker tracker = tracker();
    tracker.tryAssign("orders-service#0", SMALL);

    assertThrows(IllegalStateException.class, () -> tracker.tryAssign("orders-service#0", SMALL));
  }

  @Test
  void release_frees_the_reservation_for_reuse() {
    CapacityTracker tracker = tracker();
    tracker.tryAssign("orders-service#0", SMALL);

    tracker.release("orders-service#0");

    assertTrue(tracker.tryAssign("orders-service#0", SMALL));
    assertEquals(SMALL.memoryBytes(), tracker.snapshot().assignedMemoryBytes());
  }

  @Test
  void rekey_moves_the_reservation_to_the_new_key_without_changing_total_usage() {
    CapacityTracker tracker = tracker();
    tracker.tryAssign("orders-service#5", SMALL);

    tracker.rekey("orders-service#5", "orders-service#1");

    // The old key is free again...
    assertTrue(tracker.tryAssign("orders-service#5", SMALL));
    // ...and the new key now holds the moved reservation, so re-assigning it throws rather than
    // silently overwriting -- proving the reservation actually moved, not just got dropped.
    assertThrows(IllegalStateException.class, () -> tracker.tryAssign("orders-service#1", SMALL));
  }

  @Test
  void rekey_is_a_noop_when_the_old_key_holds_no_reservation() {
    CapacityTracker tracker = tracker();

    tracker.rekey("orders-service#5", "orders-service#1");

    assertEquals(0, tracker.snapshot().assignedMemoryBytes());
    assertTrue(tracker.tryAssign("orders-service#1", SMALL));
  }
}
