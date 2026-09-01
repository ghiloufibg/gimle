package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.protocol.Json;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.store.StateStore;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * {@code PUT /<workload>/{name}?dryRun=true} over a real loopback HTTP connection against a real
 * {@link ApiServer}.
 *
 * <p>The property under test throughout is agreement: a preview is only worth having if it says
 * what the real submission would say, so where a rejection is predicted the same manifest is then
 * actually submitted and the two are compared. Every test additionally asserts the store is
 * byte-for-byte unchanged across the dry-run -- "proposes nothing" is the other half of the
 * contract, and reading the code is not proof of it.
 *
 * <p>Plaintext, so authorization is skipped entirely (see {@code ApiServer#requireAuthorized}) --
 * the RBAC half of the preview only means anything over mTLS and is covered in {@code
 * ApiServerAuthzTest}.
 */
@ResourceLock(value = Resources.SYSTEM_PROPERTIES, mode = ResourceAccessMode.READ)
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerDryRunTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessStore inProcessStore;
  private InProcessFafnir inProcessFafnir;
  private StateStore store;
  private ApiServer server;
  private HttpClient client;
  private String baseUrl;

  @BeforeEach
  void startServer() throws IOException {
    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    store = inProcessStore.store();
    inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client());
    server.start();
    baseUrl = "http://localhost:" + server.port();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopServer() {
    server.close();
    inProcessFafnir.close();
    inProcessStore.close();
  }

  @Test
  @Timeout(20)
  void a_dry_run_predicts_the_quota_rejection_the_real_submission_then_actually_gets()
      throws Exception {
    store.putTenant(new Tenant("tight", new ResourceQuota(1, 1, 1)));
    Path jar = buildFixtureJar("com.gimle.fixture.dryrun.quota");
    String yaml =
        deploymentYaml(
            "over-quota", 1, jar, "com.gimle.fixture.dryrun.quota", Optional.of("tight"));

    String before = storeFingerprint();
    HttpResponse<String> preview = put("/deployments/over-quota?dryRun=true", yaml);
    assertEquals(200, preview.statusCode());
    Map<String, Object> verdict = Json.asObject(Json.parse(preview.body()));
    assertEquals(false, verdict.get("admitted"));
    assertEquals(409L, ((Number) verdict.get("wouldRespondStatus")).longValue());
    // A rejected verdict still reports every stage, so a caller parsing it sees one shape whatever
    // went wrong -- the stages after the failure are SKIPPED, not simply absent.
    assertEquals(
        List.of("rbac", "manifest", "artifact", "admission", "placement"),
        Json.asObjectList(verdict.get("checks")).stream().map(c -> c.get("name")).toList());
    assertEquals("SKIPPED", checkOutcome(verdict, "placement"));
    String predictedReason = failedCheckDetail(verdict, "admission");
    assertTrue(
        predictedReason.contains("past its resource quota"),
        "expected a quota rejection, got: " + predictedReason);
    assertEquals(before, storeFingerprint(), "a dry run must not change the store");

    HttpResponse<String> real = put("/deployments/over-quota", yaml);
    assertEquals(409, real.statusCode());
    assertEquals(
        predictedReason,
        real.body(),
        "the preview's predicted rejection must be the real rejection, word for word");
    assertTrue(store.getDeployment(Optional.of("tight"), "over-quota").isEmpty());
  }

  @Test
  @Timeout(20)
  void a_dry_run_predicts_a_limit_range_violation() throws Exception {
    store.putTenant(new Tenant("bounded", new ResourceQuota(1_000_000_000L, 4000, 10)));
    // The fixture module requests 16Mi; a floor of 128Mi puts it below the tenant's minimum.
    HttpResponse<String> limitRange =
        put(
            "/limitranges/bounded",
            """
            {"minRequest": {"memory": "128Mi", "cpu": "1m"}}
            """);
    assertEquals(200, limitRange.statusCode());

    Path jar = buildFixtureJar("com.gimle.fixture.dryrun.limitrange");
    String yaml =
        deploymentYaml(
            "too-small", 1, jar, "com.gimle.fixture.dryrun.limitrange", Optional.of("bounded"));

    String before = storeFingerprint();
    Map<String, Object> verdict = dryRun("/deployments/too-small", yaml);
    assertEquals(false, verdict.get("admitted"));
    assertEquals(409L, ((Number) verdict.get("wouldRespondStatus")).longValue());
    String reason = failedCheckDetail(verdict, "admission");
    assertTrue(reason.contains("limit range"), "expected a limit-range rejection, got: " + reason);
    assertEquals(before, storeFingerprint());
    assertTrue(store.getDeployment(Optional.of("bounded"), "too-small").isEmpty());
  }

  @Test
  @Timeout(20)
  void a_dry_run_predicts_no_feasible_placement_and_names_the_short_dimension() throws Exception {
    // Registered, heartbeating, and far too small for the fixture module's own 16Mi request.
    registerNode("cramped", 1024L, 4000L);

    Path jar = buildFixtureJar("com.gimle.fixture.dryrun.unplaceable");
    String yaml =
        deploymentYaml("no-room", 1, jar, "com.gimle.fixture.dryrun.unplaceable", Optional.empty());

    String before = storeFingerprint();
    Map<String, Object> verdict = dryRun("/deployments/no-room", yaml);
    // Placement is advisory: nothing about being unschedulable rejects a submission, so the
    // verdict still admits it -- exactly what the real PUT would do.
    assertEquals(true, verdict.get("admitted"));
    String placementDetail = failedCheckDetail(verdict, "placement");
    assertTrue(
        placementDetail.contains("memory is short by"),
        "expected the scheduler's own shortfall message, got: " + placementDetail);
    assertTrue(placementDetail.contains("cramped"), placementDetail);

    Map<String, Object> placement = Json.asObject(verdict.get("placement"));
    assertEquals(1L, ((Number) placement.get("replicasEvaluated")).longValue());
    assertEquals(0L, ((Number) placement.get("placeable")).longValue());
    assertEquals(1L, ((Number) placement.get("unplaceable")).longValue());
    assertEquals(before, storeFingerprint());
    assertTrue(store.getDeployment(Optional.empty(), "no-room").isEmpty());
  }

  @Test
  @Timeout(20)
  void a_dry_run_reports_the_node_each_placeable_replica_would_land_on() throws Exception {
    registerNode("roomy-a", 512L * 1024 * 1024, 4000L);
    registerNode("roomy-b", 512L * 1024 * 1024, 4000L);

    Path jar = buildFixtureJar("com.gimle.fixture.dryrun.placeable");
    String yaml =
        deploymentYaml("spread", 2, jar, "com.gimle.fixture.dryrun.placeable", Optional.empty());

    String before = storeFingerprint();
    Map<String, Object> verdict = dryRun("/deployments/spread", yaml);
    assertEquals(true, verdict.get("admitted"));
    Map<String, Object> placement = Json.asObject(verdict.get("placement"));
    assertEquals(2L, ((Number) placement.get("replicasEvaluated")).longValue());
    assertEquals(2L, ((Number) placement.get("placeable")).longValue());
    List<Map<String, Object>> placements = Json.asObjectList(placement.get("placements"));
    assertEquals(2, placements.size());
    for (Map<String, Object> entry : placements) {
      assertTrue(
          Set.of("roomy-a", "roomy-b").contains(entry.get("nodeId")),
          "unexpected node: " + entry.get("nodeId"));
    }
    assertEquals(before, storeFingerprint());
  }

  @Test
  @Timeout(20)
  void a_dry_run_of_a_manifest_whose_kind_does_not_match_the_route_predicts_the_400()
      throws Exception {
    Path jar = buildFixtureJar("com.gimle.fixture.dryrun.wrongkind");
    String yaml =
        deploymentYaml("mismatch", 1, jar, "com.gimle.fixture.dryrun.wrongkind", Optional.empty());

    String before = storeFingerprint();
    Map<String, Object> verdict = dryRun("/jobs/mismatch", yaml);
    assertEquals(false, verdict.get("admitted"));
    assertEquals(400L, ((Number) verdict.get("wouldRespondStatus")).longValue());
    assertEquals(
        "manifest kind does not match /jobs route (expected kind: Job)",
        failedCheckDetail(verdict, "manifest"));
    assertEquals(before, storeFingerprint());

    HttpResponse<String> real = put("/jobs/mismatch", yaml);
    assertEquals(400, real.statusCode());
    assertEquals(failedCheckDetail(verdict, "manifest"), real.body());
  }

  @Test
  @Timeout(30)
  void every_placeable_workload_kind_previews_and_writes_nothing() throws Exception {
    registerNode("roomy", 512L * 1024 * 1024, 4000L);
    Path jar = buildFixtureJar("com.gimle.fixture.dryrun.kinds");
    String module = "com.gimle.fixture.dryrun.kinds";
    String before = storeFingerprint();

    assertEquals(
        true,
        dryRun("/deployments/d1", deploymentYaml("d1", 1, jar, module, Optional.empty()))
            .get("admitted"));
    assertEquals(true, dryRun("/jobs/j1", jobYaml("j1", jar, module)).get("admitted"));
    assertEquals(true, dryRun("/cronjobs/c1", cronJobYaml("c1", jar, module)).get("admitted"));
    assertEquals(
        true, dryRun("/daemonsets/ds1", daemonSetYaml("ds1", jar, module)).get("admitted"));
    assertEquals(
        true, dryRun("/statefulsets/ss1", statefulSetYaml("ss1", jar, module)).get("admitted"));

    assertEquals(before, storeFingerprint());
    assertTrue(store.listDeployments().isEmpty());
    assertTrue(store.listJobSpecs().isEmpty());
    assertTrue(store.listCronJobSpecs().isEmpty());
    assertTrue(store.listDaemonSetSpecs().isEmpty());
    assertTrue(store.listStatefulSetSpecs().isEmpty());

    // The same manifest applied for real does change the store -- proving the fingerprint above
    // would have caught a write, rather than being blind to one.
    assertEquals(
        200,
        put("/deployments/d1", deploymentYaml("d1", 1, jar, module, Optional.empty()))
            .statusCode());
    assertNotEquals(before, storeFingerprint());
  }

  @Test
  @Timeout(20)
  void a_cronjob_dry_run_reports_that_it_places_nothing_of_its_own() throws Exception {
    Path jar = buildFixtureJar("com.gimle.fixture.dryrun.cron");
    Map<String, Object> verdict =
        dryRun("/cronjobs/nightly", cronJobYaml("nightly", jar, "com.gimle.fixture.dryrun.cron"));
    assertEquals(true, verdict.get("admitted"));
    assertFalse(verdict.containsKey("placement"));
    assertEquals("SKIPPED", checkOutcome(verdict, "placement"));
    assertEquals("SKIPPED", checkOutcome(verdict, "artifact"));
  }

  @Test
  @Timeout(20)
  void a_dry_run_of_an_unreadable_artifact_predicts_the_admission_rejection() throws Exception {
    store.putTenant(new Tenant("acme", new ResourceQuota(1_000_000_000L, 4000, 10)));
    String yaml =
        """
        kind: Deployment
        name: ghost
        module:
          name: com.gimle.fixture.dryrun.ghost
          version: 1.0.0
        artifactPath: %s
        replicas: 1
        tenantId: acme
        """
            .formatted(tempDir.resolve("does-not-exist.jar").toAbsolutePath());

    String before = storeFingerprint();
    Map<String, Object> verdict = dryRun("/deployments/ghost", yaml);
    assertEquals(false, verdict.get("admitted"));
    assertEquals(409L, ((Number) verdict.get("wouldRespondStatus")).longValue());
    assertTrue(failedCheckDetail(verdict, "admission").contains("artifact unreadable"));
    assertEquals(before, storeFingerprint());

    HttpResponse<String> real = put("/deployments/ghost", yaml);
    assertEquals(409, real.statusCode());
    assertEquals(failedCheckDetail(verdict, "admission"), real.body());
  }

  // ---- helpers ----

  private Map<String, Object> dryRun(String path, String yaml) throws Exception {
    HttpResponse<String> response = put(path + "?dryRun=true", yaml);
    assertEquals(200, response.statusCode(), response.body());
    return Json.asObject(Json.parse(response.body()));
  }

  private HttpResponse<String> put(String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /**
   * Everything a workload PUT could possibly write: the five desired-state collections, the
   * revision history a Deployment/DaemonSet/StatefulSet PUT appends to, the assignments a
   * reconciler would derive, and the audit trail. Compared as one string before and after each dry
   * run.
   */
  private String storeFingerprint() {
    return String.join(
        "|",
        String.valueOf(store.listDeployments()),
        String.valueOf(store.listJobSpecs()),
        String.valueOf(store.listCronJobSpecs()),
        String.valueOf(store.listDaemonSetSpecs()),
        String.valueOf(store.listStatefulSetSpecs()),
        String.valueOf(store.listAssignments()),
        String.valueOf(store.listStatefulSetAssignments()),
        String.valueOf(store.listJobRuns()),
        String.valueOf(
            store.listAuditEvents(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())),
        String.valueOf(store.listControllerRevisions("Deployment", Optional.empty(), "d1").size()));
  }

  private void registerNode(String nodeId, long memoryBytes, long cpuMillicores) {
    store.putNodeRegistration(
        new NodeRegistration(
            nodeId,
            new NodeCapabilities(
                Set.of(IsolationTier.TIER_1, IsolationTier.TIER_2), Set.<String>of()),
            Optional.empty()));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            nodeId, new ResourceUsageSnapshot(memoryBytes, 0, cpuMillicores, 0), List.of()));
  }

  private Path buildFixtureJar(String uniqueName) {
    return TestModuleBuilder.module("module " + uniqueName + " {\n}\n")
        .withDescriptor(TestModuleBuilder.minimalDescriptor(uniqueName, "1.0.0"))
        .build(tempDir, uniqueName + ".jar");
  }

  private static String failedCheckDetail(Map<String, Object> verdict, String checkName) {
    for (Map<String, Object> check : Json.asObjectList(verdict.get("checks"))) {
      if (checkName.equals(check.get("name")) && "FAILED".equals(check.get("outcome"))) {
        return String.valueOf(check.get("detail"));
      }
    }
    throw new AssertionError("no failed '" + checkName + "' check in verdict: " + verdict);
  }

  private static String checkOutcome(Map<String, Object> verdict, String checkName) {
    for (Map<String, Object> check : Json.asObjectList(verdict.get("checks"))) {
      if (checkName.equals(check.get("name"))) {
        return String.valueOf(check.get("outcome"));
      }
    }
    throw new AssertionError("no '" + checkName + "' check in verdict: " + verdict);
  }

  private static String deploymentYaml(
      String name, int replicas, Path jar, String moduleName, Optional<String> tenantId) {
    return """
        kind: Deployment
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        replicas: %d
        %s"""
        .formatted(
            name,
            moduleName,
            jar.toAbsolutePath(),
            replicas,
            tenantId.map(id -> "tenantId: " + id + "\n").orElse(""));
  }

  private static String jobYaml(String name, Path jar, String moduleName) {
    return """
        kind: Job
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        """
        .formatted(name, moduleName, jar.toAbsolutePath());
  }

  private static String cronJobYaml(String name, Path jar, String moduleName) {
    return """
        kind: CronJob
        name: %s
        schedule: "*/5 * * * *"
        jobTemplate:
          module:
            name: %s
            version: 1.0.0
          artifactPath: %s
        """
        .formatted(name, moduleName, jar.toAbsolutePath());
  }

  private static String daemonSetYaml(String name, Path jar, String moduleName) {
    return """
        kind: DaemonSet
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        """
        .formatted(name, moduleName, jar.toAbsolutePath());
  }

  private static String statefulSetYaml(String name, Path jar, String moduleName) {
    return """
        kind: StatefulSet
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        replicas: 1
        """
        .formatted(name, moduleName, jar.toAbsolutePath());
  }
}
