package com.gimle.mimir.manifest;

import java.util.Optional;

/**
 * A declared threshold on one {@link DeploymentSpec}'s own observed signals -- the alerting
 * primitive the platform previously had none of: {@code gimle-muninn} stores metrics/logs/traces
 * passively, but nothing ever compared a stored value against an operator-declared threshold and
 * told anyone. An {@code AlertRuleSpec} names the {@code deploymentName} it watches, which of that
 * deployment's already-observed signals to watch ({@link Metric}, the same signal set {@code
 * AutoscalePolicy} already scores against), a {@link Comparator}/{@code threshold} pair, and a
 * {@code webhookUrl} to notify (a plain HTTP POST, the smallest notification mechanism that doesn't
 * assume any particular chat/paging vendor) -- deliberately not a general expression language: one
 * signal, one comparison, matching {@code NetworkPolicySpec}'s own "simplest shape that's actually
 * buildable against state the platform already has" precedent rather than a redesign.
 *
 * <p>{@code tenantId} is optional, the same convention every other tenant-scoping field in this
 * package uses ({@code ServiceSpec#tenantId()}): a rule watching an untenanted deployment is itself
 * untenanted.
 *
 * <p>{@code enabled} lets an operator silence a rule without deleting it (and losing its tuned
 * threshold) -- a disabled rule is never evaluated, so it never fires and never resolves.
 */
public record AlertRuleSpec(
    String name,
    Optional<String> tenantId,
    String deploymentName,
    Metric metric,
    Comparator comparator,
    double threshold,
    String webhookUrl,
    boolean enabled) {

  /** The same observed-signal set {@code AutoscalePolicy} already scores a deployment against. */
  public enum Metric {
    REQUEST_RATE_PER_SECOND,
    ERROR_RATE_PER_SECOND,
    QUEUE_DEPTH,
    CPU_MILLICORES_USED,
    MEMORY_BYTES_USED
  }

  public enum Comparator {
    GREATER_THAN,
    LESS_THAN
  }

  public AlertRuleSpec {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("alert rule name must not be blank");
    }
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must be Optional.empty(), not null");
    }
    if (deploymentName == null || deploymentName.isBlank()) {
      throw new IllegalArgumentException("deploymentName must not be blank");
    }
    if (metric == null) {
      throw new IllegalArgumentException("metric must not be null");
    }
    if (comparator == null) {
      throw new IllegalArgumentException("comparator must not be null");
    }
    if (webhookUrl == null || webhookUrl.isBlank()) {
      throw new IllegalArgumentException("webhookUrl must not be blank");
    }
  }

  /** Convenience: a new rule starts enabled. */
  public AlertRuleSpec(
      String name,
      Optional<String> tenantId,
      String deploymentName,
      Metric metric,
      Comparator comparator,
      double threshold,
      String webhookUrl) {
    this(name, tenantId, deploymentName, metric, comparator, threshold, webhookUrl, true);
  }

  /** Whether {@code value} (a live reading of {@link #metric}) crosses this rule's threshold. */
  public boolean crosses(double value) {
    return switch (comparator) {
      case GREATER_THAN -> value > threshold;
      case LESS_THAN -> value < threshold;
    };
  }
}
