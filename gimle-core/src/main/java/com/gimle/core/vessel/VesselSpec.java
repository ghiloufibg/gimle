package com.gimle.core.vessel;

import com.gimle.core.module.ResourceSpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code vessel:} block, present on a workload spec means "run this artifact as a plain
 * runnable jar in its own OS process" rather than as a Java module loaded into a worker JVM --
 * presence of this field is the one and only switch between the two hosting modes; there is no
 * separate {@code hostingMode} flag anywhere.
 *
 * <p>{@code resourceRequest}/{@code resourceLimit} exist here, and nowhere else, because a vessel
 * has no {@code gimle-module.yaml} to read them from the way a module's {@code ModuleDescriptor}
 * does -- the manifest itself is the only place they can live. A vessel is always dedicated-process
 * hosting (the module system's Tier 2 guarantee); there is no {@code isolationTier} field here
 * since there is no weaker option to choose between.
 *
 * <p>A {@code tcp}/{@code http} probe rung needs at least one declared port to dial -- checked here
 * rather than left to fail confusingly once the agent actually tries to probe, by counting {@code
 * env} entries of type {@link VesselEnvValue.PortAllocation}. When more than one port is declared,
 * the agent probes the first one in this map's own iteration order; nothing here picks a specific
 * one by name, since the ladder has no way to know which declared port a bare {@code {tcp: true}}
 * or {@code {http: ...}} entry means without an explicit reference this manifest shape doesn't
 * carry.
 */
public record VesselSpec(
    List<String> args,
    List<String> jvmFlags,
    Map<String, VesselEnvValue> env,
    List<VesselFileMount> files,
    VesselProbes probes,
    ResourceSpec resourceRequest,
    ResourceSpec resourceLimit) {

  public VesselSpec {
    args = List.copyOf(args);
    jvmFlags = List.copyOf(jvmFlags);
    env = Map.copyOf(env);
    files = List.copyOf(files);
    if (probes == null) {
      throw new IllegalArgumentException("probes must not be null; use VesselProbes.NONE");
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
    long declaredPorts =
        env.values().stream().filter(v -> v instanceof VesselEnvValue.PortAllocation).count();
    if (declaredPorts == 0) {
      requireNoPortDependentProbe(probes.liveness(), "liveness");
      requireNoPortDependentProbe(probes.readiness(), "readiness");
    }
  }

  private static void requireNoPortDependentProbe(Optional<VesselProbeSpec> probe, String which) {
    if (probe.isPresent()) {
      throw new IllegalArgumentException(
          which
              + " probe requires at least one env entry declaring {port: dynamic} or {port:"
              + " <fixed>} -- a tcp/http probe has nothing to dial otherwise");
    }
  }

  /** The first declared port's env-var name, in {@link #env}'s own iteration order, if any. */
  public Optional<String> firstDeclaredPortName() {
    return env.entrySet().stream()
        .filter(e -> e.getValue() instanceof VesselEnvValue.PortAllocation)
        .map(Map.Entry::getKey)
        .findFirst();
  }
}
