package com.example.frauddetection;

import java.io.Serializable;

/** {@code implements Serializable} is required -- see {@link Transaction}'s own javadoc for why. */
public record AlertPayload(String transactionId, String accountId, int riskScore)
    implements Serializable {}
