package com.gimle.hilmir.launch;

import static com.gimle.hilmir.topology.ProcessRole.CONTROL_PLANE;
import static com.gimle.hilmir.topology.ProcessRole.FAFNIR;
import static com.gimle.hilmir.topology.ProcessRole.STORE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.plan.ClusterPlan;
import com.gimle.hilmir.plan.MachinePlan;
import com.gimle.hilmir.plan.ProcessCommand;
import com.gimle.hilmir.plan.ResolvedRuntime;
import com.gimle.hilmir.topology.ProcessRole;
import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.TopologyParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link MachineLauncher#restartRole} against real, killable OS processes -- the same
 * {@link com.gimle.hilmir.launch.fixture.SocketFixtureMain} fixture {@link
 * MachineLauncherIntegrationTest} already uses for {@code up}/{@code down}/{@code status} -- so a
 * restart's own kill-then-respawn-then-ledger-upsert sequence is proven against a real pid, not a
 * mock.
 */
class MachineLauncherRestartRoleIntegrationTest {

  @TempDir Path tempDir;

  private static final Topology TOPOLOGY =
      TopologyParser.parse(
          new ByteArrayInputStream(
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
              """
                  .getBytes(StandardCharsets.UTF_8)));

  private static ProcessCommand socketFixtureCommand(
      final ProcessRole role, final String id, final String machine, final int port) {
    return new ProcessCommand(
        role,
        id,
        machine,
        List.of(
            LaunchTestSupport.javaExecutable(),
            "-cp",
            LaunchTestSupport.testClasspath(),
            "com.gimle.hilmir.launch.fixture.SocketFixtureMain",
            String.valueOf(port)),
        id + ".log",
        Path.of("/unused"),
        "127.0.0.1:" + port,
        false);
  }

  private static PrintStream capture(final ByteArrayOutputStream buffer) {
    return new PrintStream(buffer, true, StandardCharsets.UTF_8);
  }

  private static ClusterPlan singleMachinePlan(final List<ProcessCommand> commands) {
    final Map<String, MachinePlan> byMachine = new LinkedHashMap<>();
    byMachine.put("m1", new MachinePlan("m1", commands));
    return new ClusterPlan(byMachine);
  }

  @Test
  void restart_role_kills_the_old_process_spawns_a_new_one_and_upserts_only_that_ledger_entry()
      throws IOException {
    final ProcessCommand storeCommand =
        socketFixtureCommand(STORE, "store-0", "m1", LaunchTestSupport.freePort());
    final ProcessCommand controlPlaneCommandV1 =
        socketFixtureCommand(CONTROL_PLANE, "controlplane-0", "m1", LaunchTestSupport.freePort());
    final ResolvedRuntime runtime =
        new ResolvedRuntime(
            LaunchTestSupport.javaExecutable(), LaunchTestSupport.testClasspath(), tempDir);

    final List<RunRecord> initialRecords =
        MachineLauncher.up(
            singleMachinePlan(List.of(storeCommand, controlPlaneCommandV1)),
            TOPOLOGY,
            "m1",
            runtime,
            capture(new ByteArrayOutputStream()));
    final long storePidBefore =
        initialRecords.stream()
            .filter(r -> r.id().equals("store-0"))
            .findFirst()
            .orElseThrow()
            .pid();
    final long controlPlanePidBefore =
        initialRecords.stream()
            .filter(r -> r.id().equals("controlplane-0"))
            .findFirst()
            .orElseThrow()
            .pid();

    try {
      // The "new" runtime plans a fresh port for the control plane's replacement process -- a
      // stand-in for a newly-unpacked classpath producing a differently-behaving command line.
      final ProcessCommand controlPlaneCommandV2 =
          socketFixtureCommand(CONTROL_PLANE, "controlplane-0", "m1", LaunchTestSupport.freePort());
      final ClusterPlan restartPlan =
          singleMachinePlan(List.of(storeCommand, controlPlaneCommandV2));

      final RunRecord restarted =
          MachineLauncher.restartRole(
              restartPlan,
              TOPOLOGY,
              "m1",
              CONTROL_PLANE,
              runtime,
              capture(new ByteArrayOutputStream()));

      assertNotEquals(controlPlanePidBefore, restarted.pid());
      assertFalse(
          ProcessHandle.of(controlPlanePidBefore).map(ProcessHandle::isAlive).orElse(false));
      assertTrue(ProcessHandle.of(restarted.pid()).map(ProcessHandle::isAlive).orElse(false));

      final List<RunRecord> ledgerAfter = RunLedger.read(runtime.dataRoot());
      assertEquals(2, ledgerAfter.size());
      final RunRecord storeAfter =
          ledgerAfter.stream().filter(r -> r.id().equals("store-0")).findFirst().orElseThrow();
      assertEquals(
          storePidBefore, storeAfter.pid(), "the co-located store record must be untouched");
      final RunRecord controlPlaneAfter =
          ledgerAfter.stream()
              .filter(r -> r.id().equals("controlplane-0"))
              .findFirst()
              .orElseThrow();
      assertEquals(restarted.pid(), controlPlaneAfter.pid());
    } finally {
      MachineLauncher.down(runtime.dataRoot(), capture(new ByteArrayOutputStream()));
    }
  }

  @Test
  void restart_role_for_a_role_not_hosted_on_the_machine_fails_clearly() throws IOException {
    final ProcessCommand storeCommand =
        socketFixtureCommand(STORE, "store-0", "m1", LaunchTestSupport.freePort());
    final ProcessCommand controlPlaneCommand =
        socketFixtureCommand(CONTROL_PLANE, "controlplane-0", "m1", LaunchTestSupport.freePort());
    final ClusterPlan plan = singleMachinePlan(List.of(storeCommand, controlPlaneCommand));
    final ResolvedRuntime runtime =
        new ResolvedRuntime(
            LaunchTestSupport.javaExecutable(), LaunchTestSupport.testClasspath(), tempDir);

    final HilmirException e =
        assertThrows(
            HilmirException.class,
            () ->
                MachineLauncher.restartRole(
                    plan, TOPOLOGY, "m1", FAFNIR, runtime, capture(new ByteArrayOutputStream())));

    assertTrue(e.getMessage().contains("does not host role FAFNIR"));
  }

  @Test
  void restart_role_with_no_existing_ledger_entry_fails_clearly_and_points_at_hilmir_up()
      throws IOException {
    final ProcessCommand storeCommand =
        socketFixtureCommand(STORE, "store-0", "m1", LaunchTestSupport.freePort());
    final ProcessCommand controlPlaneCommand =
        socketFixtureCommand(CONTROL_PLANE, "controlplane-0", "m1", LaunchTestSupport.freePort());
    final ClusterPlan plan = singleMachinePlan(List.of(storeCommand, controlPlaneCommand));
    final ResolvedRuntime runtime =
        new ResolvedRuntime(
            LaunchTestSupport.javaExecutable(), LaunchTestSupport.testClasspath(), tempDir);
    // Only store-0 was ever recorded as running -- controlplane-0 has no ledger entry to restart.
    RunLedger.write(
        tempDir,
        List.of(
            new RunRecord(
                "store-0", "STORE", 123L, List.of("java", "-version"), "store-0.log", "")));

    final HilmirException e =
        assertThrows(
            HilmirException.class,
            () ->
                MachineLauncher.restartRole(
                    plan,
                    TOPOLOGY,
                    "m1",
                    CONTROL_PLANE,
                    runtime,
                    capture(new ByteArrayOutputStream())));

    assertTrue(e.getMessage().contains("hilmir up"));
  }
}
