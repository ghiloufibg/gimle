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
 * copied enum rather than a shared reference. {@code outcome} is a second, independent axis from
 * {@code allowed}: {@code allowed} is the authorization decision alone, {@code outcome} is what
 * actually happened to the request -- an authorized write can still end up {@link
 * AuditOutcome#REJECTED} by admission (a tenant quota, a LimitRange, a name/kind mismatch) after
 * authorization already said yes. See {@link AuditOutcome}'s own javadoc for why both fields need
 * to exist.
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
    AuditOutcome outcome,
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
    if (outcome == null) {
      throw new IllegalArgumentException("outcome must not be null");
    }
  }

  /**
   * Convenience for the majority of call sites that only ever know the authorization decision at
   * the point they record an event -- resource kinds with no separate admission stage of their own,
   * where {@code allowed() == true} really does mean the write took effect. {@code outcome}
   * defaults to {@link AuditOutcome#REJECTED} when {@code allowed} is {@code false} (a denial never
   * takes effect) and {@link AuditOutcome#APPLIED} when {@code allowed} is {@code true}. A resource
   * kind whose write can still be rejected after authorization passes (see {@code
   * com.gimle.controlplane.admission.AdmissionChain}) must use the canonical constructor with its
   * own genuine outcome instead of this one.
   */
  public AuditEvent(
      String id,
      String principal,
      Set<String> groups,
      String resourceKind,
      String verb,
      Optional<String> tenantId,
      Optional<String> targetId,
      boolean allowed,
      long occurredAtEpochMilli) {
    this(
        id,
        principal,
        groups,
        resourceKind,
        verb,
        tenantId,
        targetId,
        allowed,
        allowed ? AuditOutcome.APPLIED : AuditOutcome.REJECTED,
        occurredAtEpochMilli);
  }
}
