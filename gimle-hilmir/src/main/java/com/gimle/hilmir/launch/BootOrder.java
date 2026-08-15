package com.gimle.hilmir.launch;

import com.gimle.hilmir.plan.ClusterPlan;
import com.gimle.hilmir.plan.MachinePlan;
import com.gimle.hilmir.plan.ProcessCommand;
import com.gimle.hilmir.topology.ProcessRole;
import java.util.ArrayList;
import java.util.List;

/**
 * The fixed global boot sequence a {@link ClusterPlan} is always built in -- store, then Muninn,
 * then Andvari, then Fafnir, then control plane, then agent (see {@code LaunchPlanner}'s own
 * javadoc) -- restated here as an explicit rank rather than relied on via {@link ProcessRole}'s own
 * declaration order, which groups control plane and Fafnir ahead of Muninn/Andvari for readability
 * there, not boot order.
 */
final class BootOrder {

  private static final List<ProcessRole> SEQUENCE =
      List.of(
          ProcessRole.STORE,
          ProcessRole.MUNINN,
          ProcessRole.ANDVARI,
          ProcessRole.FAFNIR,
          ProcessRole.CONTROL_PLANE,
          ProcessRole.AGENT);

  private BootOrder() {}

  private static int rank(final ProcessRole role) {
    final int index = SEQUENCE.indexOf(role);
    if (index < 0) {
      throw new IllegalArgumentException("role " + role + " never appears in a spawned plan");
    }
    return index;
  }

  /**
   * Every command anywhere in {@code clusterPlan} that {@code command} must wait on before it is
   * safe to spawn: strictly earlier in the boot sequence, and on a machine other than {@code
   * machineName}. A same-machine prerequisite needs no entry here -- it was already spawned and
   * awaited earlier in that very machine's own ordered command list, since a {@link MachinePlan}
   * preserves the same relative boot-sequence order the full plan does.
   */
  static List<ProcessCommand> remotePrerequisitesOf(
      final ClusterPlan clusterPlan, final String machineName, final ProcessCommand command) {
    final int commandRank = rank(command.role());
    final List<ProcessCommand> prerequisites = new ArrayList<>();
    for (final MachinePlan machinePlan : clusterPlan.byMachine().values()) {
      if (machinePlan.machine().equals(machineName)) {
        continue;
      }
      for (final ProcessCommand candidate : machinePlan.commands()) {
        if (rank(candidate.role()) < commandRank) {
          prerequisites.add(candidate);
        }
      }
    }
    return prerequisites;
  }
}
