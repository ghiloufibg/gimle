package com.gimle.hilmir.topology;

import com.gimle.core.exception.GimleManifestException;
import java.util.List;

/** One node agent placement: its machine, node id, gossip port, and advertised placement labels. */
public record AgentPlacement(String machine, String nodeId, int gossipPort, List<String> labels) {

  public AgentPlacement {
    if (machine == null || machine.isBlank()) {
      throw new GimleManifestException("an agent's machine must be a non-blank string");
    }
    if (nodeId == null || nodeId.isBlank()) {
      throw new GimleManifestException("an agent's nodeId must be a non-blank string");
    }
    Ports.requireValid(gossipPort, "agent " + nodeId + "'s gossip port");
    labels = List.copyOf(labels);
  }
}
