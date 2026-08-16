package com.gimle.testkit.heimdall;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Renders the investigation that ships inside every {@link HeimdallConditionError}: what the
 * cluster looked like when the condition gave up, which processes were still alive, what happened
 * recently, and where the process logs are. Pure rendering over inputs, so it stays unit-testable
 * without a cluster.
 */
final class ForensicReport {

  private ForensicReport() {}

  static String render(
      final String headline,
      final Optional<ClusterView> lastView,
      final List<HeimdallProcess> processes,
      final List<String> recentEvents,
      final List<String> platformEvents,
      final Path workDir) {
    final StringBuilder report = new StringBuilder(headline).append('\n');
    if (lastView.isPresent()) {
      final ClusterView view = lastView.get();
      final Duration age = Duration.between(view.capturedAt(), Instant.now());
      report
          .append("  last ClusterView (age ")
          .append(age.toMillis())
          .append("ms, via control-plane replica #")
          .append(view.sourceReplica())
          .append("):\n");
      if (view.deployments().isEmpty()) {
        report.append("    no deployments\n");
      }
      for (final ClusterView.DeploymentView deployment : view.deployments().values()) {
        report.append("    ").append(deployment.summarize()).append('\n');
      }
      report.append("    nodes: ");
      if (view.nodes().isEmpty()) {
        report.append("none registered");
      } else {
        report.append(
            String.join(
                ", ",
                view.nodes().values().stream().map(ClusterView.NodeView::summarize).toList()));
      }
      report.append('\n');
    } else {
      report.append("  no ClusterView was ever captured (no control-plane replica responded)\n");
    }
    final long alive = processes.stream().filter(HeimdallProcess::isAlive).count();
    report.append("  process status: ").append(alive).append('/').append(processes.size());
    report.append(" alive");
    final List<HeimdallProcess> dead = processes.stream().filter(p -> !p.isAlive()).toList();
    if (!dead.isEmpty()) {
      report.append("; dead: ");
      report.append(
          String.join(
              ", ",
              dead.stream()
                  .map(p -> p.role() + " " + p.id() + (p.exitWasExpected() ? " (by harness)" : ""))
                  .toList()));
    }
    report.append('\n');
    if (!recentEvents.isEmpty()) {
      report.append("  recent harness events:\n");
      for (final String event : recentEvents) {
        report.append("    ").append(event).append('\n');
      }
    }
    if (!platformEvents.isEmpty()) {
      report.append("  recent platform events:\n");
      for (final String event : platformEvents) {
        report.append("    ").append(event).append('\n');
      }
    }
    report.append("  process logs under: ").append(workDir);
    return report.toString();
  }
}
