package com.gimle.controlplane.manifest;

import com.gimle.controlplane.autoscale.AutoscalePolicy;
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
 */
public record DeploymentSpec(
    String name,
    ModuleId moduleId,
    String artifactPath,
    int replicas,
    PlacementConstraints placement,
    Optional<AutoscalePolicy> autoscale,
    Optional<String> tenantId) {

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
  }

  public DeploymentSpec(
      String name,
      ModuleId moduleId,
      String artifactPath,
      int replicas,
      PlacementConstraints placement,
      Optional<AutoscalePolicy> autoscale) {
    this(name, moduleId, artifactPath, replicas, placement, autoscale, Optional.empty());
  }

  public DeploymentSpec(
      String name,
      ModuleId moduleId,
      String artifactPath,
      int replicas,
      PlacementConstraints placement) {
    this(name, moduleId, artifactPath, replicas, placement, Optional.empty(), Optional.empty());
  }
}
