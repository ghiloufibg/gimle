package com.gimle.hilmir.launch;

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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link MachineLauncher}'s {@code up}/{@code down}/{@code status} against real, killable
 * OS processes -- {@link com.gimle.hilmir.launch.fixture.SocketFixtureMain}, a cheap fixture that
 * binds a listening socket and parks -- rather than any actual Gimlé process kind ({@code
 * StoreMain}, {@code AgentMain}, ...), which {@link com.gimle.hilmir.plan.LaunchPlanner} always
 * plans and this module deliberately never depends on.
 */
class MachineLauncherIntegrationTest {

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

  @Test
  void up_waits_on_a_remote_prerequisite_then_down_and_status_reflect_the_real_processes()
      throws IOException {
    final int storePort = LaunchTestSupport.freePort();
    final int agentPort = LaunchTestSupport.freePort();
    final ProcessCommand storeCommand =
        socketFixtureCommand(ProcessRole.STORE, "store-0", "m1", storePort);
    final ProcessCommand agentCommand =
        socketFixtureCommand(ProcessRole.AGENT, "agent-a", "m2", agentPort);

    final Map<String, MachinePlan> byMachine = new LinkedHashMap<>();
    byMachine.put("m1", new MachinePlan("m1", List.of(storeCommand)));
    byMachine.put("m2", new MachinePlan("m2", List.of(agentCommand)));
    final ClusterPlan clusterPlan = new ClusterPlan(byMachine);

    final ResolvedRuntime m1Runtime =
        new ResolvedRuntime(
            LaunchTestSupport.javaExecutable(),
            LaunchTestSupport.testClasspath(),
            tempDir.resolve("m1"));
    final ResolvedRuntime m2Runtime =
        new ResolvedRuntime(
            LaunchTestSupport.javaExecutable(),
            LaunchTestSupport.testClasspath(),
            tempDir.resolve("m2"));

    final ByteArrayOutputStream m1UpOutput = new ByteArrayOutputStream();
    final List<RunRecord> m1Records =
        MachineLauncher.up(clusterPlan, TOPOLOGY, "m1", m1Runtime, capture(m1UpOutput));
    assertEquals(1, m1Records.size());
    assertTrue(m1Records.get(0).pid() > 0);

    // m2's own command is AGENT, ranked after STORE -- up() must wait for m1's store to be
    // reachable before spawning its own process; asserting the wait message proves the ordering
    // actually ran, not just that both processes happened to end up alive.
    final ByteArrayOutputStream m2UpOutput = new ByteArrayOutputStream();
    final List<RunRecord> m2Records =
        MachineLauncher.up(clusterPlan, TOPOLOGY, "m2", m2Runtime, capture(m2UpOutput));
    assertEquals(1, m2Records.size());
    assertTrue(m2UpOutput.toString(StandardCharsets.UTF_8).contains("waiting for STORE store-0"));

    try {
      final ByteArrayOutputStream statusOutput = new ByteArrayOutputStream();
      MachineLauncher.status(m1Runtime.dataRoot(), capture(statusOutput));
      final String status = statusOutput.toString(StandardCharsets.UTF_8);
      assertTrue(status.contains("alive=true"));
      assertTrue(status.contains("readiness=open"));
    } finally {
      final ByteArrayOutputStream m1DownOutput = new ByteArrayOutputStream();
      MachineLauncher.down(m1Runtime.dataRoot(), capture(m1DownOutput));
      final ByteArrayOutputStream m2DownOutput = new ByteArrayOutputStream();
      MachineLauncher.down(m2Runtime.dataRoot(), capture(m2DownOutput));

      assertProcessGone(m1Records.get(0).pid());
      assertProcessGone(m2Records.get(0).pid());
      assertThrows(HilmirException.class, () -> RunLedger.read(m1Runtime.dataRoot()));
      assertThrows(HilmirException.class, () -> RunLedger.read(m2Runtime.dataRoot()));
      // Pre-empt @TempDir's own single-attempt cleanup: on Windows, this test's own two just-killed
      // fixture processes can hold their redirected log files open a little longer than down()
      // alone
      // waits out, and @TempDir never retries.
      LaunchTestSupport.drainTempDir(tempDir);
    }
  }

  @Test
  @Timeout(15)
  void up_fails_fast_when_a_spawned_process_exits_before_its_port_ever_opens() throws IOException {
    // A JVM launched against a class that doesn't exist exits almost immediately with a non-zero
    // code -- the same shape a real bad-classpath/missing-dependency failure takes. Wrapped in an
    // assertion timeout well under MachineLauncher's own 2-minute READINESS_TIMEOUT: if the
    // launcher
    // ever regresses to blindly polling the port until that timeout elapses instead of noticing the
    // process already died, this test itself times out rather than merely taking two minutes.
    final int storePort = LaunchTestSupport.freePort();
    final ProcessCommand crashingCommand =
        new ProcessCommand(
            ProcessRole.STORE,
            "store-0",
            "m1",
            List.of(
                LaunchTestSupport.javaExecutable(),
                "-cp",
                LaunchTestSupport.testClasspath(),
                "com.gimle.hilmir.launch.fixture.NoSuchMainClassAtAll"),
            "store-0.log",
            Path.of("/unused"),
            "127.0.0.1:" + storePort,
            false);

    final Map<String, MachinePlan> byMachine = new LinkedHashMap<>();
    byMachine.put("m1", new MachinePlan("m1", List.of(crashingCommand)));
    final ClusterPlan clusterPlan = new ClusterPlan(byMachine);

    final ResolvedRuntime m1Runtime =
        new ResolvedRuntime(
            LaunchTestSupport.javaExecutable(),
            LaunchTestSupport.testClasspath(),
            tempDir.resolve("m1"));

    final ByteArrayOutputStream m1UpOutput = new ByteArrayOutputStream();
    final HilmirException e =
        assertThrows(
            HilmirException.class,
            () -> MachineLauncher.up(clusterPlan, TOPOLOGY, "m1", m1Runtime, capture(m1UpOutput)));
    assertTrue(e.getMessage().contains("exited"));
    assertTrue(e.getMessage().contains("store-0.log"));

    LaunchTestSupport.drainTempDir(tempDir);
  }

  @Test
  void down_is_a_clean_no_op_for_an_already_dead_recorded_pid() {
    final RunRecord deadRecord =
        new RunRecord(
            "store-0", "STORE", 999_999_999L, List.of("java", "-version"), "store-0.log", "");
    RunLedger.write(tempDir, "m1", List.of(deadRecord));

    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    MachineLauncher.down(tempDir, capture(output));

    assertTrue(output.toString(StandardCharsets.UTF_8).contains("already gone"));
    assertThrows(HilmirException.class, () -> RunLedger.read(tempDir));
  }

  @Test
  void status_reports_a_dead_pid_as_not_alive_and_a_never_bound_address_as_closed() {
    final RunRecord deadRecord =
        new RunRecord(
            "store-0",
            "STORE",
            999_999_999L,
            List.of("java", "-version"),
            "store-0.log",
            "127.0.0.1:1");
    RunLedger.write(tempDir, "m1", List.of(deadRecord));

    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    MachineLauncher.status(tempDir, capture(output));

    final String status = output.toString(StandardCharsets.UTF_8);
    assertTrue(status.contains("alive=false"));
    assertTrue(status.contains("readiness=closed"));
  }

  @Test
  void status_reports_a_still_running_pid_as_not_alive_once_its_own_readiness_address_closes()
      throws IOException {
    // A real, live process -- not a dead pid -- standing in for a killed process the OS process
    // table hasn't reaped yet: the pid genuinely still exists, but the readiness address it was
    // ledgered against is provably no longer answering, which is what a `kill -9` actually looks
    // like from a subsequent, fresh `hilmir status` invocation.
    final int fixturePort = LaunchTestSupport.freePort();
    final ProcessBuilder processBuilder =
        new ProcessBuilder(
            LaunchTestSupport.javaExecutable(),
            "-cp",
            LaunchTestSupport.testClasspath(),
            "com.gimle.hilmir.launch.fixture.SocketFixtureMain",
            String.valueOf(fixturePort));
    processBuilder.redirectErrorStream(true);
    processBuilder.redirectOutput(
        ProcessBuilder.Redirect.to(tempDir.resolve("fixture.log").toFile()));
    final Process fixture = processBuilder.start();
    try {
      ReadinessPoller.awaitPortOpen(
          "127.0.0.1:" + fixturePort, Duration.ofSeconds(10), "the test fixture process");

      final RunRecord stillListedButNotServing =
          new RunRecord(
              "store-0",
              "STORE",
              fixture.pid(),
              List.of("java", "-version"),
              "store-0.log",
              // Deliberately not the fixture's own bound port -- port 1 requires privileges no
              // test runner has, so nothing is ever listening there.
              "127.0.0.1:1");
      RunLedger.write(tempDir, "m1", List.of(stillListedButNotServing));

      final ByteArrayOutputStream output = new ByteArrayOutputStream();
      MachineLauncher.status(tempDir, capture(output));

      final String status = output.toString(StandardCharsets.UTF_8);
      assertTrue(status.contains("alive=false"));
      assertTrue(status.contains("readiness=closed"));
    } finally {
      fixture.destroyForcibly();
      LaunchTestSupport.drainTempDir(tempDir);
    }
  }

  private static void assertProcessGone(final long pid) {
    assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
  }
}
