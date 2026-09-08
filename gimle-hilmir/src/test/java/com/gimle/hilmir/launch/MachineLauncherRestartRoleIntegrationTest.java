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
      // Pre-empt @TempDir's own single-attempt cleanup: see MachineLauncherIntegrationTest's own
      // identical call for why.
      LaunchTestSupport.drainTempDir(tempDir);
    }
  }

  private static ProcessCommand storeCommandWithId(final String id, final Path dataRoot)
      throws IOException {
    final Path dataDir = dataRoot.resolve(id);
    return new ProcessCommand(
        STORE,
        id,
        "m1",
        List.of(
            LaunchTestSupport.javaExecutable(),
            "-Dgimle.data.root=" + dataDir,
            "-Dgimle.log.root=" + dataRoot.resolve(id + "-logs"),
            "-cp",
            LaunchTestSupport.testClasspath(),
            "com.gimle.hilmir.launch.fixture.SocketFixtureMain",
            String.valueOf(LaunchTestSupport.freePort()),
            dataDir.toString()),
        id + ".log",
        dataDir,
        // Blank on purpose: a store restart otherwise also gates on the post-restart
        // store-leader-serving check (MachineLauncher#awaitStoreLeaderServing), which needs a
        // real Raft-speaking store to answer -- already covered by
        // MachineLauncherStoreQuorumGateTest. This test is only about identity resolution, so it
        // opts that unrelated gate out rather than standing up a real com.gimle.mimir.StoreMain.
        "",
        false);
  }

  @Test
  void
      restart_role_for_store_uses_the_replicas_real_ledger_identity_not_a_recomputed_positional_id()
          throws IOException {
    final ResolvedRuntime runtime =
        new ResolvedRuntime(
            LaunchTestSupport.javaExecutable(), LaunchTestSupport.testClasspath(), tempDir);

    // Simulates a store replica originally launched as "store-2" -- e.g. because, at the moment it
    // was first brought up, its own topology entry happened to be the third one in
    // store.replicas. Spawn it directly via `up` so the ledger records this real id against a
    // genuine, killable pid, exactly what a prior `hilmir up` would leave on disk.
    final ProcessCommand original = storeCommandWithId("store-2", tempDir);
    final List<RunRecord> initialRecords =
        MachineLauncher.up(
            singleMachinePlan(List.of(original)),
            TOPOLOGY,
            "m1",
            runtime,
            capture(new ByteArrayOutputStream()));
    final long originalPid = initialRecords.get(0).pid();

    try {
      // A later topology edit -- per OPS-w2d-01, a replica dynamically added via `hilmir store
      // add` and then reflected in the topology file ahead of this one -- means LaunchPlanner now
      // recomputes this exact same machine's own store replica as "store-1", not "store-2": pure
      // array-index drift, nothing about the replica itself changed, and its real data/log
      // directories are still the ones "store-2" named.
      final ProcessCommand recomputed = storeCommandWithId("store-1", tempDir);
      final ClusterPlan restartPlan = singleMachinePlan(List.of(recomputed));

      final RunRecord restarted =
          MachineLauncher.restartRole(
              restartPlan, TOPOLOGY, "m1", STORE, runtime, capture(new ByteArrayOutputStream()));

      assertEquals(
          "store-2", restarted.id(), "a restart must keep the replica's own real ledger identity");
      assertNotEquals(originalPid, restarted.pid());
      assertFalse(ProcessHandle.of(originalPid).map(ProcessHandle::isAlive).orElse(false));
      assertTrue(ProcessHandle.of(restarted.pid()).map(ProcessHandle::isAlive).orElse(false));
      assertTrue(
          restarted.command().contains("-Dgimle.data.root=" + tempDir.resolve("store-2")),
          "the replacement must keep using store-2's own data directory, not store-1's");
      assertTrue(
          restarted.command().stream().noneMatch(arg -> arg.contains("store-1")),
          "no trace of the freshly (and wrongly) recomputed positional id should leak into the"
              + " spawned command");

      final List<RunRecord> ledgerAfter = RunLedger.read(runtime.dataRoot());
      assertEquals(1, ledgerAfter.size());
      assertEquals("store-2", ledgerAfter.get(0).id());
    } finally {
      MachineLauncher.down(runtime.dataRoot(), capture(new ByteArrayOutputStream()));
      LaunchTestSupport.drainTempDir(tempDir);
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
        "m1",
        List.of(
            new RunRecord(
                "store-0", "STORE", "m1", 123L, List.of("java", "-version"), "store-0.log", "")));

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
