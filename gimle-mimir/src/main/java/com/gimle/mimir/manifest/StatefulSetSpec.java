package com.gimle.mimir.manifest;

import com.gimle.core.module.ModuleId;
import java.util.Optional;

/**
 * Desired state for a workload needing stable per-index identity and (optionally) persistent
 * local-disk storage -- an index space {@code 0..replicas-1} exactly like {@link DeploymentSpec},
 * but {@code StatefulSetReconciler} enforces {@code OrderedReady} semantics (index {@code i+1}
 * never placed before index {@code i} reports ready; scale-down removes the highest index first)
 * rather than {@link DeploymentReconciler}'s all-at-once placement, and sticky-binds each index to
 * whichever node it first lands on.
 *
 * <p>Deliberately does <em>not</em> carry its own {@code volume:} field -- persistent storage is
 * declared once, on the module's own {@code gimle-module.yaml} (sibling to {@code isolation:}/
 * {@code resources:}), the same place every other per-artifact property already lives; duplicating
 * it here would create two sources of truth for one concept. A {@code StatefulSet} whose module
 * declares no {@code volume:} still gets the ordering/identity guarantees above -- "stateful" in
 * the identity sense, not the storage sense, is a legitimate, supported shape.
 */
public record StatefulSetSpec(
    String name,
    ModuleId moduleId,
    String artifactPath,
    int replicas,
    PlacementConstraints placement,
    Optional<String> tenantId,
    Optional<String> artifactSha256)
    implements WorkloadSpec {

  public StatefulSetSpec {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("statefulset name must not be blank");
    }
    if (moduleId == null) {
      throw new IllegalArgumentException("moduleId must not be null");
    }
    if (artifactPath == null || artifactPath.isBlank()) {
      throw new IllegalArgumentException("artifactPath must not be blank");
    }
    if (replicas < 0) {
      throw new IllegalArgumentException("replicas must not be negative: " + replicas);
    }
    if (placement == null) {
      throw new IllegalArgumentException("placement must not be null");
    }
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must be Optional.empty(), not null");
    }
    if (artifactSha256 == null) {
      throw new IllegalArgumentException("artifactSha256 must be Optional.empty(), not null");
    }
  }
}
