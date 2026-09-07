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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The zombie case a bare TCP/process-table check cannot tell apart from genuinely healthy: a
 * process whose port still accepts connections and whose pid is still alive, but which never
 * actually answers its own health request -- a JVM wedged badly enough to stop serving without ever
 * releasing its listening socket. {@code CONTROL_PLANE}'s own {@code /health} endpoint is {@link
 * HealthProbe}'s only signal for this; {@link com.gimle.hilmir.launch.fixture.SocketFixtureMain}
 * accepts every connection and immediately closes it without writing any HTTP response, which fails
 * a real {@code HttpClient} request the identical way a wedged process would.
 */
class MachineLauncherHealthCheckedRespawnTest {

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
  void up_respawns_a_process_whose_port_is_open_but_never_answers_a_health_request()
      throws IOException {
    final ProcessCommand controlPlaneCommand =
        socketFixtureCommand(
            ProcessRole.CONTROL_PLANE, "controlplane-0", LaunchTestSupport.freePort());
    final Map<String, MachinePlan> byMachine = new LinkedHashMap<>();
    byMachine.put("m1", new MachinePlan("m1", List.of(controlPlaneCommand)));
    final ClusterPlan clusterPlan = new ClusterPlan(byMachine);

    final ResolvedRuntime runtime =
        new ResolvedRuntime(
            LaunchTestSupport.javaExecutable(), LaunchTestSupport.testClasspath(), tempDir);

    final List<RunRecord> firstRecords =
        MachineLauncher.up(
            clusterPlan, TOPOLOGY, "m1", runtime, capture(new ByteArrayOutputStream()));
    final RunRecord original = firstRecords.get(0);

    try {
      // Never touched -- the fixture's own process is left running exactly as spawned, its port
      // still open. Only its inability to answer a real health request marks it unhealthy.
      final ByteArrayOutputStream secondUpOutput = new ByteArrayOutputStream();
      final List<RunRecord> secondRecords =
          MachineLauncher.up(clusterPlan, TOPOLOGY, "m1", runtime, capture(secondUpOutput));

      assertEquals(1, secondRecords.size());
      final RunRecord respawned = secondRecords.get(0);
      assertNotEquals(
          original.pid(), respawned.pid(), "a zombie must be killed and replaced, not kept");

      final String secondOutput = secondUpOutput.toString(StandardCharsets.UTF_8);
      assertTrue(
          secondOutput.contains("not answering its own health check"),
          "expected the zombie to be named as such: " + secondOutput);
      assertTrue(
          secondOutput.contains("spawned CONTROL_PLANE controlplane-0"),
          "expected a real respawn: " + secondOutput);

      final List<RunRecord> ledger = RunLedger.read(runtime.dataRoot());
      assertEquals(1, ledger.size());
      assertEquals(respawned.pid(), ledger.get(0).pid());
    } finally {
      MachineLauncher.down(runtime.dataRoot(), capture(new ByteArrayOutputStream()));
      LaunchTestSupport.drainTempDir(tempDir);
    }
  }

  @Test
  void up_leaves_a_genuinely_healthy_control_plane_alone_on_a_second_run() throws IOException {
    final int port = LaunchTestSupport.freePort();
    final Map<String, MachinePlan> byMachine = new LinkedHashMap<>();
    // A role HealthProbe never HTTP-checks (STORE) stays a faithful "genuinely alive" fixture --
    // its own real behavior, not a stand-in that happens to also serve HTTP.
    byMachine.put(
        "m1",
        new MachinePlan("m1", List.of(socketFixtureCommand(ProcessRole.STORE, "store-0", port))));
    final ClusterPlan clusterPlan = new ClusterPlan(byMachine);

    final ResolvedRuntime runtime =
        new ResolvedRuntime(
            LaunchTestSupport.javaExecutable(), LaunchTestSupport.testClasspath(), tempDir);

    final List<RunRecord> firstRecords =
        MachineLauncher.up(
            clusterPlan, TOPOLOGY, "m1", runtime, capture(new ByteArrayOutputStream()));
    final RunRecord original = firstRecords.get(0);

    try {
      final ByteArrayOutputStream secondUpOutput = new ByteArrayOutputStream();
      final List<RunRecord> secondRecords =
          MachineLauncher.up(clusterPlan, TOPOLOGY, "m1", runtime, capture(secondUpOutput));

      assertEquals(1, secondRecords.size());
      assertEquals(original.pid(), secondRecords.get(0).pid());
      assertTrue(
          secondUpOutput
              .toString(StandardCharsets.UTF_8)
              .contains("store-0 (pid " + original.pid() + ") already running"));
    } finally {
      MachineLauncher.down(runtime.dataRoot(), capture(new ByteArrayOutputStream()));
      LaunchTestSupport.drainTempDir(tempDir);
    }
  }
}
