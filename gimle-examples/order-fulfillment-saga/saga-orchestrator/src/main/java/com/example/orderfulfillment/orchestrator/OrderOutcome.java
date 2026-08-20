package com.example.orderfulfillment.orchestrator;

/**
 * How one order's saga ended:
 *
 * <ul>
 *   <li>{@link #FULFILLED} -- reserve, charge, and ship all succeeded.
 *   <li>{@link #COMPENSATED} -- charge or shipping failed as a legitimate business outcome after
 *       a real reservation (and possibly a real charge) existed, and the saga rolled it back
 *       (release, and refund if a charge existed).
 *   <li>{@link #REJECTED} -- the reservation itself failed (insufficient stock); nothing was ever
 *       reserved, so no compensation was needed.
 *   <li>{@link #INFRA_FAILED} -- a step's own fabric call never got a response after every retry
 *       (not a business decision, a genuine infrastructure failure) -- counted against {@code
 *       saga.maxFailureRatio}, the same partial-failure tolerance
 *       gimle-examples/mapreduce-wordcount's own coordinator establishes.
 * </ul>
 */
enum OrderOutcome {
  FULFILLED,
  COMPENSATED,
  REJECTED,
  INFRA_FAILED
}
