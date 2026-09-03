package com.gimle.hugin.model;

import java.time.Instant;
import java.util.Optional;

/**
 * One decision from the cluster-wide audit trail: who asked, what they asked of, and whether it was
 * allowed and applied.
 *
 * <p>This is an authorization record, not a lifecycle one. It answers "what has been done to this
 * cluster" -- which is a different question from "what did this instance do", the one the per-
 * instance timeline answers, and the only cluster-wide feed the control plane actually serves.
 */
public record ActivityRow(
    String principal,
    String resourceKind,
    String verb,
    Optional<String> tenantId,
    Optional<String> targetId,
    boolean allowed,
    String outcome,
    Instant occurredAt) {

  public ActivityRow {
    if (principal == null || principal.isBlank()) {
      throw new IllegalArgumentException("principal must not be blank");
    }
    if (tenantId == null || targetId == null) {
      throw new IllegalArgumentException("optional fields must not be null; use Optional.empty()");
    }
    if (occurredAt == null) {
      throw new IllegalArgumentException("occurredAt must not be null");
    }
  }

  /**
   * What the decision did, as one word: a refusal is the thing worth spotting in a scrolling list.
   */
  public String verdict() {
    if (!allowed) {
      return "DENIED";
    }
    return "REJECTED".equals(outcome) ? "REJECTED" : "APPLIED";
  }

  /** What the decision was about, as the table's own one-column reading. */
  public String target() {
    return resourceKind + (targetId.map(id -> " " + id).orElse(""));
  }
}
