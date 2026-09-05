package com.gimle.module.resolve;

import com.gimle.core.module.ModuleInstanceId;
import com.gimle.core.module.Requirement;
import java.util.Map;

/**
 * A module's resolved dependency graph: which specific installed version satisfies each
 * requirement.
 */
public record ModuleWiring(
    ModuleInstanceId id, Map<Requirement, ModuleInstanceId> wiredDependencies) {

  public ModuleWiring {
    if (id == null) {
      throw new IllegalArgumentException("module id must not be null");
    }
    wiredDependencies = Map.copyOf(wiredDependencies);
  }
}
