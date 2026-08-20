package com.example.orderfulfillment;

/**
 * The fabric service contract shared by shipping-service and saga-orchestrator. Each module
 * bundles its own literal copy of this interface (same fully-qualified name, same signature)
 * rather than depending on a shared compile-time API jar -- see
 * {@code InventoryReservationService}'s own javadoc for why.
 */
public interface ShippingService {

  /** Ships {@code orderId}. Never throws for a carrier rejection -- that's a legitimate business
   *  outcome, reflected in the result; the caller's own saga is what turns a failure here into a
   *  full refund-and-release compensation. */
  ShipmentResult ship(String orderId);
}
