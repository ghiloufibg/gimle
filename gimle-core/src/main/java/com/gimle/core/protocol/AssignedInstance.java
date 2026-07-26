package com.gimle.core.protocol;

import com.gimle.core.module.ModuleId;

/**
 * One work order the control plane's scheduler has assigned to a specific node: what to run and
 * where to read it from. {@code artifactPath} is a path the receiving node can read directly, same
 * "resolved locally by whoever needs it" precedent {@code ControlMessage.InstallModule} already
 * established for the agent&harr;worker channel -- this is the same idea one hop further out.
 */
public record AssignedInstance(
    String deploymentName, int instanceIndex, ModuleId moduleId, String artifactPath) {

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
  }
}
