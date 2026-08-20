package com.example.frauddetection;

import java.io.Serializable;

/**
 * A synthetic payment transaction. {@code implements Serializable} is required here, not
 * optional: fraud-scorer's {@code score} return crosses the wire via plain Java serialization
 * (see {@code ObjectMarshalling} in gimle-fabric), and a record gets no serialization support
 * unless it says so itself.
 */
public record Transaction(String transactionId, String accountId, String merchantId, double amount)
    implements Serializable {}
