package com.gimle.hilmir.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.plan.ClusterPlan;
import com.gimle.hilmir.plan.MachinePlan;
import com.gimle.hilmir.plan.ProcessCommand;
import com.gimle.hilmir.topology.ProcessRole;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BootOrderTest {

  private static ProcessCommand command(
      final ProcessRole role, final String id, final String machine, final String readiness) {
    return new ProcessCommand(
        role,
        id,
        machine,
        List.of("java", "-cp", "cp", "Main"),
        id + ".log",
        Path.of("/data/" + id),
        readiness,
        false);
  }

  private static Set<String> ids(final List<ProcessCommand> commands) {
    return commands.stream().map(ProcessCommand::id).collect(Collectors.toSet());
  }

  @Test
  void an_agent_waits_on_every_earlier_role_command_placed_on_a_different_machine() {
    final ProcessCommand store = command(ProcessRole.STORE, "store-0", "m1", "m1:9091");
    final ProcessCommand controlPlane =
        command(ProcessRole.CONTROL_PLANE, "controlplane-0", "m1", "m1:8080");
    final ProcessCommand agent = command(ProcessRole.AGENT, "agent-a", "m2", "m2:9090");

    final Map<String, MachinePlan> byMachine = new LinkedHashMap<>();
    byMachine.put("m1", new MachinePlan("m1", List.of(store, controlPlane)));
    byMachine.put("m2", new MachinePlan("m2", List.of(agent)));
    final ClusterPlan clusterPlan = new ClusterPlan(byMachine);

    final List<ProcessCommand> prerequisites =
        BootOrder.remotePrerequisitesOf(clusterPlan, "m2", agent);

    assertEquals(Set.of("store-0", "controlplane-0"), ids(prerequisites));
  }

  @Test
  void a_same_machine_command_is_never_its_own_prerequisite_even_when_earlier_in_boot_order() {
    final ProcessCommand store = command(ProcessRole.STORE, "store-0", "m1", "m1:9091");
    final ProcessCommand controlPlane =
        command(ProcessRole.CONTROL_PLANE, "controlplane-0", "m1", "m1:8080");

    final Map<String, MachinePlan> byMachine = new LinkedHashMap<>();
    byMachine.put("m1", new MachinePlan("m1", List.of(store, controlPlane)));
    final ClusterPlan clusterPlan = new ClusterPlan(byMachine);

    final List<ProcessCommand> prerequisites =
        BootOrder.remotePrerequisitesOf(clusterPlan, "m1", controlPlane);

    assertTrue(prerequisites.isEmpty());
  }

  @Test
  void a_store_command_has_no_prerequisites_since_nothing_boots_before_it() {
    final ProcessCommand store1 = command(ProcessRole.STORE, "store-0", "m1", "m1:9091");
    final ProcessCommand store2 = command(ProcessRole.STORE, "store-1", "m2", "m2:9091");

    final Map<String, MachinePlan> byMachine = new LinkedHashMap<>();
    byMachine.put("m1", new MachinePlan("m1", List.of(store1)));
    byMachine.put("m2", new MachinePlan("m2", List.of(store2)));
    final ClusterPlan clusterPlan = new ClusterPlan(byMachine);

    assertTrue(BootOrder.remotePrerequisitesOf(clusterPlan, "m1", store1).isEmpty());
  }

  @Test
  void a_mixed_role_machine_only_waits_on_roles_strictly_earlier_than_its_own_command() {
    // m1 hosts both a Fafnir replica and a control plane; m2's agent must wait on both remote
    // commands, but not on its own machine's own Muninn replica -- co-located, so excluded by the
    // different-machine filter regardless of its earlier rank.
    final ProcessCommand fafnir = command(ProcessRole.FAFNIR, "fafnir-0", "m1", "m1:9092");
    final ProcessCommand controlPlane =
        command(ProcessRole.CONTROL_PLANE, "controlplane-0", "m1", "m1:8080");
    final ProcessCommand muninn = command(ProcessRole.MUNINN, "muninn-0", "m2", "m2:9093");
    final ProcessCommand agent = command(ProcessRole.AGENT, "agent-a", "m2", "m2:9090");

    final Map<String, MachinePlan> byMachine = new LinkedHashMap<>();
    byMachine.put("m1", new MachinePlan("m1", List.of(fafnir, controlPlane)));
    byMachine.put("m2", new MachinePlan("m2", List.of(muninn, agent)));
    final ClusterPlan clusterPlan = new ClusterPlan(byMachine);

    final List<ProcessCommand> prerequisites =
        BootOrder.remotePrerequisitesOf(clusterPlan, "m2", agent);

    assertEquals(Set.of("fafnir-0", "controlplane-0"), ids(prerequisites));
  }
}
