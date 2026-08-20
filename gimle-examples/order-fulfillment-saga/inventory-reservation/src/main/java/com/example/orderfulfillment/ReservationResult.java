package com.example.orderfulfillment;

import java.io.Serializable;

/** {@code implements Serializable} is required -- this crosses the wire via plain Java
 *  serialization (see {@code ObjectMarshalling} in gimle-fabric). {@code reservationId}/{@code
 *  reason} are {@code null} on whichever side doesn't apply: a failed reservation has no id, a
 *  successful one needs no reason. */
public record ReservationResult(boolean success, String reservationId, String reason)
    implements Serializable {}
