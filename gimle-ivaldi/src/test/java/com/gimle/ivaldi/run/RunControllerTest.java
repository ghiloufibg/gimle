package com.gimle.ivaldi.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.ivaldi.cluster.ClusterStore;
import com.gimle.ivaldi.validate.RenderedFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The controller's own fast, deterministic contract: rejecting a run against an unknown cluster or
 * a stop with nothing running, and the idle/not-found shapes {@code IvaldiServer} exposes before
 * any run ever starts. A real boot-and-deploy pipeline needs a genuine multi-process cluster to run
 * against, so that end-to-end path is left to a real-cluster fixture (the same split every other
 * in-process-vs-real-cluster pair in this repo already makes), not asserted here.
 */
class RunControllerTest {

  @TempDir Path tempDir;

  private ClusterStore clusters;
  private RunController controller;

  @BeforeEach
  void setUp() {
    clusters = new ClusterStore(tempDir.resolve("clusters"));
    controller = new RunController(clusters, tempDir);
  }

  @Test
  void current_snapshot_is_idle_before_anything_has_run() {
    Map<String, Object> snapshot = controller.currentSnapshotJson();

    assertEquals("idle", snapshot.get("status"));
  }

  @Test
  void starting_a_run_against_an_unknown_cluster_is_refused() {
    List<RenderedFile> files = List.of(new RenderedFile("topology.yaml", "name: t"));

    assertThrows(
        RunController.NotFoundException.class,
        () -> controller.start("no-such-cluster", Optional.empty(), files, Map.of()));
  }

  @Test
  void stopping_with_nothing_running_is_refused() {
    assertThrows(RunController.NotFoundException.class, controller::stop);
  }

  @Test
  void log_of_an_unknown_run_id_is_empty() {
    assertEquals(Optional.empty(), controller.log("no-such-run", 0));
  }

  @Test
  void starting_a_run_against_a_known_cluster_moves_off_idle() {
    Map<String, Object> cluster =
        clusters.create("{\"name\":\"local\",\"controlPlaneUrl\":\"127.0.0.1:8080\"}");
    List<RenderedFile> files = List.of(new RenderedFile("topology.yaml", "name: t"));

    Map<String, Object> started =
        controller.start(String.valueOf(cluster.get("id")), Optional.empty(), files, Map.of());

    assertTrue(started.get("id") instanceof String id && !id.isBlank());
    assertEquals(cluster.get("id"), started.get("clusterId"));
    // The pipeline itself runs on a separate virtual thread and will fail fast past this point
    // (no bundle.yaml in the file set), which is exactly why this test only asserts the
    // synchronous start() contract, not the run's eventual terminal status.
  }
}
