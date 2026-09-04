package com.gimle.mavenplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link BootstrapMojo#spawnAgent} needs a live Maven session to resolve classpaths at all, but the
 * actual command line it hands to the spawned process is a pure function of its own inputs, split
 * out into {@link BootstrapMojo#buildAgentCommand} specifically so it can be asserted here without
 * any of that machinery -- the same seam {@code FlakyTestsMojoTest} exercises for {@link
 * FlakyTestsMojo}. {@link BootstrapMojo#portsAlreadyInUse} gets the same treatment for the
 * pre-flight port-collision check, with a fake predicate standing in for an actual socket bind.
 */
class BootstrapMojoTest {

  @Test
  void points_the_agent_at_a_data_root_under_the_bootstrap_base_directory() {
    Path base = Path.of("/tmp/gimle-bootstrap");

    List<String> command =
        BootstrapMojo.buildAgentCommand(
            base,
            false,
            Path.of("/tmp/gimle-bootstrap/tls"),
            "127.0.0.1",
            null,
            "agent.jar",
            "worker.jar",
            "java",
            8080,
            9092,
            9093,
            9094);

    assertTrue(
        command.contains("-Dgimle.data.root=" + base.resolve("agent-data")),
        "expected the agent's own data root pointed at a sibling of the other spawned "
            + "processes' state directories, got: "
            + command);
  }

  @Test
  void the_agent_data_root_is_wired_regardless_of_tls_mode() {
    Path base = Path.of("/tmp/gimle-bootstrap-tls");

    List<String> command =
        BootstrapMojo.buildAgentCommand(
            base,
            true,
            base.resolve("tls"),
            "gimle-node",
            "bootstrap-token-value",
            "agent.jar",
            "worker.jar",
            "java",
            8080,
            9092,
            9093,
            9094);

    assertTrue(command.contains("-Dgimle.data.root=" + base.resolve("agent-data")));
    assertFalse(
        command.stream().anyMatch(arg -> arg.equals("-Dgimle.data.root=gimle-data")),
        "must never fall back to AgentMain's own relative default once a base dir is known");
  }

  @Test
  void the_agent_command_uses_the_overridden_ports_not_the_shared_defaults() {
    Path base = Path.of("/tmp/gimle-bootstrap-custom-ports");

    List<String> command =
        BootstrapMojo.buildAgentCommand(
            base,
            false,
            base.resolve("tls"),
            "127.0.0.1",
            null,
            "agent.jar",
            "worker.jar",
            "java",
            18080,
            19092,
            19093,
            19094);

    assertTrue(command.contains("http://127.0.0.1:18080"));
    assertTrue(command.contains("-Dgimle.agent.fafnirEndpoint=127.0.0.1:19092"));
    assertTrue(command.contains("-Dgimle.agent.muninnEndpoint=127.0.0.1:19093"));
    assertTrue(command.contains("-Dgimle.agent.andvariEndpoint=127.0.0.1:19094"));
  }

  @Test
  void a_free_port_set_reports_no_collisions() {
    Map<String, Integer> ports = new LinkedHashMap<>();
    ports.put("store client", 9091);
    ports.put("control plane", 8080);

    List<String> inUse = BootstrapMojo.portsAlreadyInUse(ports, port -> false);

    assertTrue(inUse.isEmpty());
  }

  @Test
  void a_port_already_listening_is_reported_by_its_own_label() {
    Map<String, Integer> ports = new LinkedHashMap<>();
    ports.put("store client", 9091);
    ports.put("control plane", 8080);

    List<String> inUse = BootstrapMojo.portsAlreadyInUse(ports, port -> port == 8080);

    assertEquals(List.of("control plane = 8080"), inUse);
  }

  @Test
  void every_colliding_port_is_reported_not_just_the_first() {
    Map<String, Integer> ports = new LinkedHashMap<>();
    ports.put("store client", 9091);
    ports.put("control plane", 8080);

    List<String> inUse = BootstrapMojo.portsAlreadyInUse(ports, port -> true);

    assertEquals(List.of("store client = 9091", "control plane = 8080"), inUse);
  }
}
