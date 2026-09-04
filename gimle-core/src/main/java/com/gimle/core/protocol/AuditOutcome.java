package com.gimle.core.protocol;

/**
 * What actually happened to the write or delete an {@link AuditEvent} records, as distinct from
 * {@link AuditEvent#allowed()} -- whether the authorization check itself passed. A request can be
 * authorized and still never take effect: a tenant-quota or LimitRange violation, a name/kind
 * mismatch, or any other admission-time rejection all happen strictly after authorization, so
 * {@code allowed() == true} alone was never proof the write actually landed. {@link #REJECTED}
 * covers both cases -- an authorization denial and an authorized-but-subsequently-rejected write --
 * since neither one changed anything; {@link #APPLIED} is reserved for a write or delete that
 * genuinely took effect. {@link #DESTROYED} is the one outcome distinct from an ordinary applied
 * write or delete: a hard secret destroy is irreversible, unlike every other {@code APPLIED}
 * mutation this trail records (a soft delete can be undeleted, an overwritten config value can be
 * written again), so it earns its own outcome rather than being indistinguishable from a soft
 * delete's own {@code APPLIED} entry.
 */
public enum AuditOutcome {
  APPLIED,
  REJECTED,
  DESTROYED
}
