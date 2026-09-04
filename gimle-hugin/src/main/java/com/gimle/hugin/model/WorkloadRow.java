package com.gimle.hugin.model;

import java.util.Optional;

/**
 * One workload's own status, as distinct from the status of the instances it managed to place. A
 * workload asking for four replicas with two placed has two instance rows and no third and fourth
 * row to carry the shortfall -- so without this the view shows two healthy instances and says
 * nothing at all about the two that are missing, which is the one thing an operator watching a
 * change settle most needs to know.
 *
 * <p>The shortfall is arithmetic the control plane does itself ({@code replicas} against the
 * assignments it actually made); the quota and limit-range verdicts are its own, taken against the
 * owning tenant's policy. A workload can be fully placed and still violating, which is why those
 * are separate fields rather than one status word.
 */
public record WorkloadRow(
    WorkloadKind kind,
    Optional<String> tenantId,
    String name,
    int desiredReplicas,
    int placedCount,
    int unplacedCount,
    boolean quotaViolating,
    boolean limitRangeViolating,
    Optional<String> limitRangeViolationReason) {

  public WorkloadRow {
    if (tenantId == null || limitRangeViolationReason == null) {
      throw new IllegalArgumentException("optional fields must not be null; use Optional.empty()");
    }
    if (kind == null) {
      throw new IllegalArgumentException("kind must not be null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
  }

  /** Whether this workload is running what it was asked to run, within its tenant's policy. */
  public boolean settled() {
    return unplacedCount <= 0 && !quotaViolating && !limitRangeViolating;
  }

  /**
   * What to say about an unsettled workload, in the order an operator would act on it: the
   * shortfall first, because it is what they came to look at, then whichever policy verdict
   * explains it. The control plane serves a reason for a limit-range violation and none for a quota
   * one, so this reports each at whatever precision it actually has rather than inventing wording
   * for the other.
   */
  public String problem() {
    StringBuilder text = new StringBuilder();
    if (unplacedCount > 0) {
      text.append(placedCount).append(" of ").append(desiredReplicas).append(" placed");
    }
    if (quotaViolating) {
      append(text, "quota exceeded");
    }
    if (limitRangeViolating) {
      append(text, "limit range: " + limitRangeViolationReason.orElse("workload rejected"));
    }
    return text.toString();
  }

  private static void append(final StringBuilder text, final String clause) {
    if (!text.isEmpty()) {
      text.append("  ");
    }
    text.append(clause);
  }
}
