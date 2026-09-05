package com.gimle.ivaldi.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.tls.TlsSettings;
import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.TopologyParser;
import com.gimle.ivaldi.cluster.ClusterStore;
import com.gimle.ivaldi.validate.RenderedFile;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

  /**
   * A jar path that names nothing is the validate phase's problem, not the push step's. Discovered
   * at push time it costs a full stop-and-respawn of a cluster that was running fine.
   */
  @Test
  void a_workload_naming_a_jar_that_is_not_there_fails_before_anything_is_touched() {
    clusters.save("c1", "{\"name\":\"local\",\"controlPlaneUrl\":\"http://127.0.0.1:8080\"}");
    List<RenderedFile> files =
        List.of(
            new RenderedFile("topology.yaml", TOPOLOGY),
            new RenderedFile("bundle.yaml", BUNDLE),
            new RenderedFile(
                "manifests/01-app.yaml",
                """
                kind: Deployment
                name: app
                replicas: 1
                module:
                  name: com.example.app
                  version: 1.0.0
                artifactPath: /nowhere/does-not-exist.jar
                """));

    controller.start("c1", Optional.empty(), files, Map.of());
    Map<String, Object> snapshot = awaitSettled();

    assertEquals("failed", snapshot.get("status"));
    assertTrue(String.valueOf(snapshot.get("error")).contains("does-not-exist.jar"), snapshot + "");
    assertEquals(Optional.empty(), clusters.appliedTopology("c1"));
  }

  /**
   * Every leaf in an mTLS cluster is minted for its machine's declared hostname, so an IP literal
   * fails subject-alternative-name matching. Caught before the boot, where it reads as the wrong
   * address rather than as a broken cluster.
   */
  @Test
  void an_mtls_cluster_addressed_by_ip_is_refused_before_anything_boots() {
    clusters.save("c1", "{\"name\":\"local\",\"controlPlaneUrl\":\"https://127.0.0.1:8080\"}");

    controller.start("c1", Optional.empty(), mtlsFiles(), Map.of());
    Map<String, Object> snapshot = awaitSettled();

    assertEquals("failed", snapshot.get("status"));
    assertTrue(String.valueOf(snapshot.get("error")).contains("by IP address"), snapshot + "");
    assertEquals(Optional.empty(), clusters.appliedTopology("c1"));
  }

  @Test
  void an_mtls_cluster_addressed_at_a_host_the_topology_never_declares_is_refused() {
    clusters.save("c1", "{\"name\":\"local\",\"controlPlaneUrl\":\"https://elsewhere:8080\"}");

    controller.start("c1", Optional.empty(), mtlsFiles(), Map.of());
    Map<String, Object> snapshot = awaitSettled();

    assertEquals("failed", snapshot.get("status"));
    assertTrue(String.valueOf(snapshot.get("error")).contains("never declares"), snapshot + "");
  }

  /**
   * The identity a run authenticates with comes from the topology it is running, not from this
   * process's own configuration -- which is what lets one Ivaldi target a plaintext cluster and an
   * mTLS one, and what makes an mTLS cluster's very first run possible at all: the material does
   * not exist until that run's own boot phase mints it.
   */
  @Test
  void an_mtls_run_authenticates_with_the_operator_material_its_own_topology_minted()
      throws Exception {
    Path materialDir = Files.createDirectories(tempDir.resolve("tls"));
    for (String name : List.of("operator.crt", "operator.key", "ca.crt")) {
      Files.writeString(materialDir.resolve(name), "x");
    }

    Optional<TlsSettings> material =
        RunController.clientMaterialFor(topologyWithMaterialDir(materialDir), Map.of());

    assertEquals(
        Optional.of(materialDir.resolve("operator.crt")), material.map(TlsSettings::certFile));
    assertEquals(
        Optional.of(materialDir.resolve("operator.key")), material.map(TlsSettings::keyFile));
    assertEquals(Optional.of(materialDir.resolve("ca.crt")), material.map(TlsSettings::caFile));
  }

  @Test
  void a_cluster_connection_carrying_its_own_certificate_overrides_the_topology_default()
      throws Exception {
    Path materialDir = Files.createDirectories(tempDir.resolve("tls"));
    Path elsewhere = Files.createDirectories(tempDir.resolve("elsewhere"));
    for (String name : List.of("operator.crt", "operator.key", "ca.crt")) {
      Files.writeString(materialDir.resolve(name), "x");
    }
    Files.writeString(elsewhere.resolve("me.crt"), "x");
    Files.writeString(elsewhere.resolve("me.key"), "x");

    Optional<TlsSettings> material =
        RunController.clientMaterialFor(
            topologyWithMaterialDir(materialDir),
            Map.of(
                "clientCertPath", elsewhere.resolve("me.crt").toString(),
                "clientKeyPath", elsewhere.resolve("me.key").toString()));

    assertEquals(Optional.of(elsewhere.resolve("me.crt")), material.map(TlsSettings::certFile));
    // the CA still comes from the topology, which is where this cluster's own trust root lives
    assertEquals(Optional.of(materialDir.resolve("ca.crt")), material.map(TlsSettings::caFile));
  }

  @Test
  void a_plaintext_topology_needs_no_client_material_at_all() {
    Topology plaintext =
        TopologyParser.parse(new ByteArrayInputStream(TOPOLOGY.getBytes(StandardCharsets.UTF_8)));

    assertEquals(Optional.empty(), RunController.clientMaterialFor(plaintext, Map.of()));
  }

  @Test
  void an_mtls_cluster_whose_material_is_missing_says_which_file_is_not_there() {
    Topology topology = topologyWithMaterialDir(tempDir.resolve("never-minted"));

    RuntimeException failure =
        assertThrows(
            RuntimeException.class, () -> RunController.clientMaterialFor(topology, Map.of()));

    assertTrue(failure.getMessage().contains("operator.crt"), failure.getMessage());
  }

  private static Topology topologyWithMaterialDir(Path materialDir) {
    String yaml =
        MTLS_TOPOLOGY.replace(
            "materialDir: /tmp/gimle-ivaldi-test-never-booted/tls", "materialDir: " + materialDir);
    return TopologyParser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
  }

  private List<RenderedFile> mtlsFiles() {
    return List.of(
        new RenderedFile("topology.yaml", MTLS_TOPOLOGY),
        new RenderedFile(
            "bundle.yaml", BUNDLE.replace("workloads:\n  - file: manifests/01-app.yaml\n", "")));
  }

  private Map<String, Object> awaitSettled() {
    for (int attempt = 0; attempt < 200; attempt++) {
      Map<String, Object> snapshot = controller.currentSnapshotJson();
      String status = String.valueOf(snapshot.get("status"));
      if (status.equals("failed") || status.equals("running") || status.equals("idle")) {
        return snapshot;
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted waiting for the run to settle", e);
      }
    }
    throw new IllegalStateException("run never settled: " + controller.currentSnapshotJson());
  }

  private static final String TOPOLOGY =
      """
      name: t
      machines:
        - name: local
          host: 127.0.0.1
      runtime:
        dataRoot: /tmp/gimle-ivaldi-test-never-booted
      store:
        replicas:
          - machine: local
      controlPlane:
        replicas:
          - machine: local
      fafnir:
        keyFile: /tmp/gimle-ivaldi-test-never-booted/fafnir.key
        replicas:
          - machine: local
      andvari:
        replicas:
          - machine: local
      """;

  private static final String MTLS_TOPOLOGY =
      """
      name: t
      transport: mtls
      tls:
        materialDir: /tmp/gimle-ivaldi-test-never-booted/tls
      machines:
        - name: local
          host: cluster-host.example
      runtime:
        dataRoot: /tmp/gimle-ivaldi-test-never-booted
      store:
        replicas:
          - machine: local
      controlPlane:
        replicas:
          - machine: local
      fafnir:
        keyFile: /tmp/gimle-ivaldi-test-never-booted/fafnir.key
        replicas:
          - machine: local
      """;

  private static final String BUNDLE =
      """
      kind: Bundle
      name: t
      version: 1.0.0
      workloads:
        - file: manifests/01-app.yaml
      """;
}
