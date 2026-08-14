package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real-cluster coverage for {@code kind: StatefulSet}'s core promise -- previously proven only by
 * {@code StatefulSetReconcilerTest} (pure in-memory placement) and {@code
 * LocalDiskVolumeManagerTest} (pure filesystem, no cluster), never together: that a persistent
 * volume's data actually survives a real worker JVM crash-and-respawn, and the same index lands
 * back on the same node afterward. Mirrors {@code SelfHealingIT}'s real-process-kill pattern
 * (worker tier: {@code WorkerProcessSupervisor} respawns a killed worker, the deployment recovers),
 * but for the sticky-placement/persistent-volume guarantee specifically.
 */
@Tag("smoke")
class StatefulSetPersistenceIT extends GreeterSmokeClusterSupport {

  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void a_statefulset_instance_keeps_its_sticky_node_and_its_volume_data_across_a_worker_restart()
      throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    String moduleName = "com.gimle.fixture.statefulprobe";
    Path jar = buildStatefulModuleJar(moduleName, "1.0.0");
    submitStatefulSet(baseUrl, "orders-statefulset", moduleName, "1.0.0", jar, 1);

    await(
        () -> statefulSetIndexReady(baseUrl, "orders-statefulset", 0),
        Duration.ofSeconds(60),
        "orders-statefulset's index 0 should reach ready");

    String originalNodeId =
        statefulSetIndexNode(baseUrl, "orders-statefulset", 0)
            .orElseThrow(() -> new AssertionError("expected index 0 to already have a nodeId"));

    await(
        () ->
            instanceLogContains(
                baseUrl,
                "orders-statefulset",
                0,
                "APPLICATION",
                "volume marker written for the first time"),
        Duration.ofSeconds(30),
        "the fixture module should have written its volume marker on its first real start");

    ProcessHandle firstWorker =
        findWorkerDescendantForKey(cluster.agentProcesses().get(0), "orders-statefulset#0")
            .orElseThrow(
                () -> new AssertionError("expected a live WorkerMain descendant for index 0"));
    long firstWorkerPid = firstWorker.pid();
    firstWorker.destroyForcibly();

    await(
        () -> {
          Optional<ProcessHandle> current =
              findWorkerDescendantForKey(cluster.agentProcesses().get(0), "orders-statefulset#0");
          return current.isPresent()
              && current.get().pid() != firstWorkerPid
              && statefulSetIndexReady(baseUrl, "orders-statefulset", 0);
        },
        Duration.ofSeconds(60),
        "a new worker process should replace pid "
            + firstWorkerPid
            + " and index 0 should return to ready");

    assertEquals(
        Optional.of(originalNodeId),
        statefulSetIndexNode(baseUrl, "orders-statefulset", 0),
        "the replacement worker must land on the same sticky node as before the crash, not a"
            + " different one");

    await(
        () ->
            instanceLogContains(
                baseUrl,
                "orders-statefulset",
                0,
                "APPLICATION",
                "volume marker already present from a previous start"),
        Duration.ofSeconds(30),
        "the restarted instance should read back the marker its own previous incarnation wrote --"
            + " proof the volume's data, not just its sticky node, survived the crash");

    assertTrue(
        instanceLogContains(
            baseUrl,
            "orders-statefulset",
            0,
            "APPLICATION",
            "volume marker written for the first time"),
        "the pre-crash log line should still be present too -- this is one continuous"
            + " per-instance log, not a fresh one per worker process");
  }
}
