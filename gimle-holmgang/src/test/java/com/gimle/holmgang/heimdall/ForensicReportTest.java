package com.gimle.holmgang.heimdall;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.holmgang.cluster.GimleProcess;
import com.gimle.holmgang.topology.ProcessRole;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ForensicReportTest {

  private record StubProcess(ProcessRole role, String id, boolean isAlive, boolean exitWasExpected)
      implements GimleProcess {

    @Override
    public long pid() {
      return 1;
    }

    @Override
    public void kill() {}

    @Override
    public void killWithDescendants() {}

    @Override
    public void restart() {}

    @Override
    public void onExit(final Runnable callback) {}

    @Override
    public Path logFile() {
      return Path.of("stub.log");
    }

    @Override
    public String endpoint() {
      return "127.0.0.1:0";
    }
  }

  @Test
  void the_report_carries_view_processes_events_and_log_location() {
    // A real Path, not a hardcoded forward-slash literal: ForensicReport.render appends workDir
    // via its own toString(), which is backslash-separated on Windows -- comparing against the
    // same Path's own rendering (below) keeps this assertion platform-independent.
    final Path workDir = Path.of("work", "dir");
    final ClusterView view =
        new ClusterView(
            Instant.now(),
            1,
            Map.of(
                "greeter",
                new ClusterView.DeploymentView(
                    "greeter",
                    1,
                    false,
                    List.of(
                        new ClusterView.InstanceView(
                            0, "node-1", Optional.of("ACTIVE"), Optional.of("1.0.0"))))),
            Map.of(
                "node-1", new ClusterView.NodeView("node-1", false, List.of(), Optional.empty())));
    final String report =
        ForensicReport.render(
            "condition not met: something",
            Optional.of(view),
            List.of(
                new StubProcess(ProcessRole.STORE, "store-0", true, false),
                new StubProcess(ProcessRole.AGENT, "node-1", false, true)),
            List.of("2026-08-14T05:00:00Z greeter: desired 1, ACTIVE=0 [...]"),
            List.of("2026-08-14T05:00:02Z greeter#0 SCHEDULED: placed on node-1"),
            workDir);
    assertTrue(report.contains("condition not met: something"), report);
    assertTrue(report.contains("last ClusterView"), report);
    assertTrue(report.contains("via control-plane replica #1"), report);
    assertTrue(report.contains("greeter: desired 1, ACTIVE=1"), report);
    assertTrue(report.contains("process status: 1/2 alive"), report);
    assertTrue(report.contains("AGENT node-1 (by harness)"), report);
    assertTrue(report.contains("recent harness events:"), report);
    assertTrue(report.contains("recent platform events:"), report);
    assertTrue(report.contains("process logs under: " + workDir), report);
  }

  @Test
  void a_report_with_no_view_says_so_instead_of_rendering_nothing() {
    final String report =
        ForensicReport.render(
            "condition not met: anything",
            Optional.empty(),
            List.of(),
            List.of(),
            List.of(),
            Path.of("/work/dir"));
    assertTrue(report.contains("no ClusterView was ever captured"), report);
  }
}
