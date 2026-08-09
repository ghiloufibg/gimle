package com.gimle.fabric.cluster;

import java.time.Duration;

/**
 * SWIM protocol-period/suspicion tunables. {@link #defaults()} mirrors the reference SWIM paper's
 * own starting points -- protocol period ~1s, suspicion timeout ~3 periods -- pending confirmation
 * against real cross-machine latency once it's observable.
 */
public record GossipConfig(
    Duration protocolPeriod,
    Duration pingTimeout,
    Duration suspicionTimeout,
    int indirectFanout,
    int piggybackCount,
    Duration antiEntropyInterval) {

  public GossipConfig {
    if (protocolPeriod == null || protocolPeriod.isNegative() || protocolPeriod.isZero()) {
      throw new IllegalArgumentException("protocolPeriod must be positive");
    }
    if (pingTimeout == null || pingTimeout.isNegative() || pingTimeout.isZero()) {
      throw new IllegalArgumentException("pingTimeout must be positive");
    }
    if (suspicionTimeout == null || suspicionTimeout.isNegative() || suspicionTimeout.isZero()) {
      throw new IllegalArgumentException("suspicionTimeout must be positive");
    }
    if (indirectFanout < 0) {
      throw new IllegalArgumentException("indirectFanout must not be negative");
    }
    if (piggybackCount < 1) {
      throw new IllegalArgumentException("piggybackCount must be at least 1");
    }
    if (antiEntropyInterval == null
        || antiEntropyInterval.isNegative()
        || antiEntropyInterval.isZero()) {
      throw new IllegalArgumentException("antiEntropyInterval must be positive");
    }
  }

  public static GossipConfig defaults() {
    return new GossipConfig(
        Duration.ofSeconds(1),
        Duration.ofMillis(500),
        Duration.ofSeconds(3),
        3,
        6,
        Duration.ofSeconds(30));
  }
}
