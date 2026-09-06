package com.gimle.hilmir.upgrade;

import static com.gimle.hilmir.topology.ProcessRole.CONTROL_PLANE;
import static com.gimle.hilmir.topology.ProcessRole.FAFNIR;
import static com.gimle.hilmir.topology.ProcessRole.STORE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.HilmirMain;
import com.gimle.hilmir.launch.RunRecord;
import com.gimle.hilmir.topology.ProcessRole;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link UpgradeClusterCommand}'s own flag validation and orchestration loop. The
 * stop-on-first-failure behavior is proven through the {@link RoleRestarter} seam rather than a
 * real process restart -- {@link com.gimle.hilmir.launch.MachineLauncherRestartRoleIntegrationTest}
 * already covers a real restart end to end, so this class stays focused on the verb's own
 * flag-parsing and per-role sequencing logic.
 */
class UpgradeClusterCommandTest {

  @TempDir Path tempDir;

  private static final String TOPOLOGY_YAML =
      """
      name: fixture
      machines:
        - {name: m1, host: 127.0.0.1}
      store:
        replicas:
          - {machine: m1}
      controlPlane:
        replicas:
          - {machine: m1}
      fafnir:
        keyFile: /key
        replicas:
          - {machine: m1}
      """;

  private Path topologyFile() throws IOException {
    final Path file = tempDir.resolve("topology.yaml");
    Files.writeString(file, TOPOLOGY_YAML, StandardCharsets.UTF_8);
    return file;
  }

  private static PrintStream capture(final ByteArrayOutputStream buffer) {
    return new PrintStream(buffer, true, StandardCharsets.UTF_8);
  }

  private static RoleRestarter neverCalled() {
    return (topology, machine, role, runtime, out) -> {
      throw new AssertionError("restarter should never have been called for role " + role);
    };
  }

  @Test
  void missing_new_classpath_is_reported_as_a_usage_error_before_any_restart_is_attempted()
      throws IOException {
    final List<String> args = List.of("-f", topologyFile().toString(), "--machine", "m1");

    final HilmirException e =
        assertThrows(
            HilmirException.class,
            () ->
                UpgradeClusterCommand.run(
                    args, capture(new ByteArrayOutputStream()), neverCalled()));

    assertTrue(e.getMessage().contains("--new-classpath"));
  }

  @Test
  void role_agent_is_rejected_with_a_clear_error_before_any_restart_is_attempted()
      throws IOException {
    final List<String> args =
        List.of(
            "-f",
            topologyFile().toString(),
            "--machine",
            "m1",
            "--new-classpath",
            "/new/lib/*",
            "--role",
            "AGENT");

    final HilmirException e =
        assertThrows(
            HilmirException.class,
            () ->
                UpgradeClusterCommand.run(
                    args, capture(new ByteArrayOutputStream()), neverCalled()));

    assertTrue(e.getMessage().contains("AGENT"));
    assertTrue(e.getMessage().contains("not supported"));
  }

  @Test
  void role_agent_is_also_rejected_through_the_real_hilmir_main_dispatch() throws IOException {
    final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    final int exitCode =
        HilmirMain.run(
            new String[] {
              "upgrade-cluster",
              "-f",
              topologyFile().toString(),
              "--machine",
              "m1",
              "--new-classpath",
              "/new/lib/*",
              "--role",
              "AGENT"
            },
            capture(new ByteArrayOutputStream()),
            capture(errBytes));

    assertEquals(1, exitCode);
    assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("AGENT"));
  }

  @Test
  void a_role_restart_failure_stops_the_run_before_any_later_role_is_attempted()
      throws IOException {
    final List<ProcessRole> attempted = new ArrayList<>();
    final RoleRestarter restarter =
        (topology, machine, role, runtime, out) -> {
          attempted.add(role);
          if (role == FAFNIR) {
            throw new HilmirException("simulated failure restarting fafnir");
          }
          return new RunRecord(
              role.name().toLowerCase() + "-0",
              role.name(),
              "m1",
              111L,
              List.of("java"),
              "x.log",
              "");
        };
    final List<String> args =
        List.of(
            "-f", topologyFile().toString(), "--machine", "m1", "--new-classpath", "/new/lib/*");

    assertThrows(
        HilmirException.class,
        () -> UpgradeClusterCommand.run(args, capture(new ByteArrayOutputStream()), restarter));

    // Boot order is STORE, then FAFNIR, then CONTROL_PLANE for this topology -- FAFNIR's own
    // failure must stop the loop before CONTROL_PLANE is ever attempted.
    assertEquals(List.of(STORE, FAFNIR), attempted);
  }

  @Test
  void every_restartable_role_the_machine_hosts_is_restarted_in_boot_order_when_no_role_is_given()
      throws IOException {
    final List<ProcessRole> attempted = new ArrayList<>();
    final RoleRestarter restarter =
        (topology, machine, role, runtime, out) -> {
          attempted.add(role);
          return new RunRecord(
              role.name().toLowerCase() + "-0",
              role.name(),
              "m1",
              111L,
              List.of("java"),
              "x.log",
              "");
        };
    final List<String> args =
        List.of(
            "-f", topologyFile().toString(), "--machine", "m1", "--new-classpath", "/new/lib/*");

    final int exitCode =
        UpgradeClusterCommand.run(args, capture(new ByteArrayOutputStream()), restarter);

    assertEquals(0, exitCode);
    assertEquals(List.of(STORE, FAFNIR, CONTROL_PLANE), attempted);
  }
}
