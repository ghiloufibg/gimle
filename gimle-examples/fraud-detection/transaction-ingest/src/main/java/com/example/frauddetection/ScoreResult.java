package com.example.frauddetection;

import java.io.Serializable;

/** {@code verdict} is one of {@code "LOW"}, {@code "MEDIUM"}, {@code "HIGH"} -- see FraudScorer. */
public record ScoreResult(int riskScore, String verdict) implements Serializable {}
