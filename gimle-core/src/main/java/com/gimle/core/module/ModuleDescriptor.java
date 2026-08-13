package com.gimle.core.module;

import java.util.List;
import java.util.Optional;

/** Parsed, fully-validated {@code gimle-module.yaml} content. */
public record ModuleDescriptor(
    String name,
    Version version,
    List<Requirement> requires,
    List<ServiceExport> exports,
    IsolationTier isolationTier,
    ResourceSpec resourceRequest,
    ResourceSpec resourceLimit,
    HealthProbes healthProbes,
    Optional<String> lifecycleHooksClass,
    // Job-kind run-to-completion hooks (priority-3 design doc §3a): sibling field to
    // lifecycleHooksClass, declared as lifecycle.jobHooks in gimle-module.yaml. A module never
    // declares both -- lifecycleHooksClass is for a service's own install/start/stop/uninstall
    // bracket, jobHooksClass is for a Job's one run-to-completion unit of work -- but nothing here
    // enforces mutual exclusion; that's ModuleDescriptorParser's job if it ever matters.
    Optional<String> jobHooksClass,
    // StatefulSet-kind persistent storage (priority-3 design doc §5a): declared as volume: in
    // gimle-module.yaml, a property of the artifact itself (like isolation:/resources: above), not
    // of the workload manifest -- absent means "no persistent storage," the only shape every
    // pre-existing module descriptor has.
    Optional<VolumeRequest> volume) {

  public ModuleDescriptor {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("module name must not be blank");
    }
    if (version == null) {
      throw new IllegalArgumentException("version must not be null");
    }
    requires = List.copyOf(requires);
    exports = List.copyOf(exports);
    if (isolationTier == null) {
      throw new IllegalArgumentException("isolation tier must not be null");
    }
    if (resourceRequest == null || resourceLimit == null) {
      throw new IllegalArgumentException("resource request/limit must not be null");
    }
    if (resourceRequest.memoryBytes() > resourceLimit.memoryBytes()) {
      throw new IllegalArgumentException(
          "memory request exceeds limit: "
              + resourceRequest.memory()
              + " > "
              + resourceLimit.memory());
    }
    if (resourceRequest.cpuMillicores() > resourceLimit.cpuMillicores()) {
      throw new IllegalArgumentException(
          "cpu request exceeds limit: " + resourceRequest.cpu() + " > " + resourceLimit.cpu());
    }
    if (healthProbes == null) {
      throw new IllegalArgumentException("health probes must not be null");
    }
    if (lifecycleHooksClass == null) {
      throw new IllegalArgumentException("lifecycleHooksClass must be Optional.empty(), not null");
    }
    if (jobHooksClass == null) {
      throw new IllegalArgumentException("jobHooksClass must be Optional.empty(), not null");
    }
    if (volume == null) {
      throw new IllegalArgumentException("volume must be Optional.empty(), not null");
    }
  }

  public ModuleId id() {
    return new ModuleId(name, version);
  }
}
