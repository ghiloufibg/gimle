package com.gimle.core.protocol;

import com.gimle.core.module.ArtifactReference;
import com.gimle.core.module.ModuleId;
import com.gimle.core.vessel.VesselSpec;
import java.util.List;
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
 *
 * <p>{@code vessel}, when present, means the agent must not run {@code artifactPath} as a Java
 * module at all -- no worker JVM, no {@code ModuleLayer}, no install/resolve/start handshake -- but
 * spawn it directly as its own {@code java -jar} process instead, configured from the {@link
 * VesselSpec} itself rather than any descriptor read from the jar. Copied straight from the owning
 * workload spec's own {@code vessel()} field by whichever handler builds this record; the agent
 * never inspects the artifact to decide which hosting mode applies.
 *
 * <p>{@code configMapRefs}, when non-empty, names the ConfigMaps whose keys this instance should
 * receive in place of its tenant's entire flat config set -- copied straight from the owning
 * Deployment's own {@code configMapRefs()} field. Empty (the default, and the only value every
 * other workload kind ever passes) means "unscoped": the agent fetches and delivers every plain
 * config entry the tenant owns, exactly as it did before this field existed.
 *
 * <p>{@code secretMapRefs} is the identical narrowing for Fafnir-managed secrets: when non-empty,
 * names the SecretMaps whose keys this instance should receive in place of its tenant's entire
 * secret set -- copied straight from the owning Deployment's own {@code secretMapRefs()} field.
 * Empty means "unscoped": the agent fetches and delivers every secret the tenant owns, exactly as
 * it did before this field existed.
 */
public record AssignedInstance(
    String deploymentName,
    int instanceIndex,
    ModuleId moduleId,
    String artifactPath,
    Optional<String> tenantId,
    OptionalInt renamedFromInstanceIndex,
    Optional<VesselSpec> vessel,
    List<String> configMapRefs,
    List<String> secretMapRefs) {

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
    ArtifactReference.requireValid(artifactPath);
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must be Optional.empty(), not null");
    }
    if (renamedFromInstanceIndex == null) {
      throw new IllegalArgumentException(
          "renamedFromInstanceIndex must be OptionalInt.empty(), not null");
    }
    if (vessel == null) {
      throw new IllegalArgumentException("vessel must be Optional.empty(), not null");
    }
    if (configMapRefs == null) {
      throw new IllegalArgumentException("configMapRefs must be List.of(), not null");
    }
    if (secretMapRefs == null) {
      throw new IllegalArgumentException("secretMapRefs must be List.of(), not null");
    }
    configMapRefs = List.copyOf(configMapRefs);
    secretMapRefs = List.copyOf(secretMapRefs);
  }

  /** Back-compat: defaults {@code secretMapRefs} to {@code List.of()}. */
  public AssignedInstance(
      String deploymentName,
      int instanceIndex,
      ModuleId moduleId,
      String artifactPath,
      Optional<String> tenantId,
      OptionalInt renamedFromInstanceIndex,
      Optional<VesselSpec> vessel,
      List<String> configMapRefs) {
    this(
        deploymentName,
        instanceIndex,
        moduleId,
        artifactPath,
        tenantId,
        renamedFromInstanceIndex,
        vessel,
        configMapRefs,
        List.of());
  }

  /** Back-compat: defaults {@code configMapRefs} and {@code secretMapRefs} to {@code List.of()}. */
  public AssignedInstance(
      String deploymentName,
      int instanceIndex,
      ModuleId moduleId,
      String artifactPath,
      Optional<String> tenantId,
      OptionalInt renamedFromInstanceIndex,
      Optional<VesselSpec> vessel) {
    this(
        deploymentName,
        instanceIndex,
        moduleId,
        artifactPath,
        tenantId,
        renamedFromInstanceIndex,
        vessel,
        List.of());
  }

  /**
   * Back-compat: defaults {@code renamedFromInstanceIndex}, {@code vessel}, {@code configMapRefs},
   * and {@code secretMapRefs} to empty.
   */
  public AssignedInstance(
      String deploymentName,
      int instanceIndex,
      ModuleId moduleId,
      String artifactPath,
      Optional<String> tenantId,
      OptionalInt renamedFromInstanceIndex) {
    this(
        deploymentName,
        instanceIndex,
        moduleId,
        artifactPath,
        tenantId,
        renamedFromInstanceIndex,
        Optional.empty());
  }

  /**
   * Back-compat: defaults {@code tenantId}, {@code renamedFromInstanceIndex} and {@code vessel}.
   */
  public AssignedInstance(
      String deploymentName, int instanceIndex, ModuleId moduleId, String artifactPath) {
    this(
        deploymentName,
        instanceIndex,
        moduleId,
        artifactPath,
        Optional.empty(),
        OptionalInt.empty());
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
}
