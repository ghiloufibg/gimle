package com.example.orderfulfillment;

/**
 * The fabric service contract shared by inventory-reservation and saga-orchestrator. Each module
 * bundles its own literal copy of this interface (same fully-qualified name, same signature)
 * rather than depending on a shared compile-time API jar -- the fabric's service catalog resolves
 * lookups by interface name and dispatches through a proxy built from the caller's own {@code
 * Class} object, so two independently compiled, structurally identical copies interoperate
 * correctly across the wire. The same "structural contract, not a shared jar" demonstration
 * gimle-examples/greeter-provider and greeter-consumer already establish.
 */
public interface InventoryReservationService {

  /** Reserves {@code quantity} units of {@code sku} for {@code orderId}. Never throws for
   *  insufficient stock -- that's a legitimate business outcome, reflected in the result, not an
   *  infrastructure failure. */
  ReservationResult reserve(String orderId, String sku, int quantity);

  /** Releases a previously successful reservation -- the compensating action for this step. A
   *  no-op (logged, not thrown) if {@code reservationId} is unknown, e.g. a double release. */
  void release(String reservationId);
}
