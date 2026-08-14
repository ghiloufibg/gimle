package com.gimle.holmgang.steps;

import com.gimle.holmgang.WorkDirs;
import com.gimle.holmgang.cluster.GimleCluster;
import com.gimle.holmgang.topology.ClusterSpec;
import com.gimle.holmgang.topology.ClusterTopologyParser;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cluster acquisition for the Gherkin layer. Boot cost dominates a scenario's wall clock, so
 * clusters are pooled per topology name and shared across every non-destructive scenario in the run
 * (which must each leave the cluster as found -- the after-scenario hook enforces that). A scenario
 * tagged {@code @destructive} gets a {@link #fresh} cluster all to itself instead, closed the
 * moment it ends, so killed processes and mutated membership can never leak into another scenario.
 */
final class ClusterPool {

  private static final Map<String, GimleCluster> POOLED = new LinkedHashMap<>();
  private static final Map<String, Path> POOLED_WORK_DIRS = new LinkedHashMap<>();
  private static boolean anyScenarioFailed;

  private ClusterPool() {}

  record FreshCluster(GimleCluster cluster, Path workDir) {}

  static synchronized GimleCluster pooled(final String topologyName) {
    return POOLED.computeIfAbsent(
        topologyName,
        name -> {
          final Path workDir = workDirFor(name);
          POOLED_WORK_DIRS.put(name, workDir);
          return boot(name, workDir);
        });
  }

  static synchronized FreshCluster fresh(final String topologyName) {
    final Path workDir = workDirFor(topologyName);
    return new FreshCluster(boot(topologyName, workDir), workDir);
  }

  static synchronized void markFailed() {
    anyScenarioFailed = true;
  }

  static synchronized void closeAll() {
    for (final GimleCluster cluster : POOLED.values()) {
      cluster.close();
    }
    POOLED.clear();
    // Pooled clusters served many scenarios, so retention is decided by the whole run: one
    // failed scenario anywhere keeps every pooled work directory for the post-mortem.
    if (WorkDirs.shouldDelete(anyScenarioFailed)) {
      POOLED_WORK_DIRS.values().forEach(WorkDirs::deleteRecursively);
    }
    POOLED_WORK_DIRS.clear();
  }

  private static GimleCluster boot(final String topologyName, final Path workDir) {
    final ClusterSpec spec =
        ClusterTopologyParser.fromClasspath("topologies/" + topologyName + ".yaml");
    return GimleCluster.start(spec, workDir);
  }

  private static Path workDirFor(final String topologyName) {
    return Path.of("target", "holmgang", topologyName + "-" + Long.toHexString(System.nanoTime()));
  }
}
