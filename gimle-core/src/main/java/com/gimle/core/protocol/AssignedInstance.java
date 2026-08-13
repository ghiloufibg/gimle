package com.gimle.core.protocol;

import com.gimle.core.module.ModuleId;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One work order the control plane's scheduler has assigned to a specific node: which module to
 * run, and where to read its artifact from. {@code artifactPath} is resolved locally by the
 * receiving node rather than shipped by the control plane.
 *
 * <p>{@code tenantId}, when present, is the deployment's tenant. The agent passes it down to the
 * worker it spawns so the worker can scope service-registry permission checks and configuration
 * lookups to that tenant.
 *
 * <p>{@code renamedFromInstanceIndex}, when present, means this work order isn't a fresh placement:
 * the control plane wants whatever instance the agent is already supervising under {@code
 * deploymentName#renamedFromInstanceIndex} retargeted to this index in place, no restart -- see
 * {@code AgentMain#reconcileAssignments}'s own rename branch, and {@code
 * com.gimle.mimir.store.InstanceAssignment}'s identically-named field, which this is copied from.
 */
public record AssignedInstance(
    String deploymentName,
    int instanceIndex,
    ModuleId moduleId,
    String artifactPath,
    Optional<String> tenantId,
    OptionalInt renamedFromInstanceIndex) {

  public AssignedInstance {
    if (deploymentName == null || deploymentName.isBlank()) {
      throw new IllegalArgumentException("deploymentName must not be blank");
    }
    if (instanceIndex < 0) {
      throw new IllegalArgumentException("instanceIndex must not be negative: " + instanceIndex);
    }
    if (moduleId == null) {
      throw new IllegalArgumentException("moduleId must not be null");
    }
    if (artifactPath == null || artifactPath.isBlank()) {
      throw new IllegalArgumentException("artifactPath must not be blank");
    }
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must be Optional.empty(), not null");
    }
    if (renamedFromInstanceIndex == null) {
      throw new IllegalArgumentException(
          "renamedFromInstanceIndex must be OptionalInt.empty(), not null");
    }
  }

  /** Back-compat: defaults {@code renamedFromInstanceIndex} to {@code OptionalInt.empty()}. */
  public AssignedInstance(
      String deploymentName,
      int instanceIndex,
      ModuleId moduleId,
      String artifactPath,
      Optional<String> tenantId) {
    this(deploymentName, instanceIndex, moduleId, artifactPath, tenantId, OptionalInt.empty());
  }

  public AssignedInstance(
      String deploymentName, int instanceIndex, ModuleId moduleId, String artifactPath) {
    this(deploymentName, instanceIndex, moduleId, artifactPath, Optional.empty());
  }
}
