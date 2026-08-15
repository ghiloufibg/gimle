package com.gimle.hilmir.topology;

import java.util.List;

/** Andvari's declared replica placements. An optional role: an empty list means "disabled." */
public record AndvariRole(List<ServiceReplica> replicas) {

  public AndvariRole {
    replicas = List.copyOf(replicas);
  }
}
