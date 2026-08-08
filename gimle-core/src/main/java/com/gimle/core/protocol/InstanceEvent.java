package com.gimle.core.protocol;

import java.util.Optional;

/**
 * One durable, queryable entry in an instance's own lifecycle timeline -- distinct from {@code
 * ModuleStateChanged} (a fire-and-forget notification the agent doesn't retain) and from the
 * general audit-logging item still on the roadmap (this is per-instance timeline data, not a
 * cross-resource audit trail). {@code causeSummary} is populated only for a {@code
 * TRANSITION_FAILED} event, and deliberately holds an exception's class name plus message rather
 * than a full stack trace, to keep each event's persisted footprint small. {@code id} is generated
 * once at the point of occurrence (a worker, via {@link InstanceEventKind}'s owning transition) and
 * travels unchanged through every hop after that, giving callers a stable identity for pagination
 * independent of storage order.
 */
public record InstanceEvent(
    String id,
    String deploymentName,
    int instanceIndex,
    InstanceEventKind kind,
    String message,
    Optional<String> causeSummary,
    long occurredAtEpochMilli) {

  public InstanceEvent {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    if (deploymentName == null || deploymentName.isBlank()) {
      throw new IllegalArgumentException("deploymentName must not be blank");
    }
    if (instanceIndex < 0) {
      throw new IllegalArgumentException("instanceIndex must not be negative: " + instanceIndex);
    }
    if (kind == null) {
      throw new IllegalArgumentException("kind must not be null");
    }
    if (message == null) {
      throw new IllegalArgumentException("message must not be null");
    }
    causeSummary = causeSummary == null ? Optional.empty() : causeSummary;
  }

  public InstanceEvent(
      String id,
      String deploymentName,
      int instanceIndex,
      InstanceEventKind kind,
      String message,
      long occurredAtEpochMilli) {
    this(id, deploymentName, instanceIndex, kind, message, Optional.empty(), occurredAtEpochMilli);
  }
}
