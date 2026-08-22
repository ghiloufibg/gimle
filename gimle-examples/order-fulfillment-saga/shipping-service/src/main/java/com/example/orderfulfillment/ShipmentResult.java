package com.example.orderfulfillment;

import java.io.Serializable;

/** {@code implements Serializable} is required -- this crosses the wire via plain Java
 *  serialization (see {@code ObjectMarshalling} in gimle-fabric). */
public record ShipmentResult(boolean success, String trackingId, String reason)
    implements Serializable {}
