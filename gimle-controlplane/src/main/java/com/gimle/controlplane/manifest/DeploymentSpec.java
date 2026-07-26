package com.gimle.controlplane.manifest;

import com.gimle.core.module.ModuleId;

/**
 * Desired state for one deployment: how many replicas of a module should run, and where. The
 * module's own {@code gimle-module.yaml} (isolation tier, resource request/limit, health probes) is
 * read from the artifact once resolved -- never duplicated here, matching the separation Phase 1/2
 * already draw between "artifact contents" and "runtime assignment." {@code artifactPath} is the
 * one exception the scheduler needs up front: it must read the descriptor's isolation tier and
 * resource request *before* any node has resolved anything, so the manifest carries a path the
 * control plane can read directly -- the same "artifact path travels as a plain string, resolved
 * locally by whoever needs it" precedent {@code ControlMessage.InstallModule} already established.
 */
public record DeploymentSpec(
    String name,
    ModuleId moduleId,
    String artifactPath,
    int replicas,
    PlacementConstraints placement) {

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
  }
}
