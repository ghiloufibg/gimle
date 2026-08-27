package com.gimle.holmgang;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gimle.holmgang.cluster.GimleCluster;
import com.gimle.holmgang.surtr.ExampleModuleJarSource;
import com.gimle.holmgang.topology.ClusterSpec;
import com.gimle.holmgang.topology.ClusterTopologyParser;
import com.gimle.ragnarok.surtr.SurtrReport;
import com.gimle.ragnarok.surtr.SurtrRunResult;
import com.gimle.ragnarok.surtr.SurtrRunner;
import com.gimle.ragnarok.surtr.SurtrWorkload;
import com.gimle.ragnarok.surtr.SurtrWorkloadParser;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The single failsafe entry point for Surtr, the scale/churn/performance runner. It skips via a
 * JUnit assumption unless {@code -Dgimle.surtr.workload=<name|path>} is set, so ordinary validation
 * runs are untouched; the default {@code mvn verify} never sees it at all ({@code @Tag("holmgang")}
 * plus the module's {@code *IT} surefire exclusion). Given a workload, it boots that workload's own
 * topology, runs it, writes a report, and fails the run if any gate tripped.
 *
 * <p>Boots its own cluster and owns its whole lifetime -- a burn is destructive of capacity even
 * when it breaks nothing, so it never shares a pooled scenario cluster.
 */
@Tag("holmgang")
class SurtrIT {

  @Test
  @Timeout(value = 20, unit = TimeUnit.MINUTES)
  void runs_the_configured_surtr_workload() {
    final String workloadName = System.getProperty("gimle.surtr.workload");
    assumeTrue(
        workloadName != null && !workloadName.isBlank(),
        "set -Dgimle.surtr.workload=<name|path> to run a Surtr burn");

    final SurtrWorkload workload = SurtrWorkloadParser.resolve(workloadName);
    final ClusterSpec spec = ClusterTopologyParser.fromClasspath(workload.topology());
    final Path workDir =
        Path.of("target", "holmgang", "surtr-cluster-" + Long.toHexString(System.nanoTime()));
    final GimleCluster cluster = GimleCluster.start(spec, workDir);
    try {
      final SurtrRunResult result =
          new SurtrRunner(cluster.asClusterTarget(), workload, new ExampleModuleJarSource()).run();
      com.gimle.holmgang.saga.SagaCollector.instance().recordSurtr(result);
      final Path report = SurtrReport.write(result, Path.of("target", "holmgang", "surtr"));
      System.out.println(SurtrReport.render(result));
      System.out.println("Surtr report written to " + report.toAbsolutePath());
      assertTrue(
          result.passed(),
          "one or more Surtr gates failed; see " + report + "\n" + SurtrReport.render(result));
    } finally {
      cluster.close();
    }
  }
}
