package com.gimle.hilmir.topology;

import com.gimle.core.exception.GimleManifestException;
import java.util.Optional;

/**
 * One store replica: which machine it's placed on, its two distinct listening ports (Raft peer
 * traffic and client RPC traffic), and optionally the port its read-only HTTP health endpoint
 * listens on. A store replica serves no health endpoint at all unless {@code healthPort} names one
 * -- unlike every other role here, whose own primary port already answers a status request -- so
 * leaving it unset is a real configuration, not a missing value to default.
 */
public record StoreReplica(
    String machine, int raftPort, int clientPort, Optional<Integer> healthPort) {

  public StoreReplica {
    if (machine == null || machine.isBlank()) {
      throw new GimleManifestException("a store replica's machine must be a non-blank string");
    }
    Ports.requireValid(raftPort, "store raft port on machine " + machine);
    Ports.requireValid(clientPort, "store client port on machine " + machine);
    if (healthPort == null) {
      throw new GimleManifestException("a store replica's health port must not be null");
    }
    healthPort.ifPresent(
        port -> Ports.requireValid(port, "store health port on machine " + machine));
  }

  public StoreReplica(final String machine, final int raftPort, final int clientPort) {
    this(machine, raftPort, clientPort, Optional.empty());
  }
}
