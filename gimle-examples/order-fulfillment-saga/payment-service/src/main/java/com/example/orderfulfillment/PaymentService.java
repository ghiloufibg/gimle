package com.example.orderfulfillment;

/**
 * The fabric service contract shared by payment-service and saga-orchestrator. Each module
 * bundles its own literal copy of this interface (same fully-qualified name, same signature)
 * rather than depending on a shared compile-time API jar -- see
 * {@code InventoryReservationService}'s own javadoc for why.
 */
public interface PaymentService {

  /** Charges {@code amount} for {@code orderId}. Never throws for a declined card -- that's a
   *  legitimate business outcome, reflected in the result. */
  ChargeResult charge(String orderId, double amount);

  /** Refunds a previous charge -- the compensating action for this step. A no-op (logged, not
   *  thrown) if {@code chargeId} is unknown. */
  void refund(String chargeId);
}
