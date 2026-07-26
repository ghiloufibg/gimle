package com.gimle.core.exception;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Requirement;
import java.util.List;

/**
 * A module's dependency wiring or its underlying JPMS {@code ModuleLayer} cannot be built: an
 * unsatisfied requirement, a dependency cycle in the declared (not resolution-order) graph, or
 * layer/configuration resolution failing for any other reason (including a split-package conflict,
 * which JPMS itself detects during {@code Configuration.resolve} — Gimlé wraps that rather than
 * re-implementing package-overlap detection independently).
 */
public class GimleResolutionException extends RuntimeException {

  private GimleResolutionException(String message) {
    super(message);
  }

  private GimleResolutionException(String message, Throwable cause) {
    super(message, cause);
  }

  public static GimleResolutionException unsatisfied(ModuleId dependent, Requirement requirement) {
    return new GimleResolutionException(
        "module "
            + dependent
            + " requires "
            + requirement.moduleName()
            + " "
            + requirement.range()
            + " but no installed module satisfies it");
  }

  /**
   * {@code moduleNames} is a cycle in the declared dependency graph (by module name, not version) —
   * e.g. {@code [A, B, A]} for {@code A requires B requires A}.
   */
  public static GimleResolutionException cycle(List<String> moduleNames) {
    return new GimleResolutionException(
        "dependency cycle detected: " + String.join(" -> ", moduleNames));
  }

  public static GimleResolutionException layer_instantiation_failed(
      ModuleId moduleId, Throwable cause) {
    return new GimleResolutionException("failed to instantiate ModuleLayer for " + moduleId, cause);
  }
}
