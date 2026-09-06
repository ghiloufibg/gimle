package com.gimle.hilmir.validate;

import com.gimle.hilmir.topology.AgentPlacement;
import com.gimle.hilmir.topology.Machine;
import com.gimle.hilmir.topology.ServiceReplica;
import com.gimle.hilmir.topology.StoreReplica;
import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.Transport;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The semantic rule catalog a {@link Topology} is checked against: everything the parser's
 * structural checks let through but that would still make the topology undeployable, fragile, or
 * self-defeating. A pure function of its argument -- no I/O, no network reachability checks -- so
 * {@code hilmir validate} can run entirely offline against a topology document alone.
 *
 * <p>Every rule code below is part of this class's public contract: a caller may match on {@link
 * Finding#code()} directly, so a code is never renamed once shipped, only added to.
 */
public final class TopologyValidator {

  private static final Pattern IPV4_LITERAL =
      Pattern.compile(
          "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");

  private TopologyValidator() {}

  public static List<Finding> validate(final Topology topology) {
    final List<Finding> findings = new ArrayList<>();
    final int totalMachines = topology.machines().size();
    final Set<String> machineNames =
        topology.machines().stream()
            .map(Machine::name)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    if (topology.machines().isEmpty()) {
      findings.add(error("NO_MACHINES", "topology declares no machines"));
    }
    checkDuplicateMachines(topology, findings);

    final List<String> storeMachines =
        topology.store().replicas().stream().map(StoreReplica::machine).toList();
    checkRole("store", storeMachines, "NO_STORE", machineNames, totalMachines, findings);
    if (storeMachines.size() == 1) {
      findings.add(warning("SINGLE_STORE", "exactly one store replica: no quorum or failover"));
    }
    if (!storeMachines.isEmpty() && storeMachines.size() % 2 == 0) {
      findings.add(
          warning(
              "STORE_EVEN_REPLICAS",
              "an even store replica count gains no quorum benefit over one fewer replica"));
    }

    final List<String> controlPlaneMachines =
        topology.controlPlane().replicas().stream().map(ServiceReplica::machine).toList();
    checkRole(
        "control plane",
        controlPlaneMachines,
        "NO_CONTROL_PLANE",
        machineNames,
        totalMachines,
        findings);
    if (controlPlaneMachines.size() == 1) {
      findings.add(warning("SINGLE_CONTROL_PLANE", "exactly one control-plane replica"));
    }

    final List<String> fafnirMachines =
        topology.fafnir().replicas().stream().map(ServiceReplica::machine).toList();
    checkRole("fafnir", fafnirMachines, "NO_FAFNIR", machineNames, totalMachines, findings);

    final List<String> muninnMachines =
        topology.muninn().replicas().stream().map(ServiceReplica::machine).toList();
    checkRole("muninn", muninnMachines, null, machineNames, totalMachines, findings);

    final List<String> andvariMachines =
        topology.andvari().replicas().stream().map(ServiceReplica::machine).toList();
    checkRole("andvari", andvariMachines, null, machineNames, totalMachines, findings);

    checkAgents(topology, machineNames, totalMachines, findings);
    checkPortConflicts(topology, findings);
    checkMtls(topology, findings);

    return List.copyOf(findings);
  }

  /**
   * One role's shared shape: an absent-role error (skipped when {@code noneErrorCode} is {@code
   * null}, for the two optional roles Muninn and Andvari), every replica's machine reference, and
   * co-location of two or more of the role's own replicas on one machine.
   */
  private static void checkRole(
      final String roleLabel,
      final List<String> replicaMachines,
      final String noneErrorCode,
      final Set<String> machineNames,
      final int totalMachines,
      final List<Finding> out) {
    if (replicaMachines.isEmpty()) {
      if (noneErrorCode != null) {
        out.add(error(noneErrorCode, "no " + roleLabel + " replicas declared"));
      }
      return;
    }
    for (final String machine : replicaMachines) {
      if (!machineNames.contains(machine)) {
        out.add(
            error(
                "UNKNOWN_MACHINE", roleLabel + " replica references unknown machine: " + machine));
      }
    }
    if (replicaMachines.size() >= 2 && anyDuplicated(replicaMachines)) {
      out.add(
          new Finding(
              "REPLICAS_COLOCATED",
              totalMachines > 1 ? Severity.ERROR : Severity.WARNING,
              roleLabel + " has two or more replicas placed on the same machine"));
    }
  }

  private static void checkAgents(
      final Topology topology,
      final Set<String> machineNames,
      final int totalMachines,
      final List<Finding> out) {
    final List<AgentPlacement> agents = topology.agents();
    if (agents.isEmpty()) {
      out.add(warning("NO_AGENTS", "no agents declared: the cluster can never place a workload"));
      return;
    }
    for (final AgentPlacement agent : agents) {
      if (!machineNames.contains(agent.machine())) {
        out.add(
            error(
                "UNKNOWN_MACHINE",
                "agent " + agent.nodeId() + " references unknown machine: " + agent.machine()));
      }
    }
    final Set<String> seenNodeIds = new HashSet<>();
    final Set<String> reportedDuplicates = new HashSet<>();
    for (final AgentPlacement agent : agents) {
      if (!seenNodeIds.add(agent.nodeId()) && reportedDuplicates.add(agent.nodeId())) {
        out.add(error("DUPLICATE_NODE_ID", "duplicate agent node id: " + agent.nodeId()));
      }
    }
    final List<String> agentMachines = agents.stream().map(AgentPlacement::machine).toList();
    if (agentMachines.size() >= 2 && anyDuplicated(agentMachines)) {
      out.add(
          new Finding(
              "AGENTS_COLOCATED",
              totalMachines > 1 ? Severity.ERROR : Severity.WARNING,
              "more than one agent is placed on the same machine -- the platform's model is one"
                  + " node agent per machine"));
    }
  }

  /**
   * Every port every process kind claims, grouped by machine: two claims on one machine sharing a
   * port number is a real operational conflict regardless of which two process kinds they belong to
   * (a store's own raft port colliding with an agent's own gossip port is just as broken as two
   * store replicas colliding with each other).
   */
  private static void checkPortConflicts(final Topology topology, final List<Finding> out) {
    final Map<String, Map<Integer, List<String>>> claimsByMachine = new LinkedHashMap<>();
    for (final StoreReplica replica : topology.store().replicas()) {
      claim(claimsByMachine, replica.machine(), replica.raftPort(), "store raft port");
      claim(claimsByMachine, replica.machine(), replica.clientPort(), "store client port");
      replica
          .healthPort()
          .ifPresent(port -> claim(claimsByMachine, replica.machine(), port, "store health port"));
    }
    for (final ServiceReplica replica : topology.controlPlane().replicas()) {
      claim(claimsByMachine, replica.machine(), replica.port(), "control plane port");
    }
    for (final ServiceReplica replica : topology.fafnir().replicas()) {
      claim(claimsByMachine, replica.machine(), replica.port(), "fafnir port");
    }
    for (final ServiceReplica replica : topology.muninn().replicas()) {
      claim(claimsByMachine, replica.machine(), replica.port(), "muninn port");
    }
    for (final ServiceReplica replica : topology.andvari().replicas()) {
      claim(claimsByMachine, replica.machine(), replica.port(), "andvari port");
    }
    for (final AgentPlacement agent : topology.agents()) {
      claim(
          claimsByMachine,
          agent.machine(),
          agent.gossipPort(),
          "agent " + agent.nodeId() + " gossip port");
    }
    for (final Map.Entry<String, Map<Integer, List<String>>> machineEntry :
        claimsByMachine.entrySet()) {
      for (final Map.Entry<Integer, List<String>> portEntry : machineEntry.getValue().entrySet()) {
        if (portEntry.getValue().size() > 1) {
          out.add(
              error(
                  "PORT_CONFLICT",
                  "machine "
                      + machineEntry.getKey()
                      + " port "
                      + portEntry.getKey()
                      + " is claimed by more than one process: "
                      + String.join(", ", portEntry.getValue())));
        }
      }
    }
  }

  private static void claim(
      final Map<String, Map<Integer, List<String>>> claimsByMachine,
      final String machine,
      final int port,
      final String owner) {
    claimsByMachine
        .computeIfAbsent(machine, k -> new LinkedHashMap<>())
        .computeIfAbsent(port, k -> new ArrayList<>())
        .add(owner);
  }

  private static void checkMtls(final Topology topology, final List<Finding> out) {
    if (topology.transport() != Transport.MTLS) {
      return;
    }
    if (topology.tls().isEmpty()) {
      out.add(
          error("MTLS_NO_MATERIAL_DIR", "transport is mtls but no tls.materialDir is configured"));
    }
    for (final Machine machine : topology.machines()) {
      if (isIpLiteral(machine.host())) {
        out.add(
            error(
                "MTLS_IP_LITERAL_HOST",
                "machine "
                    + machine.name()
                    + "'s host '"
                    + machine.host()
                    + "' is an IP literal: the platform's PKI mints DNS-only subject alternative"
                    + " names, so hostname verification against an IP literal will fail under"
                    + " mtls -- use a DNS hostname instead"));
      }
    }
  }

  private static boolean isIpLiteral(final String host) {
    if (IPV4_LITERAL.matcher(host).matches()) {
      return true;
    }
    return host.contains(":")
        && host.chars().allMatch(c -> Character.digit(c, 16) >= 0 || c == ':' || c == '.');
  }

  private static boolean anyDuplicated(final List<String> values) {
    final Map<String, Long> counts =
        values.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    return counts.values().stream().anyMatch(count -> count >= 2);
  }

  private static void checkDuplicateMachines(final Topology topology, final List<Finding> out) {
    final Set<String> seen = new HashSet<>();
    final Set<String> reported = new HashSet<>();
    for (final Machine machine : topology.machines()) {
      if (!seen.add(machine.name()) && reported.add(machine.name())) {
        out.add(error("DUPLICATE_MACHINE", "duplicate machine name: " + machine.name()));
      }
    }
  }

  private static Finding error(final String code, final String message) {
    return new Finding(code, Severity.ERROR, message);
  }

  private static Finding warning(final String code, final String message) {
    return new Finding(code, Severity.WARNING, message);
  }
}
