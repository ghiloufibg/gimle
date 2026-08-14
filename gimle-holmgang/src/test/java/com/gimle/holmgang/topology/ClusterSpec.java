package com.gimle.holmgang.topology;

import com.gimle.core.exception.GimleManifestException;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The immutable description of one cluster shape -- the single thing the bootstrap runtime reads.
 * Both entry points (a YAML topology document via {@link ClusterTopologyParser} and the {@link
 * ClusterTopology} builder) converge here, so every validation rule lives in this constructor and
 * applies identically to both.
 *
 * <p>Deliberately absent: ports (leased dynamically at boot, so two clusters can coexist on one
 * machine and no topology ever encodes a machine-specific constant), hostnames (everything binds
 * loopback), and deployed modules (deployments are scenario actions, not cluster composition).
 */
public record ClusterSpec(
    String name,
    Transport transport,
    int storeReplicas,
    int controlPlaneReplicas,
    int fafnirReplicas,
    boolean muninnEnabled,
    List<NodeSpec> nodes,
    boolean faultsProxied,
    Map<ProcessRole, List<String>> extraJvmFlags,
    SeedSpec seed) {

  public ClusterSpec {
    if (name == null || name.isBlank()) {
      throw new GimleManifestException("topology name must be a non-blank string");
    }
    if (transport == null) {
      throw new GimleManifestException("topology transport must be set");
    }
    if (storeReplicas < 1) {
      throw new GimleManifestException("store.replicas must be >= 1, got " + storeReplicas);
    }
    if (controlPlaneReplicas < 1) {
      throw new GimleManifestException(
          "controlPlane.replicas must be >= 1, got " + controlPlaneReplicas);
    }
    // ControlPlaneMain requires --fafnir-endpoint, so a topology can never omit Fafnir entirely.
    if (fafnirReplicas < 1) {
      throw new GimleManifestException("fafnir.replicas must be >= 1, got " + fafnirReplicas);
    }
    nodes = List.copyOf(nodes);
    final Set<String> nodeIds = new HashSet<>();
    for (final NodeSpec node : nodes) {
      if (!nodeIds.add(node.id())) {
        throw new GimleManifestException("duplicate node id: " + node.id());
      }
    }
    final Map<ProcessRole, List<String>> flags = new EnumMap<>(ProcessRole.class);
    extraJvmFlags.forEach((role, roleFlags) -> flags.put(role, List.copyOf(roleFlags)));
    extraJvmFlags = Map.copyOf(flags);
    if (seed == null) {
      seed = SeedSpec.EMPTY;
    }
  }

  /** The extra JVM flags for one role; empty when the topology declares none. */
  public List<String> jvmFlags(final ProcessRole role) {
    return extraJvmFlags.getOrDefault(role, List.of());
  }
}
