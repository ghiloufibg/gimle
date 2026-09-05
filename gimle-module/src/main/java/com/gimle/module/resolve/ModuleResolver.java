package com.gimle.module.resolve;

import com.gimle.core.exception.GimleResolutionException;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ModuleInstanceId;
import com.gimle.core.module.Requirement;
import com.gimle.module.lifecycle.ModuleState;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Computes a module's dependency wiring against a {@link ModuleRegistry}: each requirement is wired
 * to the highest installed version satisfying it, restricted to candidates already {@code
 * RESOLVED}/{@code ACTIVE}. Dependents are never re-wired when a newer dependency version installs
 * — only an explicit re-resolve of the dependent rewires it — which is what makes version skew
 * during hot redeploy a normal state rather than a special case.
 */
public final class ModuleResolver {

  private final ModuleRegistry registry;

  public ModuleResolver(ModuleRegistry registry) {
    this.registry = registry;
  }

  public ModuleWiring resolve(ModuleInstanceId targetId) {
    detectCycle(targetId.name(), new ArrayDeque<>());

    ModuleDescriptor descriptor = registry.artifact(targetId).descriptor();
    Map<Requirement, ModuleInstanceId> wired = new LinkedHashMap<>();
    for (Requirement requirement : descriptor.requires()) {
      wired.put(requirement, chooseCandidate(targetId, requirement));
    }
    return new ModuleWiring(targetId, wired);
  }

  private ModuleInstanceId chooseCandidate(ModuleInstanceId dependent, Requirement requirement) {
    Optional<ModuleInstanceId> best =
        registry.idsByName(requirement.moduleName()).stream()
            .filter(candidateId -> isResolvableState(registry.state(candidateId)))
            .filter(candidateId -> requirement.range().satisfies(candidateId.version()))
            .max(Comparator.comparing(ModuleInstanceId::version));
    return best.orElseThrow(() -> GimleResolutionException.unsatisfied(dependent, requirement));
  }

  private static boolean isResolvableState(ModuleState state) {
    return state == ModuleState.RESOLVED || state == ModuleState.ACTIVE;
  }

  /**
   * DFS over the <em>declared</em> requirement graph (by module name, across every installed
   * version of that name — not the resolution-order graph, which by construction can't cycle since
   * a dependency must already be resolved before a dependent wires to it). This is intentionally
   * conservative: if any installed version of a name participates in a cycle, the name is flagged,
   * even if the specific version {@link #chooseCandidate} would ultimately pick does not. That's
   * the right default for what is almost always a manifest-authoring mistake.
   */
  private void detectCycle(String moduleName, Deque<String> path) {
    if (path.contains(moduleName)) {
      List<String> cycle = new ArrayList<>(path);
      cycle.add(moduleName);
      throw GimleResolutionException.cycle(cycle);
    }
    path.push(moduleName);
    try {
      for (ModuleInstanceId id : registry.idsByName(moduleName)) {
        for (Requirement requirement : registry.artifact(id).descriptor().requires()) {
          detectCycle(requirement.moduleName(), path);
        }
      }
    } finally {
      path.pop();
    }
  }
}
