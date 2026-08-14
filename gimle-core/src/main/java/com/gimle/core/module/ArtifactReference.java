package com.gimle.core.module;

/**
 * The one place that defines how a workload's artifact reference is interpreted everywhere it
 * travels as a plain string (manifest specs, assignments, the agent's install path): a non-blank
 * value is a local filesystem path read directly by whoever holds it, and a blank value means
 * "resolve the module's {@code (name, version)} coordinate from the Andvari artifact registry
 * instead." Blank-as-coordinate rather than a separate field keeps every existing record, codec,
 * and API payload shape unchanged -- the same {@code ""}-means-unspecified convention {@code
 * InstanceAssignment.UNSPECIFIED_ARTIFACT_PATH} already established.
 */
public final class ArtifactReference {

  /** The blank sentinel meaning "resolve from the artifact registry by module coordinate." */
  public static final String REGISTRY_COORDINATE = "";

  private ArtifactReference() {}

  /**
   * Guards a record component carrying an artifact reference: {@code null} is always a programming
   * error, while blank is the legitimate resolve-from-registry state.
   */
  public static void requireValid(String artifactPath) {
    if (artifactPath == null) {
      throw new IllegalArgumentException(
          "artifactPath must not be null; use \"\" to resolve the module coordinate from the"
              + " artifact registry");
    }
  }

  /** True when this reference asks for registry resolution rather than naming a local file. */
  public static boolean isRegistryCoordinate(String artifactPath) {
    return artifactPath == null || artifactPath.isBlank();
  }

  /** True when this reference names a concrete local file to read directly. */
  public static boolean isLocalPath(String artifactPath) {
    return !isRegistryCoordinate(artifactPath);
  }
}
