package com.gimle.hilmir.topology;

import com.gimle.core.exception.GimleManifestException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The immutable description of one multi-machine cluster shape -- the single thing both {@link
 * TopologyParser} and {@code com.gimle.hilmir.plan.LaunchPlanner} read.
 *
 * <p>Deliberately permissive at the type level: an empty {@code machines} list, zero store
 * replicas, a store replica referencing a machine nowhere in {@code machines}, or two agents
 * sharing a node id all construct a {@code Topology} without complaint. Unlike {@code
 * gimle-holmgang}'s own {@code ClusterSpec} (a test fixture that should fail fast on a shape it can
 * never meaningfully boot), a {@code Topology} is operator-authored input to an operator-facing
 * tool -- every such mistake is instead a {@code com.gimle.hilmir.validate.Finding} the operator
 * can see all of at once, not just the first one the constructor happens to trip over. This
 * constructor enforces only what would otherwise make a field meaningless to read (a null section,
 * a blank name) -- see {@code TopologyValidator} for every semantic rule.
 */
public record Topology(
    String name,
    Transport transport,
    Optional<TlsMaterial> tls,
    List<Machine> machines,
    RuntimeSettings runtime,
    StoreRole store,
    ControlPlaneRole controlPlane,
    FafnirRole fafnir,
    MuninnRole muninn,
    AndvariRole andvari,
    List<AgentPlacement> agents,
    Map<ProcessRole, List<String>> extraJvmFlags) {

  public Topology {
    if (name == null || name.isBlank()) {
      throw new GimleManifestException("topology name must be a non-blank string");
    }
    if (transport == null) {
      throw new GimleManifestException("topology transport must be set");
    }
    if (tls == null) {
      throw new GimleManifestException("topology tls field must not be null");
    }
    if (runtime == null
        || store == null
        || controlPlane == null
        || fafnir == null
        || muninn == null
        || andvari == null) {
      throw new GimleManifestException("topology sections must not be null");
    }
    machines = List.copyOf(machines);
    agents = List.copyOf(agents);
    final Map<ProcessRole, List<String>> flags = new EnumMap<>(ProcessRole.class);
    extraJvmFlags.forEach((role, roleFlags) -> flags.put(role, List.copyOf(roleFlags)));
    extraJvmFlags = Map.copyOf(flags);
  }

  /** The extra JVM flags for one role; empty when the topology declares none. */
  public List<String> jvmFlags(final ProcessRole role) {
    return extraJvmFlags.getOrDefault(role, List.of());
  }
}
