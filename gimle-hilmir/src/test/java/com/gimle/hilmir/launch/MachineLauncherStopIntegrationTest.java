package com.gimle.hilmir.launch;

import static com.gimle.hilmir.topology.ProcessRole.CONTROL_PLANE;
import static com.gimle.hilmir.topology.ProcessRole.FAFNIR;
import static com.gimle.hilmir.topology.ProcessRole.STORE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link MachineLauncher#stop} against real, killable OS processes -- the same {@link
 * com.gimle.hilmir.launch.fixture.SocketFixtureMain} fixture the other launch integration tests use
 * -- so that "stop exactly one co-located process and leave its neighbours running" is proven
 * against real pids and a real ledger rather than a mock.
 */
class MachineLauncherStopIntegrationTest {

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
              """
                  .getBytes(StandardCharsets.UTF_8)));

  private static ProcessCommand socketFixtureCommand(
      final ProcessRole role, final String id, final int port) {
    return new ProcessCommand(
        role,
        id,
        "m1",
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

  private ResolvedRuntime runtime() {
    return new ResolvedRuntime(
        LaunchTestSupport.javaExecutable(), LaunchTestSupport.testClasspath(), tempDir);
  }

  private static boolean alive(final long pid) {
    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
  }

  private static long pidOf(final List<RunRecord> records, final String id) {
    return records.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow().pid();
  }

  @Test
  void stop_kills_only_the_named_role_and_drops_only_its_ledger_entry() throws IOException {
    final ResolvedRuntime runtime = runtime();
    final List<RunRecord> started =
        MachineLauncher.up(
            singleMachinePlan(
                List.of(
                    socketFixtureCommand(STORE, "store-0", LaunchTestSupport.freePort()),
                    socketFixtureCommand(
                        CONTROL_PLANE, "controlplane-0", LaunchTestSupport.freePort()))),
            TOPOLOGY,
            "m1",
            runtime,
            capture(new ByteArrayOutputStream()));
    final long storePid = pidOf(started, "store-0");
    final long controlPlanePid = pidOf(started, "controlplane-0");

    try {
      final ByteArrayOutputStream output = new ByteArrayOutputStream();
      final RunRecord stopped =
          MachineLauncher.stop(
              tempDir, Optional.of(CONTROL_PLANE), Optional.empty(), capture(output));

      assertEquals("controlplane-0", stopped.id());
      assertFalse(alive(controlPlanePid));
      assertTrue(alive(storePid), "the co-located store must keep running");
      assertEquals(
          List.of("store-0"), RunLedger.read(tempDir).stream().map(RunRecord::id).toList());
      assertTrue(output.toString(StandardCharsets.UTF_8).contains("controlplane-0"));
    } finally {
      MachineLauncher.down(tempDir, capture(new ByteArrayOutputStream()));
      LaunchTestSupport.drainTempDir(tempDir);
    }
  }

  @Test
  void stop_by_id_picks_one_of_two_co_located_replicas_of_the_same_role() throws IOException {
    final ResolvedRuntime runtime = runtime();
    final List<RunRecord> started =
        MachineLauncher.up(
            singleMachinePlan(
                List.of(
                    socketFixtureCommand(STORE, "store-0", LaunchTestSupport.freePort()),
                    socketFixtureCommand(STORE, "store-1", LaunchTestSupport.freePort()))),
            TOPOLOGY,
            "m1",
            runtime,
            capture(new ByteArrayOutputStream()));
    final long firstPid = pidOf(started, "store-0");
    final long secondPid = pidOf(started, "store-1");

    try {
      final HilmirException ambiguous =
          assertThrows(
              HilmirException.class,
              () ->
                  MachineLauncher.stop(
                      tempDir,
                      Optional.of(STORE),
                      Optional.empty(),
                      capture(new ByteArrayOutputStream())));
      assertTrue(ambiguous.getMessage().contains("store-0"));
      assertTrue(ambiguous.getMessage().contains("store-1"));
      assertTrue(alive(firstPid) && alive(secondPid), "an ambiguous request must stop nothing");

      MachineLauncher.stop(
          tempDir, Optional.empty(), Optional.of("store-1"), capture(new ByteArrayOutputStream()));

      assertFalse(alive(secondPid));
      assertTrue(alive(firstPid));
      assertEquals(
          List.of("store-0"), RunLedger.read(tempDir).stream().map(RunRecord::id).toList());
    } finally {
      MachineLauncher.down(tempDir, capture(new ByteArrayOutputStream()));
      LaunchTestSupport.drainTempDir(tempDir);
    }
  }

  @Test
  void stop_for_a_role_the_machine_never_ran_names_what_is_actually_recorded() {
    RunLedger.write(
        tempDir,
        "m1",
        List.of(
            new RunRecord(
                "store-0",
                "STORE",
                999_999_999L,
                List.of("java", "-version"),
                "store-0.log",
                "")));

    final HilmirException e =
        assertThrows(
            HilmirException.class,
            () ->
                MachineLauncher.stop(
                    tempDir,
                    Optional.of(FAFNIR),
                    Optional.empty(),
                    capture(new ByteArrayOutputStream())));

    assertTrue(e.getMessage().contains("--role FAFNIR"));
    assertTrue(e.getMessage().contains("store-0 (STORE)"));
  }

  @Test
  void stop_leaves_the_ledger_ready_for_a_later_up_to_respawn_just_that_process()
      throws IOException {
    final ResolvedRuntime runtime = runtime();
    final ProcessCommand store =
        socketFixtureCommand(STORE, "store-0", LaunchTestSupport.freePort());
    final ProcessCommand controlPlane =
        socketFixtureCommand(CONTROL_PLANE, "controlplane-0", LaunchTestSupport.freePort());
    final ClusterPlan plan = singleMachinePlan(List.of(store, controlPlane));
    final List<RunRecord> started =
        MachineLauncher.up(plan, TOPOLOGY, "m1", runtime, capture(new ByteArrayOutputStream()));
    final long storePid = pidOf(started, "store-0");
    final long firstControlPlanePid = pidOf(started, "controlplane-0");

    try {
      MachineLauncher.stop(
          tempDir,
          Optional.of(CONTROL_PLANE),
          Optional.empty(),
          capture(new ByteArrayOutputStream()));

      final List<RunRecord> afterUp =
          MachineLauncher.up(plan, TOPOLOGY, "m1", runtime, capture(new ByteArrayOutputStream()));

      assertEquals(storePid, pidOf(afterUp, "store-0"), "the still-running store is not respawned");
      final long secondControlPlanePid = pidOf(afterUp, "controlplane-0");
      assertTrue(secondControlPlanePid != firstControlPlanePid);
      assertTrue(alive(secondControlPlanePid));
    } finally {
      MachineLauncher.down(tempDir, capture(new ByteArrayOutputStream()));
      LaunchTestSupport.drainTempDir(tempDir);
    }
  }
}
