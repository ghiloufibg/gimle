package com.gimle.ivaldi.run;

import com.gimle.hilmir.topology.AgentPlacement;
import com.gimle.hilmir.topology.Machine;
import com.gimle.hilmir.topology.ServiceReplica;
import com.gimle.hilmir.topology.StoreReplica;
import com.gimle.hilmir.topology.Topology;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Refuses a reboot before it starts rather than deep inside {@code MachineLauncher.up}, if a port
 * the topology declares on {@code machine} is already bound -- a cluster started by hand ({@code
 * mvn gimle:store}, say) alongside the one this run is about to boot is the case this exists for; a
 * bind failure many processes deep produces a half-booted process tree that {@code hilmir down}
 * then has to clean up, where refusing up front leaves nothing to undo.
 */
final class PortPreflight {

  private static final int CONNECT_TIMEOUT_MS = 200;

  private PortPreflight() {}

  /** Every port already in use, as {@code "<what> <host>:<port>"} strings, for a run's own log. */
  static List<String> conflictsOn(Topology topology, String machine) {
    String host =
        topology.machines().stream()
            .filter(m -> m.name().equals(machine))
            .findFirst()
            .map(Machine::host)
            .orElse("127.0.0.1");
    List<String> conflicts = new ArrayList<>();
    for (StoreReplica r : topology.store().replicas()) {
      if (r.machine().equals(machine)) {
        checkPort(host, r.raftPort(), "store raft", conflicts);
        checkPort(host, r.clientPort(), "store client", conflicts);
      }
    }
    checkRole(topology.controlPlane().replicas(), machine, host, "controlPlane", conflicts);
    checkRole(topology.fafnir().replicas(), machine, host, "fafnir", conflicts);
    checkRole(topology.muninn().replicas(), machine, host, "muninn", conflicts);
    checkRole(topology.andvari().replicas(), machine, host, "andvari", conflicts);
    for (AgentPlacement agent : topology.agents()) {
      if (agent.machine().equals(machine)) {
        checkPort(host, agent.gossipPort(), "agent " + agent.nodeId() + " gossip", conflicts);
      }
    }
    return conflicts;
  }

  private static void checkRole(
      List<ServiceReplica> replicas,
      String machine,
      String host,
      String role,
      List<String> conflicts) {
    for (ServiceReplica r : replicas) {
      if (r.machine().equals(machine)) {
        checkPort(host, r.port(), role, conflicts);
      }
    }
  }

  private static void checkPort(String host, int port, String what, List<String> conflicts) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
      conflicts.add(what + " " + host + ":" + port);
    } catch (java.io.IOException notInUse) {
      // Connection refused/timed out means nothing is listening there yet -- the expected case.
    }
  }
}
