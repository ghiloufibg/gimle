package com.gimle.core.module;

/**
 * Which running instance of a module a worker is being told about -- the artifact coordinate plus
 * what distinguishes one replica from another inside the same worker JVM.
 *
 * <p>A {@link ModuleId} names an artifact, not something running: two replicas of one deployment
 * are the same artifact by construction. Keying a worker's registry, schedulers and identities by
 * {@link ModuleId} therefore made two such replicas indistinguishable, so packing them into one
 * worker would have had them share a single {@code ModuleLayer}, a single lifecycle state and a
 * single set of exported services -- stopping either would stop both. The density limit that fell
 * out of that (siblings of one deployment always got their own worker, however small they were) was
 * a consequence of the wrong key rather than a decision anyone made.
 *
 * <p>{@code instanceKey} is empty for a module installed with no deployment identity at all -- a
 * test fixture, or an artifact installed directly into a worker rather than placed by the control
 * plane. Two such installs of one artifact still collide, which is exactly right: nothing has said
 * they are different instances.
 */
public record ModuleInstanceId(ModuleId moduleId, String instanceKey) {

  public ModuleInstanceId {
    if (moduleId == null) {
      throw new IllegalArgumentException("moduleId must not be null");
    }
    if (instanceKey == null) {
      throw new IllegalArgumentException("instanceKey must be empty, not null");
    }
  }

  /** A module with no deployment identity -- see this type's own javadoc for when that is right. */
  public static ModuleInstanceId unattached(ModuleId moduleId) {
    return new ModuleInstanceId(moduleId, "");
  }

  /** The instance a control plane placed: one replica of one deployment, in one tenant. */
  public static ModuleInstanceId of(
      ModuleId moduleId, String tenantId, String deploymentName, int instanceIndex) {
    return new ModuleInstanceId(moduleId, tenantId + "/" + deploymentName + "#" + instanceIndex);
  }

  public String name() {
    return moduleId.name();
  }

  public Version version() {
    return moduleId.version();
  }

  @Override
  public String toString() {
    return instanceKey.isEmpty() ? moduleId.toString() : moduleId + " [" + instanceKey + "]";
  }
}
