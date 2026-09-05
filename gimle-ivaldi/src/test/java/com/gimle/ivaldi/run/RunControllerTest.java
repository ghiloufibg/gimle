package com.gimle.ivaldi.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Set;
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
    controller.start("c1", Optional.empty(), filesMissingTheirJar(), Map.of());
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

  /**
   * A stop after a cancelled run has finished must still tear down and settle. Returning early on
   * the cancelled flag left the status stuck at STOPPING -- which reads as in-flight -- so every
   * later run was refused with a 409 until the process was restarted.
   */
  @Test
  void a_stop_after_a_cancelled_run_settles_instead_of_wedging_the_controller() {
    clusters.save("c1", "{\"name\":\"local\",\"controlPlaneUrl\":\"http://127.0.0.1:8080\"}");
    controller.start("c1", Optional.empty(), filesMissingTheirJar(), Map.of());
    awaitSettled();

    controller.stop();
    Map<String, Object> settled = awaitSettled();

    assertEquals("idle", settled.get("status"));
    // and the controller accepts work again rather than answering 409 forever
    controller.start("c1", Optional.empty(), filesMissingTheirJar(), Map.of());
    assertEquals("failed", awaitSettled().get("status"));
  }

  /**
   * A manifest carries no artifact path of its own -- the jar backing its module coordinate is
   * recorded in the file set's own {@code ivaldi.artifacts.yaml}, which is what a run reads to
   * decide what to push.
   */
  private List<RenderedFile> filesMissingTheirJar() {
    return List.of(
        new RenderedFile("topology.yaml", TOPOLOGY),
        new RenderedFile("bundle.yaml", BUNDLE),
        new RenderedFile(
            "manifests/01-app.yaml",
            """
            apiVersion: v1
            kind: Deployment
            name: app
            replicas: 1
            module:
              name: com.example.app
              version: 1.0.0
            """),
        new RenderedFile(
            "ivaldi.artifacts.yaml",
            """
            artifacts:
              - manifest: manifests/01-app.yaml
                module: com.example.app
                version: 1.0.0
                path: /nowhere/does-not-exist.jar
            """));
  }

  /**
   * A run is a property of the cluster it targets, not of the process. Holding one globally meant
   * starting a second abandoned the first with no way to reach it, and every blueprint's Runner
   * rendered whichever run happened to be current.
   */
  @Test
  void two_clusters_each_hold_their_own_run() {
    clusters.save("c1", "{\"name\":\"one\",\"controlPlaneUrl\":\"http://127.0.0.1:8080\"}");
    clusters.save("c2", "{\"name\":\"two\",\"controlPlaneUrl\":\"http://127.0.0.1:8081\"}");

    controller.start("c1", Optional.of("bp-one"), filesMissingTheirJar(), Map.of());
    awaitSettled();
    controller.start("c2", Optional.of("bp-two"), filesMissingTheirJar(), Map.of());
    awaitSettled();

    assertEquals(2, controller.allSnapshotsJson().size());
    assertEquals("c1", controller.clusterSnapshotJson("c1").get("clusterId"));
    assertEquals("c2", controller.clusterSnapshotJson("c2").get("clusterId"));
  }

  @Test
  void a_blueprint_sees_only_its_own_run() {
    clusters.save("c1", "{\"name\":\"one\",\"controlPlaneUrl\":\"http://127.0.0.1:8080\"}");
    controller.start("c1", Optional.of("bp-one"), filesMissingTheirJar(), Map.of());
    awaitSettled();

    assertEquals("c1", controller.blueprintSnapshotJson("bp-one").get("clusterId"));
    // a blueprint that has never run must not be handed someone else's cluster
    Map<String, Object> other = controller.blueprintSnapshotJson("bp-never-run");
    assertEquals("idle", other.get("status"));
    assertNull(other.get("clusterId"));
  }

  @Test
  void a_run_in_flight_blocks_only_its_own_cluster() {
    clusters.save("c1", "{\"name\":\"one\",\"controlPlaneUrl\":\"http://127.0.0.1:8080\"}");
    clusters.save("c2", "{\"name\":\"two\",\"controlPlaneUrl\":\"http://127.0.0.1:8081\"}");
    controller.start("c1", Optional.of("bp-one"), filesMissingTheirJar(), Map.of());
    awaitSettled();

    // a settled run never blocks anything, and a different cluster is always free
    controller.start("c2", Optional.of("bp-two"), filesMissingTheirJar(), Map.of());
    assertEquals("c2", awaitSettled().get("clusterId"));
  }

  /**
   * A different blueprint targeting a cluster another blueprint already has a settled (not
   * in-flight) run against used to be refused outright -- a cluster's infra is no longer "owned" by
   * whichever blueprint deployed to it first, so this now simply starts its own,
   * independently-tracked deployment (see the class javadoc's "One cluster, many deployments"
   * section) rather than colliding with the first at all.
   */
  @Test
  void two_different_blueprints_can_each_run_against_the_same_shared_cluster() {
    clusters.save("c1", "{\"name\":\"one\",\"controlPlaneUrl\":\"http://127.0.0.1:8080\"}");
    controller.start("c1", Optional.of("bp-one"), filesMissingTheirJar(), Map.of());
    awaitSettled("bp-one");

    controller.start("c1", Optional.of("bp-two"), filesMissingTheirJar(), Map.of());
    Map<String, Object> second = awaitSettled("bp-two");

    assertEquals("failed", second.get("status"));
    // bp-one's own run is untouched by bp-two's start.
    assertEquals("failed", controller.blueprintSnapshotJson("bp-one").get("status"));
    assertEquals(2, controller.allSnapshotsJson().size());
  }

  /** The same blueprint re-running its own cluster -- an ordinary redeploy -- is unaffected. */
  @Test
  void the_same_blueprint_can_always_rerun_its_own_cluster() {
    clusters.save("c1", "{\"name\":\"one\",\"controlPlaneUrl\":\"http://127.0.0.1:8080\"}");
    controller.start("c1", Optional.of("bp-one"), filesMissingTheirJar(), Map.of());
    awaitSettled();

    controller.start("c1", Optional.of("bp-one"), filesMissingTheirJar(), Map.of());

    assertEquals("bp-one", awaitSettled().get("blueprintId"));
    // Still one deployment, not two -- a redeploy reuses its own key rather than minting another.
    assertEquals(1, controller.allSnapshotsJson().size());
  }

  /**
   * The blueprint(s) whose run applied a deployment are recorded beside the cluster's topology, so
   * a cluster recovered after a restart still belongs to something -- adopted with none recorded it
   * could be stopped but never shown as running anywhere.
   */
  @Test
  void starting_a_run_records_the_deployment_against_the_cluster() {
    clusters.save("c1", "{\"name\":\"one\",\"controlPlaneUrl\":\"http://127.0.0.1:8080\"}");

    controller.start("c1", Optional.of("bp-one"), filesMissingTheirJar(), Map.of());
    awaitSettled();

    assertEquals(Set.of("bp-one"), clusters.deployments("c1"));
  }

  /**
   * A cluster shared by several blueprints must not be deleted while any of them is still live --
   * generalized from the single-run check this cluster's own {@link ClusterStore} once needed, now
   * that a cluster can hold more than one.
   */
  @Test
  void requiring_no_live_run_checks_every_deployment_on_the_cluster_not_just_one() {
    clusters.save("c1", "{\"name\":\"one\",\"controlPlaneUrl\":\"http://127.0.0.1:8080\"}");
    controller.start("c1", Optional.of("bp-one"), filesMissingTheirJar(), Map.of());
    awaitSettled("bp-one");
    controller.start("c1", Optional.of("bp-two"), filesMissingTheirJar(), Map.of());
    awaitSettled("bp-two");

    // Both settled to "failed", which still counts as live -- neither was ever explicitly stopped.
    assertThrows(
        RunController.ClusterInUseException.class, () -> controller.requireNoLiveRun("c1"));
  }

  /**
   * Pulled out of {@link RunController#execute} so this exact refusal is directly testable without
   * booting a real cluster -- see the class javadoc's "One cluster, many deployments" section.
   */
  @Test
  void a_topology_change_is_refused_while_another_deployment_still_shares_the_cluster() {
    Optional<String> message = RunController.conflictingRebootMessage("c1", Set.of("bp-other"));

    assertTrue(message.isPresent());
    assertTrue(message.get().contains("bp-other"), message.get());
  }

  @Test
  void a_topology_change_is_allowed_when_nothing_else_shares_the_cluster() {
    assertEquals(Optional.empty(), RunController.conflictingRebootMessage("c1", Set.of()));
  }

  /**
   * The control plane treats a present-but-empty allow-list as a real "deny this direction" policy,
   * distinct from the direction not being restricted at all -- so dropping it here just because it
   * happened to be empty silently turned "deny all cross-tenant callers" into "no restriction
   * whatsoever" once it reached the wire.
   */
  @Test
  void network_policy_body_keeps_a_present_but_empty_allow_list() {
    RenderedFile manifest = new RenderedFile("manifests/05-networkpolicy-deny.yaml", "");
    Map<String, Object> mapping =
        Map.of("name", "deny-all", "tenantId", "acme", "allowedCallerTenantIds", List.of());

    Map<String, Object> body = RunController.networkPolicyBody(manifest, mapping);

    assertTrue(body.containsKey("allowedCallerTenantIds"), body.toString());
    assertEquals(List.of(), body.get("allowedCallerTenantIds"));
  }

  @Test
  void network_policy_body_omits_a_direction_the_manifest_never_declared() {
    RenderedFile manifest = new RenderedFile("manifests/05-networkpolicy-open.yaml", "");
    Map<String, Object> mapping = Map.of("name", "open", "tenantId", "acme");

    Map<String, Object> body = RunController.networkPolicyBody(manifest, mapping);

    assertTrue(
        !body.containsKey("allowedCallerTenantIds") && !body.containsKey("deploymentNames"),
        body.toString());
  }

  @Test
  void network_policy_body_carries_a_non_empty_allow_list_through_unchanged() {
    RenderedFile manifest = new RenderedFile("manifests/05-networkpolicy-scoped.yaml", "");
    Map<String, Object> mapping =
        Map.of(
            "name",
            "scoped",
            "tenantId",
            "acme",
            "allowedCallerTenantIds",
            List.of("billing"),
            "deploymentNames",
            List.of("api"));

    Map<String, Object> body = RunController.networkPolicyBody(manifest, mapping);

    assertEquals(List.of("billing"), body.get("allowedCallerTenantIds"));
    assertEquals(List.of("api"), body.get("deploymentNames"));
  }

  private Map<String, Object> awaitSettled() {
    return awaitSettled(controller::currentSnapshotJson);
  }

  /** Settles on one blueprint's own run specifically -- needed once more than one may be live. */
  private Map<String, Object> awaitSettled(String blueprintId) {
    return awaitSettled(() -> controller.blueprintSnapshotJson(blueprintId));
  }

  private Map<String, Object> awaitSettled(java.util.function.Supplier<Map<String, Object>> read) {
    for (int attempt = 0; attempt < 200; attempt++) {
      Map<String, Object> snapshot = read.get();
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
    throw new IllegalStateException("run never settled: " + read.get());
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
