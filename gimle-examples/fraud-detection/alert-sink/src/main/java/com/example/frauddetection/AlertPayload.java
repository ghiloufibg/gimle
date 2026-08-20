package com.example.frauddetection;

import java.io.Serializable;

/** {@code implements Serializable} is required -- this crosses the wire via plain Java
 *  serialization (see {@code ObjectMarshalling} in gimle-fabric). */
public record AlertPayload(String transactionId, String accountId, int riskScore)
    implements Serializable {}
