package com.gimle.hilmir.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A re-run of {@code up} against a machine where some roles are already alive must respawn only the
 * genuinely dead ones, and its own bookkeeping file must come out of that re-run still naming every
 * already-alive role's real pid -- not a fresh, unconditional duplicate of everything that machine
 * hosts, and not a ledger overwrite that forgets the still-running originals.
 */
class MachineLauncherUpSkipsAliveRolesTest {

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

  @Test
  void up_respawns_only_the_dead_role_and_keeps_the_alive_ones_own_pid_in_the_ledger()
      throws IOException {
    final ProcessCommand storeCommand =
        socketFixtureCommand(ProcessRole.STORE, "store-0", LaunchTestSupport.freePort());
    final ProcessCommand agentCommand =
        socketFixtureCommand(ProcessRole.AGENT, "agent-a", LaunchTestSupport.freePort());
    final Map<String, MachinePlan> byMachine = new LinkedHashMap<>();
    byMachine.put("m1", new MachinePlan("m1", List.of(storeCommand, agentCommand)));
    final ClusterPlan clusterPlan = new ClusterPlan(byMachine);

    final ResolvedRuntime runtime =
        new ResolvedRuntime(
            LaunchTestSupport.javaExecutable(), LaunchTestSupport.testClasspath(), tempDir);

    final List<RunRecord> firstRecords =
        MachineLauncher.up(
            clusterPlan, TOPOLOGY, "m1", runtime, capture(new ByteArrayOutputStream()));
    final RunRecord originalStore = firstRecords.get(0);
    final RunRecord originalAgent = firstRecords.get(1);

    try {
      // Simulate M43's own repro: exactly one role (STORE) dies; AGENT stays genuinely alive.
      ProcessHandle.of(originalStore.pid()).ifPresent(ProcessHandle::destroyForcibly);
      awaitDead(originalStore.pid());

      final ByteArrayOutputStream secondUpOutput = new ByteArrayOutputStream();
      final List<RunRecord> secondRecords =
          MachineLauncher.up(clusterPlan, TOPOLOGY, "m1", runtime, capture(secondUpOutput));

      assertEquals(2, secondRecords.size());
      final RunRecord respawnedStore = secondRecords.get(0);
      final RunRecord keptAgent = secondRecords.get(1);

      assertNotEquals(originalStore.pid(), respawnedStore.pid());
      assertEquals(originalAgent.pid(), keptAgent.pid());
      final String secondOutput = secondUpOutput.toString(StandardCharsets.UTF_8);
      assertTrue(
          secondOutput.contains("agent-a (pid " + originalAgent.pid() + ") already running"));
      assertTrue(secondOutput.contains("spawned STORE store-0"));

      // The ledger written by the re-run must still name the real, live agent pid -- not overwrite
      // it with a duplicate, and not lose it entirely.
      final List<RunRecord> ledger = RunLedger.read(runtime.dataRoot());
      assertEquals(2, ledger.size());
      assertTrue(
          ledger.stream()
              .anyMatch(r -> r.id().equals("agent-a") && r.pid() == originalAgent.pid()));
      assertTrue(
          ledger.stream()
              .anyMatch(r -> r.id().equals("store-0") && r.pid() == respawnedStore.pid()));
    } finally {
      MachineLauncher.down(runtime.dataRoot(), capture(new ByteArrayOutputStream()));
      LaunchTestSupport.drainTempDir(tempDir);
    }
  }

  private static void awaitDead(final long pid) throws IOException {
    final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
      if (System.nanoTime() >= deadline) {
        throw new IOException("pid " + pid + " still alive 5s after destroyForcibly");
      }
      try {
        Thread.sleep(20);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted while awaiting process death", e);
      }
    }
  }
}
