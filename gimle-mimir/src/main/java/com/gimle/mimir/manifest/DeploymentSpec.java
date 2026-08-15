package com.gimle.mimir.manifest;

import com.gimle.core.module.ArtifactReference;
import com.gimle.core.module.ModuleId;
import com.gimle.core.vessel.VesselSpec;
import java.util.Optional;

/**
 * Desired state for one deployment: how many replicas of a module should run, and where. The
 * module's own {@code gimle-module.yaml} (isolation tier, resource request/limit, health probes) is
 * read from the artifact once resolved -- never duplicated here, keeping "artifact contents"
 * separate from "runtime assignment." {@code artifactPath} is the one exception the scheduler needs
 * up front: it must read the descriptor's isolation tier and resource request *before* any node has
 * resolved anything, so the manifest carries a path the control plane can read directly -- the same
 * "artifact path travels as a plain string, resolved locally by whoever needs it" precedent {@code
 * ControlMessage.InstallModule} already established. A blank {@code artifactPath} is the
 * resolve-from-registry state (see {@link ArtifactReference}): the module's {@code (name, version)}
 * coordinate alone identifies the artifact, pulled from Andvari by whoever needs the bytes.
 *
 * <p>{@code autoscale} is optional: when present, {@code AutoscaleReconciler} computes an effective
 * replica count {@code DeploymentReconciler} reads in place of {@code replicas} -- {@code replicas}
 * itself stays the user-submitted floor/starting point, never overwritten by the autoscaler.
 *
 * <p>{@code tenantId} is optional, matching the {@code autoscale} precedent exactly: a deployment
 * with no {@code tenantId} is untenanted. When present, it must name a {@link
 * com.gimle.core.tenant.Tenant} already registered with the control plane -- checked by the API
 * server at admission, not by this record's own compact constructor, which has no {@code
 * StateStore} to check against.
 *
 * <p>{@code artifactSha256} is the SHA-256 {@code ApiServer} computed from the artifact at {@code
 * artifactPath} when this spec was admitted -- {@code Optional.empty()} means either the spec was
 * admitted before this field existed, or the artifact was unreadable at that moment (the same
 * tolerant posture {@code tenantId}'s own back-compat constructors already establish for a field
 * added after specs already existed). {@code DeploymentReconciler} re-reads the artifact every tick
 * and refuses to place new instances if the bytes on disk no longer match -- ties a spec to the
 * specific artifact it was admitted against, not just whatever currently happens to be at that
 * path.
 *
 * <p>{@code disruption} is optional, matching every other field added after this record already
 * existed: absent means {@link DisruptionBudget#DEFAULT} (migrate one index at a time, no surge),
 * exactly {@code DeploymentReconciler}'s behavior before this field existed.
 *
 * <p>{@code vessel} is optional and, when present, is the *only* thing that distinguishes this spec
 * from an ordinary module-hosted one -- there is no separate flag. Presence means {@code
 * moduleId}/{@code artifactPath} name a plain runnable jar the agent spawns directly as its own OS
 * process, never loaded into a worker JVM as a Java module.
 */
public record DeploymentSpec(
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

  public DeploymentSpec {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("deployment name must not be blank");
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

  /** Back-compat: defaults {@code vessel} to {@code Optional.empty()}. */
  public DeploymentSpec(
      String name,
      ModuleId moduleId,
      String artifactPath,
      int replicas,
      PlacementConstraints placement,
      Optional<AutoscalePolicy> autoscale,
      Optional<String> tenantId,
      Optional<String> artifactSha256,
      Optional<DisruptionBudget> disruption) {
    this(
        name,
        moduleId,
        artifactPath,
        replicas,
        placement,
        autoscale,
        tenantId,
        artifactSha256,
        disruption,
        Optional.empty());
  }

  /** Back-compat: defaults {@code disruption} to {@code Optional.empty()}. */
  public DeploymentSpec(
      String name,
      ModuleId moduleId,
      String artifactPath,
      int replicas,
      PlacementConstraints placement,
      Optional<AutoscalePolicy> autoscale,
      Optional<String> tenantId,
      Optional<String> artifactSha256) {
    this(
        name,
        moduleId,
        artifactPath,
        replicas,
        placement,
        autoscale,
        tenantId,
        artifactSha256,
        Optional.empty());
  }

  /**
   * Back-compat: defaults {@code tenantId} and {@code artifactSha256} to {@code Optional.empty()}.
   */
  public DeploymentSpec(
      String name,
      ModuleId moduleId,
      String artifactPath,
      int replicas,
      PlacementConstraints placement,
      Optional<AutoscalePolicy> autoscale) {
    this(name, moduleId, artifactPath, replicas, placement, autoscale, Optional.empty());
  }

  /** Back-compat: defaults {@code artifactSha256} to {@code Optional.empty()}. */
  public DeploymentSpec(
      String name,
      ModuleId moduleId,
      String artifactPath,
      int replicas,
      PlacementConstraints placement,
      Optional<AutoscalePolicy> autoscale,
      Optional<String> tenantId) {
    this(name, moduleId, artifactPath, replicas, placement, autoscale, tenantId, Optional.empty());
  }

  public DeploymentSpec(
      String name,
      ModuleId moduleId,
      String artifactPath,
      int replicas,
      PlacementConstraints placement) {
    this(name, moduleId, artifactPath, replicas, placement, Optional.empty(), Optional.empty());
  }

  /**
   * The effective disruption budget: {@link #disruption} itself when present, {@link
   * DisruptionBudget#DEFAULT} otherwise -- the one line every caller should use instead of
   * unwrapping {@link #disruption} directly, so "no {@code disruption:} block" and "an explicit
   * {@code {maxUnavailable: 1, maxSurge: 0}} block" are handled identically everywhere.
   */
  public DisruptionBudget effectiveDisruptionBudget() {
    return disruption.orElse(DisruptionBudget.DEFAULT);
  }

  /**
   * The peak instance count this deployment may ever legitimately run at once: {@link #replicas}
   * plus whatever surge headroom {@link #effectiveDisruptionBudget()} allows. {@code maxSurge} is
   * accepted for a Deployment (rejected only for a DaemonSet, where one-instance-per-node already
   * leaves no room for an "extra" instance -- see {@link DisruptionBudget}'s own javadoc), so this
   * charges a tenant's quota for the peak {@code replicas + maxSurge} a rollout could transiently
   * reach, not just the steady-state {@code replicas}.
   */
  public int maxCommittedInstances() {
    return replicas + effectiveDisruptionBudget().maxSurge();
  }
}
