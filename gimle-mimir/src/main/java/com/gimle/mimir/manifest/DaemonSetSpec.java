package com.gimle.mimir.manifest;

import com.gimle.core.module.ModuleId;
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
 */
public record DaemonSetSpec(
    String name,
    ModuleId moduleId,
    String artifactPath,
    PlacementConstraints placement,
    Optional<String> tenantId,
    Optional<String> artifactSha256)
    implements WorkloadSpec {

  public DaemonSetSpec {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("daemonset name must not be blank");
    }
    if (moduleId == null) {
      throw new IllegalArgumentException("moduleId must not be null");
    }
    if (artifactPath == null || artifactPath.isBlank()) {
      throw new IllegalArgumentException("artifactPath must not be blank");
    }
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
  }
}
