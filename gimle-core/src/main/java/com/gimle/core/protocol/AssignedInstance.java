package com.gimle.core.protocol;

import com.gimle.core.module.ModuleId;
import java.util.Optional;

/**
 * One work order the control plane's scheduler has assigned to a specific node: what to run and
 * where to read it from. {@code artifactPath} is a path the receiving node can read directly, same
 * "resolved locally by whoever needs it" precedent {@code ControlMessage.InstallModule} already
 * established for the agent&harr;worker channel -- this is the same idea one hop further out.
 *
 * <p>{@code tenantId} (Phase 5 design §5.1/§6.3) is the deployment's tenant, if any -- carried here
 * so the agent can pass it down to the worker it spawns (as a launch argument, the same
 * "AgentMain's existing all-CLI-args bootstrapping" precedent used for node id and gossip seeds),
 * which needs it to scope {@code FabricServiceRegistry} permission checks and {@code
 * ModuleContext.config} lookups to this instance's own tenant.
 */
public record AssignedInstance(
    String deploymentName,
    int instanceIndex,
    ModuleId moduleId,
    String artifactPath,
    Optional<String> tenantId) {

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
  }

  public AssignedInstance(
      String deploymentName, int instanceIndex, ModuleId moduleId, String artifactPath) {
    this(deploymentName, instanceIndex, moduleId, artifactPath, Optional.empty());
  }
}
