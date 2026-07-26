package com.gimle.module.resolve;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Requirement;
import java.util.Map;

/**
 * A module's resolved dependency graph: which specific installed version satisfies each
 * requirement.
 */
public record ModuleWiring(ModuleId id, Map<Requirement, ModuleId> wiredDependencies) {

  public ModuleWiring {
    if (id == null) {
      throw new IllegalArgumentException("module id must not be null");
    }
    wiredDependencies = Map.copyOf(wiredDependencies);
  }
}
