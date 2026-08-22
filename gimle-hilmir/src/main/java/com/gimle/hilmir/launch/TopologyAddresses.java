package com.gimle.hilmir.launch;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.topology.Machine;
import com.gimle.hilmir.topology.ServiceReplica;
import com.gimle.hilmir.topology.Topology;
import java.util.List;

/**
 * The small set of topology lookups the launch package needs on more than one path (minting an
 * agent's bootstrap token, picking {@code pki init}'s hostnames): resolving a machine name to its
 * host, and a control-plane replica to its bare {@code host:port}. Deliberately not shared with
 * {@code com.gimle.hilmir.plan.LaunchPlanner}'s own private equivalents -- those are
 * package-private to the planner package, and this is a different, downstream package.
 */
final class TopologyAddresses {

  private TopologyAddresses() {}

  static String hostOf(final Topology topology, final String machineName) {
    return topology.machines().stream()
        .filter(m -> m.name().equals(machineName))
        .map(Machine::host)
        .findFirst()
        .orElseThrow(
            () -> new HilmirException("no machine named '" + machineName + "' in topology"));
  }

  /**
   * {@code index}'s control-plane replica's own bare {@code host:port} -- never schemed: {@code
   * gimle-cli}'s own {@code --server} flag (see {@code com.gimle.cli.ControlPlaneClient}) always
   * prepends its own scheme itself from whether TLS system properties are set, so passing it an
   * already-schemed URL would double the scheme instead of setting it.
   */
  static String hostPortOf(final Topology topology, final int index) {
    final List<ServiceReplica> replicas = topology.controlPlane().replicas();
    if (index >= replicas.size()) {
      throw new HilmirException(
          "cannot resolve control-plane replica "
              + index
              + ": topology declares "
              + replicas.size()
              + " control-plane replica(s)");
    }
    final ServiceReplica replica = replicas.get(index);
    return hostOf(topology, replica.machine()) + ":" + replica.port();
  }
}
