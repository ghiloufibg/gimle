package com.gimle.hilmir.topology;

import com.gimle.core.exception.GimleManifestException;

/**
 * One store replica: which machine it's placed on, and its two distinct listening ports (Raft peer
 * traffic and client RPC traffic).
 */
public record StoreReplica(String machine, int raftPort, int clientPort) {

  public StoreReplica {
    if (machine == null || machine.isBlank()) {
      throw new GimleManifestException("a store replica's machine must be a non-blank string");
    }
    Ports.requireValid(raftPort, "store raft port on machine " + machine);
    Ports.requireValid(clientPort, "store client port on machine " + machine);
  }
}
