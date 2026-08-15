package com.gimle.hilmir.topology;

import java.util.List;

/**
 * The control plane's declared replica placements. Zero replicas parses fine -- {@code
 * TopologyValidator}'s {@code NO_CONTROL_PLANE} rule flags it as an error, not the parser.
 */
public record ControlPlaneRole(List<ServiceReplica> replicas) {

  public ControlPlaneRole {
    replicas = List.copyOf(replicas);
  }
}
