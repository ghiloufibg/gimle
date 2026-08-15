package com.gimle.holmgang.utgard;

import java.util.List;

/**
 * Small, readable YAML builders for the topology documents Utgard writes into each container --
 * pure string assembly, no Docker, no file I/O, so {@code UtgardTopologiesTest} can round-trip the
 * output through {@code gimle-hilmir}'s own real {@code TopologyParser}/{@code TopologyValidator}
 * without a daemon. Every machine name doubles as its own {@code host} value: on Utgard's shared
 * Docker network, a container's network alias *is* its DNS-resolvable hostname, so {@code host:
 * helm-1} is exactly as real an address as a production topology's own {@code host:
 * gimle-1.example.com} -- unlike {@code gimle-holmgang}'s own single-machine {@code GimleCluster},
 * which fakes multi-machine-ness with dynamically leased loopback ports on one host.
 */
public final class UtgardTopologies {

  private UtgardTopologies() {}

  /**
   * A plaintext topology spreading one store replica, one control-plane replica, and one Fafnir
   * replica across {@code machines} (as directed by the three placement arguments), plus one agent
   * per entry in {@code agentMachines}. Andvari and Muninn stay disabled -- neither is exercised by
   * any of Utgard's own scenarios, so declaring them would only add boot time.
   */
  public static String plaintext(
      final String clusterName,
      final List<String> machines,
      final String storeMachine,
      final String controlPlaneMachine,
      final String fafnirMachine,
      final List<String> agentMachines) {
    final StringBuilder yaml = new StringBuilder();
    yaml.append("name: ").append(clusterName).append('\n');
    appendMachines(yaml, machines);
    yaml.append("store:\n  replicas:\n    - {machine: ").append(storeMachine).append("}\n");
    yaml.append("controlPlane:\n  replicas:\n    - {machine: ")
        .append(controlPlaneMachine)
        .append("}\n");
    yaml.append("fafnir:\n  keyFile: ")
        .append(FAFNIR_KEY_FILE)
        .append("\n  replicas:\n    - {machine: ")
        .append(fafnirMachine)
        .append("}\n");
    appendAgents(yaml, agentMachines);
    return yaml.toString();
  }

  /**
   * An mtls topology with every server-side role (store, control plane, Fafnir) on one {@code
   * serverMachine} -- the platform's own PKI mints DNS-only, single-hostname server leaves (see
   * {@code MTLS_SINGLE_HOSTNAME_PKI} in {@code gimle-hilmir}'s validator catalog), so a server role
   * split across machines would need material this suite cannot generate. The agent(s) in {@code
   * agentMachines} still run on their own separate machine(s): an agent only ever dials {@code
   * serverMachine} by name (it presents no server identity of its own that anything here verifies
   * by hostname), which is exactly the part of a genuinely multi-machine mtls topology that does
   * work today -- and the part that only means anything over a real, independently resolvable
   * hostname rather than {@code localhost}.
   */
  public static String mtls(
      final String clusterName,
      final List<String> machines,
      final String serverMachine,
      final List<String> agentMachines,
      final String materialDir) {
    final StringBuilder yaml = new StringBuilder();
    yaml.append("name: ").append(clusterName).append('\n');
    yaml.append("transport: mtls\n");
    yaml.append("tls:\n  materialDir: ").append(materialDir).append('\n');
    appendMachines(yaml, machines);
    yaml.append("store:\n  replicas:\n    - {machine: ").append(serverMachine).append("}\n");
    yaml.append("controlPlane:\n  replicas:\n    - {machine: ").append(serverMachine).append("}\n");
    yaml.append("fafnir:\n  keyFile: ")
        .append(FAFNIR_KEY_FILE)
        .append("\n  replicas:\n    - {machine: ")
        .append(serverMachine)
        .append("}\n");
    appendAgents(yaml, agentMachines);
    return yaml.toString();
  }

  private static final String FAFNIR_KEY_FILE = "/opt/gimle/data/fafnir-secret.key";

  private static void appendMachines(final StringBuilder yaml, final List<String> machines) {
    yaml.append("machines:\n");
    for (final String machine : machines) {
      yaml.append("  - {name: ").append(machine).append(", host: ").append(machine).append("}\n");
    }
  }

  private static void appendAgents(final StringBuilder yaml, final List<String> agentMachines) {
    yaml.append("agents:\n");
    for (final String machine : agentMachines) {
      yaml.append("  - {machine: ")
          .append(machine)
          .append(", nodeId: ")
          .append(nodeIdFor(machine))
          .append("}\n");
    }
  }

  /** The node id {@link #plaintext}/{@link #mtls} assign an agent placed on {@code machine}. */
  public static String nodeIdFor(final String machine) {
    return "node-" + machine;
  }
}
