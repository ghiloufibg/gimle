package com.gimle.core.protocol;

import java.util.Optional;
import java.util.Set;

/**
 * One durable, queryable entry in Gimlé's cross-resource audit trail: who ({@code principal}/
 * {@code groups}), did what ({@code resourceKind}/{@code verb}/{@code tenantId}/{@code targetId}),
 * and the outcome ({@code allowed}), both allowed and denied decisions alike -- a denial is exactly
 * as auditable as a grant. Where {@link InstanceEvent} is scoped to one instance's own lifecycle
 * timeline, this records a single authorization decision against any resource kind. {@code
 * resourceKind} and {@code verb} travel as plain {@code String}s rather than {@code
 * com.gimle.core.authz.ResourceKind}/{@code Verb} themselves, the same "gimle-core doesn't need a
 * second dependency on its own authz enums just to name them back" reasoning {@link
 * InstanceEvent#kind} already applies by carrying {@code com.gimle.module}'s lifecycle state as a
 * copied enum rather than a shared reference.
 */
public record AuditEvent(
    String id,
    String principal,
    Set<String> groups,
    String resourceKind,
    String verb,
    Optional<String> tenantId,
    Optional<String> targetId,
    boolean allowed,
    long occurredAtEpochMilli) {

  public AuditEvent {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    if (principal == null || principal.isBlank()) {
      throw new IllegalArgumentException("principal must not be blank");
    }
    groups = groups == null ? Set.of() : Set.copyOf(groups);
    if (resourceKind == null || resourceKind.isBlank()) {
      throw new IllegalArgumentException("resourceKind must not be blank");
    }
    if (verb == null || verb.isBlank()) {
      throw new IllegalArgumentException("verb must not be blank");
    }
    tenantId = tenantId == null ? Optional.empty() : tenantId;
    targetId = targetId == null ? Optional.empty() : targetId;
  }
}
