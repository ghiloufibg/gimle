package com.gimle.mimir.manifest;

import com.gimle.core.module.ModuleId;
import java.util.Optional;

/**
 * Desired state for one deployment: how many replicas of a module should run, and where. The
 * module's own {@code gimle-module.yaml} (isolation tier, resource request/limit, health probes) is
 * read from the artifact once resolved -- never duplicated here, keeping "artifact contents"
 * separate from "runtime assignment." {@code artifactPath} is the one exception the scheduler needs
 * up front: it must read the descriptor's isolation tier and resource request *before* any node has
 * resolved anything, so the manifest carries a path the control plane can read directly -- the same
 * "artifact path travels as a plain string, resolved locally by whoever needs it" precedent {@code
 * ControlMessage.InstallModule} already established.
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
    Optional<DisruptionBudget> disruption)
    implements WorkloadSpec {

  public DeploymentSpec {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("deployment name must not be blank");
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
   * plus whatever surge headroom {@link #effectiveDisruptionBudget()} allows. Today {@code
   * maxSurge} is always {@code 0} (rejected outright at parse time), so this equals {@link
   * #replicas} everywhere it's used -- it exists now so admission-time quota accounting is already
   * correct once a rollout can actually provision a surge instance ahead of removing the original,
   * rather than needing a second change to the quota check when that lands.
   */
  public int maxCommittedInstances() {
    return replicas + effectiveDisruptionBudget().maxSurge();
  }
}
