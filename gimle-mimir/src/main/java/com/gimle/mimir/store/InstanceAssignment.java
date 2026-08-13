package com.gimle.mimir.store;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import java.util.OptionalInt;

/**
 * The scheduler's placement decision for one deployment replica: which node should run it, which
 * module version it was placed with, and the artifact path that version was installed from. {@code
 * moduleId}/{@code artifactPath} are what a rolling update actually compares against {@code
 * DeploymentSpec#moduleId()} to detect a version mismatch, and what keep an old-version index
 * installable correctly if the agent supervising it restarts before that index has migrated --
 * without them, every assignment implicitly runs whatever the deployment's *current* spec says,
 * which can't express "this specific replica hasn't migrated yet" mid-rollout. The three-argument
 * constructor (placeholder moduleId/artifactPath) keeps every call site that predates rolling
 * updates and genuinely doesn't care which version was placed unchanged.
 *
 * <p>{@code renamedFromInstanceIndex} is set only by {@code DeploymentReconciler#handleSurge}'s
 * promotion step: this assignment's {@code nodeId}/{@code moduleId}/{@code artifactPath} were
 * copied verbatim from an already-healthy surge instance previously running at that index, rather
 * than freshly scheduled -- a signal {@code AgentMain} uses to retarget the already-running worker
 * in place (re-key its own bookkeeping, tell the worker its identity changed) instead of tearing it
 * down and spawning a new one under this index. Absent for every ordinary placement, which is
 * exactly what "freshly scheduled, nothing to retarget from" means.
 */
public record InstanceAssignment(
    String deploymentName,
    int instanceIndex,
    String nodeId,
    ModuleId moduleId,
    String artifactPath,
    OptionalInt renamedFromInstanceIndex) {

  /**
   * The placeholder the three-argument constructor uses; a caller resolving "what should this
   * instance actually run" (e.g. the API server joining an assignment against its deployment)
   * checks for this to fall back to the deployment spec's own moduleId/artifactPath, matching the
   * behavior for every assignment that never specifies its own.
   */
  public static final ModuleId UNSPECIFIED_MODULE =
      new ModuleId("unspecified", Version.parse("0.0.0"));

  private static final String UNSPECIFIED_ARTIFACT_PATH = "";

  public InstanceAssignment {
    if (deploymentName == null || deploymentName.isBlank()) {
      throw new IllegalArgumentException("deploymentName must not be blank");
    }
    if (instanceIndex < 0) {
      throw new IllegalArgumentException("instanceIndex must not be negative: " + instanceIndex);
    }
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("nodeId must not be blank");
    }
    if (moduleId == null) {
      throw new IllegalArgumentException("moduleId must not be null");
    }
    if (artifactPath == null) {
      throw new IllegalArgumentException("artifactPath must not be null (use \"\" if unknown)");
    }
    if (renamedFromInstanceIndex == null) {
      throw new IllegalArgumentException(
          "renamedFromInstanceIndex must be OptionalInt.empty(), not null");
    }
  }

  /** Back-compat: defaults {@code renamedFromInstanceIndex} to {@code OptionalInt.empty()}. */
  public InstanceAssignment(
      String deploymentName,
      int instanceIndex,
      String nodeId,
      ModuleId moduleId,
      String artifactPath) {
    this(deploymentName, instanceIndex, nodeId, moduleId, artifactPath, OptionalInt.empty());
  }

  public InstanceAssignment(String deploymentName, int instanceIndex, String nodeId) {
    this(deploymentName, instanceIndex, nodeId, UNSPECIFIED_MODULE, UNSPECIFIED_ARTIFACT_PATH);
  }
}
