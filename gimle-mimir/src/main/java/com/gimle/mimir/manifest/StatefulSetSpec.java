package com.gimle.mimir.manifest;

import com.gimle.core.module.ArtifactReference;
import com.gimle.core.module.ModuleId;
import com.gimle.core.vessel.VesselSpec;
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
 *
 * <p>{@code autoscale} is optional, matching {@link DeploymentSpec#autoscale()} exactly: when
 * present, {@code AutoscaleReconciler} computes an effective replica count {@code
 * StatefulSetReconciler} reads in place of {@code replicas} -- {@code replicas} itself stays the
 * user-submitted floor, never overwritten by the autoscaler.
 *
 * <p>{@code disruption} is optional and governs how many indices {@code StatefulSetReconciler} may
 * roll forward concurrently during a version update -- absent means {@link
 * DisruptionBudget#DEFAULT} (one index at a time), the behavior every StatefulSet had before this
 * field existed. Unlike {@link DeploymentSpec}, {@code maxSurge} is never accepted here: {@link
 * StatefulSetManifestParser} rejects a nonzero value outright, the same permanent posture {@link
 * DaemonSetManifestParser} already takes for the identical reason -- a StatefulSet index owns a
 * sticky, exclusive per-index identity (and, when the module declares one, a local-disk volume that
 * cannot be duplicated), so there is no "extra" instance a rollout could ever provision ahead of
 * removing the original.
 */
public record StatefulSetSpec(
    String name,
    ModuleId moduleId,
    String artifactPath,
    int replicas,
    PlacementConstraints placement,
    Optional<AutoscalePolicy> autoscale,
    Optional<String> tenantId,
    Optional<String> artifactSha256,
    Optional<DisruptionBudget> disruption,
    Optional<VesselSpec> vessel)
    implements WorkloadSpec {

  public StatefulSetSpec {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("statefulset name must not be blank");
    }
    if (moduleId == null) {
      throw new IllegalArgumentException("moduleId must not be null");
    }
    ArtifactReference.requireValid(artifactPath);
    if (replicas < 0) {
      throw new IllegalArgumentException("replicas must not be negative: " + replicas);
    }
    if (placement == null) {
      throw new IllegalArgumentException("placement must not be null");
    }
    if (autoscale == null) {
      throw new IllegalArgumentException("autoscale must be Optional.empty(), not null");
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

  /** Back-compat: defaults {@code autoscale} and {@code disruption} to {@code Optional.empty()}. */
  public StatefulSetSpec(
      String name,
      ModuleId moduleId,
      String artifactPath,
      int replicas,
      PlacementConstraints placement,
      Optional<String> tenantId,
      Optional<String> artifactSha256,
      Optional<VesselSpec> vessel) {
    this(
        name,
        moduleId,
        artifactPath,
        replicas,
        placement,
        Optional.empty(),
        tenantId,
        artifactSha256,
        Optional.empty(),
        vessel);
  }

  /** Back-compat: defaults {@code vessel} (and {@code autoscale}/{@code disruption}) to empty. */
  public StatefulSetSpec(
      String name,
      ModuleId moduleId,
      String artifactPath,
      int replicas,
      PlacementConstraints placement,
      Optional<String> tenantId,
      Optional<String> artifactSha256) {
    this(
        name,
        moduleId,
        artifactPath,
        replicas,
        placement,
        tenantId,
        artifactSha256,
        Optional.empty());
  }

  /**
   * The effective disruption budget: {@link #disruption} itself when present, {@link
   * DisruptionBudget#DEFAULT} otherwise -- mirrors {@link
   * DeploymentSpec#effectiveDisruptionBudget()} exactly.
   */
  public DisruptionBudget effectiveDisruptionBudget() {
    return disruption.orElse(DisruptionBudget.DEFAULT);
  }
}
