package com.gimle.testkit.heimdall;

import java.util.OptionalInt;

/**
 * Conditions over one instance's own log, satisfied by the platform's real live-tailing surface: a
 * chunked-NDJSON {@code /logs/instances/...?follow=true} stream proxied through a control-plane
 * replica -- genuine push to the harness, one stream per condition, reconnected transparently while
 * the instance's worker restarts.
 */
public final class LogConditions {

  private final Heimdall heimdall;
  private final OptionalInt replicaFilter;
  private final String deploymentName;
  private final int instanceIndex;
  private final String category;

  LogConditions(
      final Heimdall heimdall,
      final OptionalInt replicaFilter,
      final String deploymentName,
      final int instanceIndex,
      final String category) {
    this.heimdall = heimdall;
    this.replicaFilter = replicaFilter;
    this.deploymentName = deploymentName;
    this.instanceIndex = instanceIndex;
    this.category = category;
  }

  public HeimdallCondition contains(final String text) {
    return heimdall.registerLogCondition(
        "instance "
            + deploymentName
            + "#"
            + instanceIndex
            + "'s "
            + category
            + " log contains \""
            + text
            + "\"",
        replicaFilter,
        deploymentName,
        instanceIndex,
        category,
        text);
  }
}
