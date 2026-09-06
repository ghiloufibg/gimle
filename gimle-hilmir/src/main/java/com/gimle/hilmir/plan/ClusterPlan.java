package com.gimle.hilmir.plan;

import com.gimle.hilmir.HilmirException;
import java.util.Map;
import java.util.TreeSet;

/**
 * {@link LaunchPlanner#plan}'s whole output: one {@link MachinePlan} per machine in the topology.
 */
public record ClusterPlan(Map<String, MachinePlan> byMachine) {

  public ClusterPlan {
    byMachine = Map.copyOf(byMachine);
  }

  /**
   * The plan for exactly one machine, or a failure naming every machine this plan does cover. Every
   * caller that narrows to a single machine -- previewing it, launching it, restarting one of its
   * roles -- goes through here, so a mistyped machine name is rejected identically everywhere
   * rather than quietly resolving to "nothing to do" in whichever caller happens to tolerate a
   * missing entry.
   */
  public MachinePlan requireMachine(final String machineName) {
    final MachinePlan machinePlan = byMachine.get(machineName);
    if (machinePlan == null) {
      throw new HilmirException(
          "no machine named '"
              + machineName
              + "' in this topology -- "
              + (byMachine.isEmpty()
                  ? "this topology plans no processes on any machine"
                  : "machines with planned processes: "
                      + String.join(", ", new TreeSet<>(byMachine.keySet()))));
    }
    return machinePlan;
  }
}
