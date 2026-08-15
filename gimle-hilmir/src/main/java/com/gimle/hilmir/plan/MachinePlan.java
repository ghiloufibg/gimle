package com.gimle.hilmir.plan;

import com.gimle.core.exception.GimleManifestException;
import java.util.List;

/**
 * One machine's own share of a {@link ClusterPlan}: every process it hosts, already ordered the way
 * {@link LaunchPlanner} orders every process globally (stores, then Muninn, then Andvari, then
 * Fafnir, then control planes, then agents) filtered down to just this machine's own commands.
 */
public record MachinePlan(String machine, List<ProcessCommand> commands) {

  public MachinePlan {
    if (machine == null || machine.isBlank()) {
      throw new GimleManifestException("a machine plan must declare a non-blank machine name");
    }
    commands = List.copyOf(commands);
  }
}
