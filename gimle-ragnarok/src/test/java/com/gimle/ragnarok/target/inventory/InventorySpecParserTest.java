package com.gimle.ragnarok.target.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.ragnarok.RagnarokException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Parsing and validation of the {@code inventory:} block. */
final class InventorySpecParserTest {

  private static InventorySpec parse(final String yaml) {
    final Map<?, ?> root =
        (Map<?, ?>) new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
    return InventorySpecParser.parse(root);
  }

  @Test
  void parses_machines_and_a_role_per_process_kind() {
    final InventorySpec spec =
        parse(
            """
            machines:
              - name: node-1
                host: 10.0.1.10
                ssh: {user: gimle, identityFile: /home/op/.ssh/id_ed25519}
              - name: node-2
                host: 10.0.1.11
            store:
              - {machine: node-1, id: store-0, pidFile: /opt/gimle/data/store-0.pid,
                 logFile: /opt/gimle/data/store-0.log, command: [java, -jar, store.jar]}
              - {machine: node-2, id: store-1, pidFile: /opt/gimle/data/store-1.pid,
                 logFile: /opt/gimle/data/store-1.log, command: [java, -jar, store.jar]}
            controlPlane:
              - {machine: node-1, id: controlplane-0, pidFile: /opt/gimle/data/cp-0.pid,
                 logFile: /opt/gimle/data/cp-0.log, command: [java, -jar, cp.jar]}
            """);
    assertEquals(2, spec.machines().size());
    assertEquals("node-1", spec.machines().get(0).name());
    assertEquals(2, spec.store().size());
    assertEquals("store-0", spec.store().get(0).id());
    assertEquals(1, spec.controlPlane().size());
    assertTrue(spec.fafnir().isEmpty());
    assertTrue(spec.machineNamed("node-1").isPresent());
    assertTrue(spec.machineNamed("node-1").get().ssh().isPresent());
    assertTrue(spec.machineNamed("node-2").get().ssh().isEmpty());
  }

  @Test
  void a_role_naming_an_unknown_machine_is_rejected() {
    assertThrows(
        RagnarokException.class,
        () ->
            parse(
                """
                machines:
                  - {name: node-1, host: 10.0.1.10}
                store:
                  - {machine: node-does-not-exist, id: store-0, pidFile: /a.pid,
                     logFile: /a.log, command: [java]}
                """));
  }

  @Test
  void duplicate_machine_names_are_rejected() {
    assertThrows(
        RagnarokException.class,
        () ->
            parse(
                """
                machines:
                  - {name: node-1, host: 10.0.1.10}
                  - {name: node-1, host: 10.0.1.11}
                """));
  }

  @Test
  void no_machines_is_rejected() {
    assertThrows(RagnarokException.class, () -> parse("store: []\n"));
  }

  @Test
  void a_role_with_no_command_is_rejected() {
    assertThrows(
        RagnarokException.class,
        () ->
            parse(
                """
                machines:
                  - {name: node-1, host: 10.0.1.10}
                store:
                  - {machine: node-1, id: store-0, pidFile: /a.pid, logFile: /a.log, command: []}
                """));
  }

  @Test
  void a_machine_with_no_host_is_rejected() {
    // Caught by YamlParsing.requireString before Machine's own compact constructor ever runs.
    assertThrows(RagnarokException.class, () -> parse("machines:\n  - {name: node-1}\n"));
  }

  @Test
  void parses_agents_and_resolves_them_by_node_id() {
    final InventorySpec spec =
        parse(
            """
            machines:
              - {name: node-1, host: 10.0.1.10}
            agents:
              - {machine: node-1, nodeId: node-abc, logRoot: /opt/gimle/data/agent-node-abc-logs}
            """);
    assertEquals(1, spec.agents().size());
    assertTrue(spec.agentFor("node-abc").isPresent());
    assertEquals("node-1", spec.agentFor("node-abc").get().machine());
    assertTrue(spec.agentFor("node-does-not-exist").isEmpty());
  }

  @Test
  void an_agent_naming_an_unknown_machine_is_rejected() {
    assertThrows(
        RagnarokException.class,
        () ->
            parse(
                """
                machines:
                  - {name: node-1, host: 10.0.1.10}
                agents:
                  - {machine: node-does-not-exist, nodeId: node-abc, logRoot: /a/logs}
                """));
  }

  @Test
  void an_agent_with_no_log_root_is_rejected() {
    assertThrows(
        RagnarokException.class,
        () ->
            parse(
                """
                machines:
                  - {name: node-1, host: 10.0.1.10}
                agents:
                  - {machine: node-1, nodeId: node-abc}
                """));
  }

  @Test
  void parses_a_store_roles_raft_port_and_defaults_it_to_empty_for_every_other_role_kind() {
    final InventorySpec spec =
        parse(
            """
            machines:
              - {name: node-1, host: 10.0.1.10}
            store:
              - {machine: node-1, id: store-0, pidFile: /a.pid, logFile: /a.log,
                 command: [java], raftPort: 9080}
            controlPlane:
              - {machine: node-1, id: controlplane-0, pidFile: /b.pid, logFile: /b.log,
                 command: [java]}
            """);
    assertEquals(9080, spec.store().get(0).raftPort().orElseThrow());
    assertTrue(spec.controlPlane().get(0).raftPort().isEmpty());
  }

  @Test
  void sudo_defaults_to_false_and_can_be_declared_true() {
    assertFalse(parse("machines:\n  - {name: node-1, host: 10.0.1.10}\n").sudo());
    final InventorySpec spec =
        parse("machines:\n  - {name: node-1, host: 10.0.1.10}\nsudo: true\n");
    assertTrue(spec.sudo());
  }
}
