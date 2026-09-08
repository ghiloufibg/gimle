package com.gimle.hilmir.launch;

import static com.gimle.hilmir.topology.ProcessRole.AGENT;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.plan.ClusterPlan;
import com.gimle.hilmir.plan.MachinePlan;
import com.gimle.hilmir.plan.ProcessCommand;
import com.gimle.hilmir.plan.ResolvedRuntime;
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
 * Exercises {@link MachineLauncher#down}'s descendant-killing against a real process tree -- a
 * parent {@link com.gimle.hilmir.launch.fixture.ParentWithChildFixtureMain} plus its own real OS
 * child -- proving a descendant gets the same destroy-then-await-then-destroyForcibly escalation
 * the named process itself already gets, not a single unescalated SIGTERM with no fallback.
 */
class MachineLauncherDescendantKillIntegrationTest {

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

  private static ProcessCommand parentWithChildCommand(
      final String id, final int port, final boolean childIgnoresSigterm) {
    return new ProcessCommand(
        AGENT,
        id,
        "m1",
        List.of(
            LaunchTestSupport.javaExecutable(),
            "-cp",
            LaunchTestSupport.testClasspath(),
            "com.gimle.hilmir.launch.fixture.ParentWithChildFixtureMain",
            String.valueOf(port),
            String.valueOf(childIgnoresSigterm)),
        id + ".log",
        Path.of("/unused"),
        "127.0.0.1:" + port,
        false);
  }

  private static PrintStream capture(final ByteArrayOutputStream buffer) {
    return new PrintStream(buffer, true, StandardCharsets.UTF_8);
  }

  private static ClusterPlan singleCommandPlan(final ProcessCommand command) {
    final Map<String, MachinePlan> byMachine = new LinkedHashMap<>();
    byMachine.put("m1", new MachinePlan("m1", List.of(command)));
    return new ClusterPlan(byMachine);
  }

  private ResolvedRuntime runtime() {
    return new ResolvedRuntime(
        LaunchTestSupport.javaExecutable(), LaunchTestSupport.testClasspath(), tempDir);
  }

  @Test
  @Timeout(30)
  void down_kills_a_descendant_that_ignores_sigterm_via_forceful_escalation() throws IOException {
    final int port = LaunchTestSupport.freePort();
    final List<RunRecord> started =
        MachineLauncher.up(
            singleCommandPlan(parentWithChildCommand("agent-a", port, true)),
            TOPOLOGY,
            "m1",
            runtime(),
            capture(new ByteArrayOutputStream()));
    final long parentPid = started.get(0).pid();
    final long childPid = awaitDescendant(parentPid);

    MachineLauncher.down(tempDir, capture(new ByteArrayOutputStream()));

    assertFalse(ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false));
    assertFalse(
        ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
        "a descendant that ignores SIGTERM must still end up killed via destroyForcibly");
    LaunchTestSupport.drainTempDir(tempDir);
  }

  @Test
  @Timeout(15)
  void down_kills_a_cooperative_descendant_without_needing_a_full_grace_period()
      throws IOException {
    final int port = LaunchTestSupport.freePort();
    final List<RunRecord> started =
        MachineLauncher.up(
            singleCommandPlan(parentWithChildCommand("agent-a", port, false)),
            TOPOLOGY,
            "m1",
            runtime(),
            capture(new ByteArrayOutputStream()));
    final long parentPid = started.get(0).pid();
    final long childPid = awaitDescendant(parentPid);

    final long startNanos = System.nanoTime();
    MachineLauncher.down(tempDir, capture(new ByteArrayOutputStream()));
    final Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

    assertFalse(ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false));
    assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
    assertTrue(
        elapsed.compareTo(Duration.ofSeconds(5)) < 0,
        "a cooperative descendant must not cost a full grace-period wait: took " + elapsed);
    LaunchTestSupport.drainTempDir(tempDir);
  }

  /**
   * The fixture's child process is spawned by its own {@code main()} before it binds its socket,
   * but {@code up} only ever confirms the socket -- so a caller relying on the child already being
   * a visible OS descendant has to wait for that separately rather than assuming it landed by the
   * time {@code up} returns.
   */
  private static long awaitDescendant(final long parentPid) {
    final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      final List<ProcessHandle> descendants =
          ProcessHandle.of(parentPid).orElseThrow().descendants().toList();
      if (!descendants.isEmpty()) {
        return descendants.get(0).pid();
      }
      try {
        Thread.sleep(25);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted waiting for a descendant to appear", e);
      }
    }
    throw new AssertionError("parent " + parentPid + " never got a descendant within 10s");
  }
}
