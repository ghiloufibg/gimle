package com.gimle.hilmir.topology;

import java.util.List;

/**
 * The store's declared replica placements. Zero replicas parses fine -- {@code TopologyValidator}'s
 * {@code NO_STORE} rule flags it as an error, not the parser.
 */
public record StoreRole(List<StoreReplica> replicas) {

  public StoreRole {
    replicas = List.copyOf(replicas);
  }
}
