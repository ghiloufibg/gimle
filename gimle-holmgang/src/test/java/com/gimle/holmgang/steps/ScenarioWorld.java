package com.gimle.holmgang.steps;

import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.cluster.GimleCluster;
import com.gimle.holmgang.fenrir.ChaosLedger;
import com.gimle.holmgang.heimdall.InvariantGuard;
import com.gimle.holmgang.loki.Loki;
import com.gimle.holmgang.workload.RecordingWorkload;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one mutable state shared by every step class within a scenario (constructor-injected by
 * cucumber-picocontainer, fresh per scenario). Everything a scenario creates is recorded here so
 * the after-scenario hook can hand a pooled cluster back exactly as it found it.
 */
public final class ScenarioWorld {

  /** What a scenario deployed, remembered so a rolling-update step can reuse its shape. */
  public record DeployedModule(
      String moduleName, String version, int replicas, Optional<String> tenantId) {}

  final Map<String, DeployedModule> deployments = new LinkedHashMap<>();
  final List<String> tenants = new ArrayList<>();
  final List<String> cordonedNodes = new ArrayList<>();
  final Deque<InvariantGuard> guards = new ArrayDeque<>();
  final Deque<Loki.Partition> partitions = new ArrayDeque<>();
  final List<Process> loadProcesses = new ArrayList<>();
  final Map<String, Long> workerPids = new HashMap<>();
  Integer lastSubmissionStatus;
  RecordingWorkload workload;
  ChaosLedger chaosLedger;

  private GimleCluster cluster;
  private boolean destructive;
  private boolean ownsCluster;
  private Path ownedWorkDir;
  private Path fixturesDir;

  void markDestructive() {
    destructive = true;
  }

  boolean isDestructive() {
    return destructive;
  }

  void attachPooled(final GimleCluster pooled) {
    this.cluster = pooled;
    this.ownsCluster = false;
  }

  void attachOwned(final GimleCluster owned, final Path workDir) {
    this.cluster = owned;
    this.ownsCluster = true;
    this.ownedWorkDir = workDir;
  }

  boolean hasCluster() {
    return cluster != null;
  }

  boolean ownsCluster() {
    return ownsCluster;
  }

  Path ownedWorkDir() {
    return ownedWorkDir;
  }

  GimleCluster cluster() {
    if (cluster == null) {
      throw new HolmgangException(
          "no cluster in this scenario -- start with: Given a running cluster from topology"
              + " \"<name>\"");
    }
    return cluster;
  }

  /** Where this scenario's runtime-compiled module jars land; created on first use. */
  Path fixturesDir() {
    if (fixturesDir == null) {
      fixturesDir = Path.of("target", "holmgang-fixtures", Long.toHexString(System.nanoTime()));
      try {
        Files.createDirectories(fixturesDir);
      } catch (final IOException e) {
        throw new HolmgangException("failed creating fixtures directory " + fixturesDir, e);
      }
    }
    return fixturesDir;
  }
}
