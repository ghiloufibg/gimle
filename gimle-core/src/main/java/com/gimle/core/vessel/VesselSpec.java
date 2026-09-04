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
 * each probe rung must name which one it dials via its own {@link VesselProbeSpec#portName()} --
 * left unnamed, there's no way to know which declared port a bare {@code {tcp: true}} or {@code
 * {http: ...}} entry means, so that case is rejected here rather than guessed at by the agent.
 * Exactly one declared port lets a rung's {@code portName} stay absent, resolving to that sole
 * port.
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
    } else {
      requireResolvablePort(probes.liveness(), "liveness", declaredPorts, env);
      requireResolvablePort(probes.readiness(), "readiness", declaredPorts, env);
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

  /**
   * Once at least one port is declared, a probe rung naming a specific one must actually name a
   * real {@code {port: ...}} entry; a rung naming none is only resolvable when there's exactly one
   * candidate for it to mean.
   */
  private static void requireResolvablePort(
      Optional<VesselProbeSpec> probe,
      String which,
      long declaredPorts,
      Map<String, VesselEnvValue> env) {
    if (probe.isEmpty()) {
      return;
    }
    Optional<String> portName = probe.get().portName();
    if (portName.isPresent()) {
      if (!(env.get(portName.get()) instanceof VesselEnvValue.PortAllocation)) {
        throw new IllegalArgumentException(
            which
                + " probe names port '"
                + portName.get()
                + "', which is not a declared {port: ...} env entry");
      }
      return;
    }
    if (declaredPorts > 1) {
      throw new IllegalArgumentException(
          which
              + " probe must name a port -- more than one {port: ...} env entry is declared, so"
              + " which one to dial is ambiguous without naming it");
    }
  }

  /** The first declared port's env-var name, in {@link #env}'s own iteration order, if any. */
  public Optional<String> firstDeclaredPortName() {
    return env.entrySet().stream()
        .filter(e -> e.getValue() instanceof VesselEnvValue.PortAllocation)
        .map(Map.Entry::getKey)
        .findFirst();
  }

  /**
   * The declared port env-var name {@code probe} should be dialed against: its own named port when
   * it declares one, otherwise the sole declared port. The compact constructor above already
   * rejects an unnamed rung once more than one port is declared, so a caller here never has to
   * guess between multiple candidates the way {@link #firstDeclaredPortName()} alone would.
   */
  public Optional<String> declaredPortNameFor(VesselProbeSpec probe) {
    return probe.portName().isPresent() ? probe.portName() : firstDeclaredPortName();
  }
}
