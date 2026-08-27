package com.gimle.ragnarok.target.inventory;

import com.gimle.hilmir.remote.ResolvedSshTarget;
import com.gimle.hilmir.topology.Machine;
import com.gimle.hilmir.topology.SshSettings;
import com.gimle.ragnarok.RagnarokException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The managed-inventory document: the machines Ragnarök may SSH into, which {@link ManagedRoleSpec}
 * backs each store/control-plane/Fafnir/Muninn/Andvari replica index -- the same ordering {@link
 * com.gimle.ragnarok.target.ClusterTarget}'s own indexed accessors (`store(int)`,
 * `controlPlane(int)`, ...) already expect -- and which {@link AgentSpec} backs each node id, for
 * resolving a worker's real OS pid. Every role's/agent's own {@code machine} field is validated
 * against the declared {@link #machines()} here, once, rather than at every lookup.
 */
public record InventorySpec(
    List<Machine> machines,
    List<ManagedRoleSpec> store,
    List<ManagedRoleSpec> controlPlane,
    List<ManagedRoleSpec> fafnir,
    List<ManagedRoleSpec> muninn,
    List<ManagedRoleSpec> andvari,
    List<AgentSpec> agents) {

  public InventorySpec {
    machines = List.copyOf(machines);
    store = List.copyOf(store);
    controlPlane = List.copyOf(controlPlane);
    fafnir = List.copyOf(fafnir);
    muninn = List.copyOf(muninn);
    andvari = List.copyOf(andvari);
    agents = List.copyOf(agents);
    if (machines.isEmpty()) {
      throw new RagnarokException("inventory must declare at least one machine");
    }
    final Set<String> names = new LinkedHashSet<>();
    for (final Machine m : machines) {
      if (!names.add(m.name())) {
        throw new RagnarokException("duplicate machine name in inventory: " + m.name());
      }
    }
    requireKnownMachines(names, store, ManagedRoleSpec::machine, ManagedRoleSpec::id);
    requireKnownMachines(names, controlPlane, ManagedRoleSpec::machine, ManagedRoleSpec::id);
    requireKnownMachines(names, fafnir, ManagedRoleSpec::machine, ManagedRoleSpec::id);
    requireKnownMachines(names, muninn, ManagedRoleSpec::machine, ManagedRoleSpec::id);
    requireKnownMachines(names, andvari, ManagedRoleSpec::machine, ManagedRoleSpec::id);
    requireKnownMachines(names, agents, AgentSpec::machine, AgentSpec::nodeId);
  }

  private static <T> void requireKnownMachines(
      final Set<String> knownMachines,
      final List<T> entries,
      final java.util.function.Function<T, String> machineOf,
      final java.util.function.Function<T, String> labelOf) {
    for (final T entry : entries) {
      if (!knownMachines.contains(machineOf.apply(entry))) {
        throw new RagnarokException(
            labelOf.apply(entry)
                + " references unknown machine '"
                + machineOf.apply(entry)
                + "' (declared machines: "
                + knownMachines
                + ")");
      }
    }
  }

  /** The declared {@link Machine} named {@code name}, resolved once per lookup. */
  public Optional<Machine> machineNamed(final String name) {
    return machines.stream().filter(m -> m.name().equals(name)).findFirst();
  }

  /** The declared {@link AgentSpec} for Gimlé node id {@code nodeId}, if one was declared. */
  public Optional<AgentSpec> agentFor(final String nodeId) {
    return agents.stream().filter(a -> a.nodeId().equals(nodeId)).findFirst();
  }

  /**
   * Builds a {@link ResolvedSshTarget} for {@code role} -- a single per-machine {@code ssh:} tier
   * plus this document's own defaults, deliberately simpler than {@code gimle-hilmir}'s fuller
   * 3-tier (CLI flags / per-machine / topology-wide) precedence: a v1 inventory document only ever
   * needs one tier, since Ragnarök has no CLI-flag equivalent of {@code --ssh-user} today.
   */
  public ResolvedSshTarget resolvedTarget(final ManagedRoleSpec role) {
    return resolvedTarget(machineOrThrow(role.machine(), role.id()));
  }

  /** Same as {@link #resolvedTarget(ManagedRoleSpec)}, for an agent instead of a managed role. */
  public ResolvedSshTarget resolvedTarget(final AgentSpec agent) {
    return resolvedTarget(machineOrThrow(agent.machine(), agent.nodeId()));
  }

  /**
   * Same as {@link #resolvedTarget(ManagedRoleSpec)}, for pinning a machine's host key up front.
   */
  public ResolvedSshTarget resolvedTarget(final Machine machine) {
    final SshSettings ssh = machine.ssh().orElse(SshSettings.EMPTY);
    return new ResolvedSshTarget(
        machine.name(),
        machine.host(),
        ssh.user(),
        ssh.port(),
        ssh.identityFile(),
        ResolvedSshTarget.DEFAULT_INSTALL_DIR,
        Optional.empty(),
        machine.sshHostKeyFingerprint());
  }

  private Machine machineOrThrow(final String machineName, final String label) {
    return machineNamed(machineName)
        .orElseThrow(
            () ->
                new RagnarokException(label + " references unknown machine '" + machineName + "'"));
  }
}
