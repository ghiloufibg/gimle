package com.gimle.hilmir.topology;

import com.gimle.core.exception.GimleManifestException;

/**
 * One replica of a single-port process kind (control plane, Fafnir, Muninn, or Andvari): which
 * machine it's placed on and the port it listens on there.
 */
public record ServiceReplica(String machine, int port) {

  public ServiceReplica {
    if (machine == null || machine.isBlank()) {
      throw new GimleManifestException("a replica's machine must be a non-blank string");
    }
    Ports.requireValid(port, "replica port on machine " + machine);
  }
}
