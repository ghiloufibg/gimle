package com.gimle.mimir.manifest;

import com.gimle.core.module.ArtifactReference;
import com.gimle.core.module.ModuleId;
import com.gimle.core.vessel.VesselSpec;
import java.util.Optional;

/**
 * Desired state for a per-node workload: one instance on every node currently matching {@code
 * placement} and not cordoned -- "how many" is never a stored integer the way {@link
 * DeploymentSpec#replicas()} is, it's recomputed every reconcile tick from live node state (see
 * {@code DaemonSetReconciler}).
 *
 * <p>{@code placement.antiAffinityAcrossNodes} is deliberately meaningless here and rejected
 * outright by {@link DaemonSetManifestParser} if set {@code true} -- "one per node, cluster-wide"
 * is already a stronger guarantee than anti-affinity was ever meant to express, so allowing the
 * flag would just be a confusing no-op at best.
 *
 * <p>{@code disruption} is optional, matching {@code tenantId}/{@code artifactSha256}'s own
 * back-compat shape: absent means {@link DisruptionBudget#DEFAULT} (migrate one node at a time),
 * exactly {@code DaemonSetReconciler}'s behavior before this field existed. Its own {@code
 * maxSurge} is always {@code 0} here -- {@link DaemonSetManifestParser} rejects a nonzero value
 * outright, the same posture it takes for {@code placement.antiAffinity}.
 *
 * <p>{@code tolerateAllTaints} defaults to {@code false} -- a node an operator has tainted for one
 * or more tenants stays excluded from this DaemonSet's placement by default, the same
 * tenant-isolation boundary {@code Scheduler}'s taint filter already enforces for every other
 * workload kind. Unlike Kubernetes, where a DaemonSet's pod template gets baseline tolerations for
 * a handful of built-in system taints automatically, Gimlé taints have no such "well-known" subset
 * to distinguish -- every taint here is an operator-declared tenant reservation, so opting out is a
 * deliberate, explicit, per-DaemonSet choice rather than an unconditional default. Set {@code true}
 * only for a genuinely cluster-wide, untenanted DaemonSet (a log shipper or node exporter) that
 * must cover every node including ones reserved for a tenant -- {@code Scheduler.eligibleNodes}
 * skips its taint-filter stage entirely when this is set, independent of the DaemonSet's own {@code
 * tenantId}.
 */
public record DaemonSetSpec(
    String name,
    ModuleId moduleId,
    String artifactPath,
    PlacementConstraints placement,
    Optional<String> tenantId,
    Optional<String> artifactSha256,
    Optional<DisruptionBudget> disruption,
    Optional<VesselSpec> vessel,
    boolean tolerateAllTaints)
    implements WorkloadSpec {

  public DaemonSetSpec {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("daemonset name must not be blank");
    }
    if (moduleId == null) {
      throw new IllegalArgumentException("moduleId must not be null");
    }
    ArtifactReference.requireValid(artifactPath);
    if (placement == null) {
      throw new IllegalArgumentException("placement must not be null");
    }
    if (placement.antiAffinityAcrossNodes()) {
      throw new IllegalArgumentException(
          "placement.antiAffinity is meaningless on a DaemonSet -- one per node is already"
              + " stronger than anti-affinity");
    }
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must be Optional.empty(), not null");
    }
    if (artifactSha256 == null) {
      throw new IllegalArgumentException("artifactSha256 must be Optional.empty(), not null");
    }
    if (disruption == null) {
      throw new IllegalArgumentException("disruption must be Optional.empty(), not null");
    }
    if (vessel == null) {
      throw new IllegalArgumentException("vessel must be Optional.empty(), not null");
    }
  }

  /** Back-compat: defaults {@code tolerateAllTaints} to {@code false}. */
  public DaemonSetSpec(
      String name,
      ModuleId moduleId,
      String artifactPath,
      PlacementConstraints placement,
      Optional<String> tenantId,
      Optional<String> artifactSha256,
      Optional<DisruptionBudget> disruption,
      Optional<VesselSpec> vessel) {
    this(
        name,
        moduleId,
        artifactPath,
        placement,
        tenantId,
        artifactSha256,
        disruption,
        vessel,
        false);
  }

  /** Back-compat: defaults {@code vessel} to {@code Optional.empty()}. */
  public DaemonSetSpec(
      String name,
      ModuleId moduleId,
      String artifactPath,
      PlacementConstraints placement,
      Optional<String> tenantId,
      Optional<String> artifactSha256,
      Optional<DisruptionBudget> disruption) {
    this(
        name,
        moduleId,
        artifactPath,
        placement,
        tenantId,
        artifactSha256,
        disruption,
        Optional.empty());
  }

  /** Back-compat: defaults {@code disruption} to {@code Optional.empty()}. */
  public DaemonSetSpec(
      String name,
      ModuleId moduleId,
      String artifactPath,
      PlacementConstraints placement,
      Optional<String> tenantId,
      Optional<String> artifactSha256) {
    this(name, moduleId, artifactPath, placement, tenantId, artifactSha256, Optional.empty());
  }

  /** See {@link DeploymentSpec#effectiveDisruptionBudget()}'s own javadoc. */
  public DisruptionBudget effectiveDisruptionBudget() {
    return disruption.orElse(DisruptionBudget.DEFAULT);
  }
}
