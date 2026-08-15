package com.gimle.hilmir.topology;

import java.util.List;

/** Muninn's declared replica placements. An optional role: an empty list means "disabled." */
public record MuninnRole(List<ServiceReplica> replicas) {

  public MuninnRole {
    replicas = List.copyOf(replicas);
  }
}
