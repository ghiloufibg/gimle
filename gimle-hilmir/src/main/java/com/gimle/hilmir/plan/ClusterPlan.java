package com.gimle.hilmir.plan;

import java.util.Map;

/**
 * {@link LaunchPlanner#plan}'s whole output: one {@link MachinePlan} per machine in the topology.
 */
public record ClusterPlan(Map<String, MachinePlan> byMachine) {

  public ClusterPlan {
    byMachine = Map.copyOf(byMachine);
  }
}
