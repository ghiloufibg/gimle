package com.gimle.hilmir.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.topology.ProcessRole;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link MachineLauncher#insertBootstrapTokenFlag} directly: the bootstrap-token system
 * property must land before a command's own {@code -cp}, never appended at the end -- java only
 * recognizes a {@code -D} flag as a JVM option ahead of the main class; appended after it (as this
 * method used to do, silently) it is parsed as a plain, ignored program argument instead. This bug
 * meant an mtls agent's own bootstrap token never actually reached {@code AgentMain}, discovered
 * only by running a real agent process against a real mtls cluster end to end -- exactly the gap a
 * pure unit test of the insertion logic itself closes without needing that whole cluster.
 */
class MachineLauncherBootstrapTokenTest {

  @Test
  void inserts_the_token_flag_immediately_before_the_first_cp_flag() {
    final List<String> commandLine =
        List.of(
            "java",
            "-Dgimle.transport.protocol=tls",
            "-cp",
            "some.jar",
            "com.gimle.agent.AgentMain",
            "node-a",
            "https://cp:8080");

    final List<String> result =
        MachineLauncher.insertBootstrapTokenFlag(
            commandLine, "the-token", ProcessRole.AGENT, "agent-node-a");

    final int tokenIndex = result.indexOf("-Dgimle.tls.bootstrapToken=the-token");
    final int cpIndex = result.indexOf("-cp");
    assertTrue(tokenIndex >= 0, "the token flag must be present");
    assertTrue(tokenIndex < cpIndex, "the token flag must come before -cp, not after it");
    assertEquals(commandLine.size() + 1, result.size());
  }

  @Test
  void never_lands_after_the_main_class_or_any_positional_argument() {
    final List<String> commandLine =
        List.of(
            "java",
            "-cp",
            "some.jar",
            "com.gimle.agent.AgentMain",
            "node-a",
            "https://cp:8080",
            "gossip:9090",
            "-",
            // The worker-launch tail an agent's own command line carries -- the token must never
            // end up anywhere in or after this either.
            "java",
            "-cp",
            "worker.jar",
            "com.gimle.worker.WorkerMain");

    final List<String> result =
        MachineLauncher.insertBootstrapTokenFlag(
            commandLine, "the-token", ProcessRole.AGENT, "agent-node-a");

    final int tokenIndex = result.indexOf("-Dgimle.tls.bootstrapToken=the-token");
    final int mainClassIndex = result.indexOf("com.gimle.agent.AgentMain");
    assertTrue(tokenIndex < mainClassIndex, "the token flag must precede AgentMain itself");
  }

  @Test
  void fails_clearly_when_the_command_line_has_no_cp_to_insert_before() {
    final List<String> commandLine = List.of("java", "-version");

    final HilmirException e =
        assertThrows(
            HilmirException.class,
            () ->
                MachineLauncher.insertBootstrapTokenFlag(
                    commandLine, "the-token", ProcessRole.AGENT, "agent-node-a"));

    assertTrue(e.getMessage().contains("agent-node-a"));
  }
}
