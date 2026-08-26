package com.gimle.mavenplugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link BootstrapMojo#spawnAgent} needs a live Maven session to resolve classpaths at all, but the
 * actual command line it hands to the spawned process is a pure function of its own inputs, split
 * out into {@link BootstrapMojo#buildAgentCommand} specifically so it can be asserted here without
 * any of that machinery -- the same seam {@code FlakyTestsMojoTest} exercises for {@link
 * FlakyTestsMojo}.
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
            "java");

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
            "java");

    assertTrue(command.contains("-Dgimle.data.root=" + base.resolve("agent-data")));
    assertFalse(
        command.stream().anyMatch(arg -> arg.equals("-Dgimle.data.root=gimle-data")),
        "must never fall back to AgentMain's own relative default once a base dir is known");
  }
}
