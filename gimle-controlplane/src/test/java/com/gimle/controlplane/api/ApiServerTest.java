package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.ConsoleAddons;
import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.Json;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.StateStore;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/** Exercises {@link ApiServer} over a real loopback HTTP connection, not a mocked handler. */
// Real ApiServer + real java.net.http.HttpClient on a loopback ephemeral port: under heavy
// concurrent socket churn from other classes doing the same thing at once, this JDK HttpClient
// occasionally read a corrupted/cross-talked response (observed: "parsing HTTP/1.1 status line"
// on a fresh connection). Excludes this class from running concurrently with any other class
// that also spins up a real ApiServer and issues real HTTP requests against it.
@ResourceLock("gimle-controlplane-api-server-http")
// This class never calls System.setProperty itself, but ApiServer#start reads
// gimle.transport.protocol at construction time (ApiServer.java's TransportProtocol.fromConfig()
// calls): without this lock, a concurrently-running TLS test (e.g. ApiServerTlsTest) flipping that
// property mid-race could make @BeforeEach build this class's "plaintext" server as TLS instead,
// which is exactly what produced the "parsing HTTP/1.1 status line" corruption -- a plain
// HttpClient reading raw TLS handshake bytes off the wire.
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ApiServerTest {

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

  private HttpResponse<String> send(HttpRequest request) throws Exception {
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static String deploymentYaml(String name, int replicas) {
    return """
        kind: Deployment
        name: %s
        module:
          name: com.gimle.example.orders
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
        replicas: %d
        """
        .formatted(name, replicas);
  }

  @Test
  void put_then_get_a_deployment_round_trips() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml("orders-service", 3)))
                .build());
    assertEquals(200, put.statusCode());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .GET()
                .build());
    assertEquals(200, get.statusCode());
    Map<String, Object> status = Json.asObject(Json.parse(get.body()));
    Map<String, Object> spec = Json.asObject(status.get("spec"));
    assertEquals("orders-service", spec.get("name"));
    assertEquals(3L, spec.get("replicas"));
    assertEquals(3L, status.get("unplacedCount"));
  }

  @Test
  void a_local_artifact_path_put_carries_a_deprecation_warning_header() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml("orders-service", 3)))
                .build());

    assertEquals(200, put.statusCode());
    List<String> warnings = put.headers().allValues("X-Gimle-Warning");
    assertEquals(1, warnings.size());
    assertTrue(warnings.get(0).contains("deprecated"), warnings.get(0));
  }

  @Test
  void a_v1_manifest_declaring_artifact_path_is_rejected() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        "apiVersion: v1\n" + deploymentYaml("orders-service", 3)))
                .build());

    assertEquals(400, put.statusCode());
    assertTrue(put.body().contains("not accepted in apiVersion v1"), put.body());
    assertEquals(List.of(), put.headers().allValues("X-Gimle-Warning"));
  }

  /**
   * Regression coverage for a real bug found end-to-end: the deprecation warning must never ride a
   * response that isn't the manifest's own actual success -- a manifest can parse cleanly (warnings
   * non-empty) and still be rejected downstream by the per-kind handler itself (name mismatch here,
   * but the same reasoning covers an admission conflict or a wrong-kind manifest) for a reason
   * entirely unrelated to the deprecated field. Attaching the header before that handler has
   * decided the outcome -- the bug's actual mechanism -- would claim a deprecated field caused
   * something that, since nothing was applied, never happened at all.
   */
  @Test
  void a_deprecation_warning_never_rides_an_unrelated_rejection() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/name-does-not-match"))
                .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml("orders-service", 3)))
                .build());

    assertEquals(400, put.statusCode());
    assertTrue(put.body().contains("does not match URL path"), put.body());
    assertEquals(List.of(), put.headers().allValues("X-Gimle-Warning"));
  }

  @Test
  void an_unsupported_api_version_is_rejected() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        "apiVersion: v9\n" + deploymentYaml("orders-service", 3)))
                .build());

    assertEquals(400, put.statusCode());
    assertTrue(put.body().contains("unsupported apiVersion 'v9'"), put.body());
  }

  private static String deploymentYamlWithAutoscale(String name) {
    return """
        kind: Deployment
        name: %s
        module:
          name: com.gimle.example.orders
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
        replicas: 2
        autoscale:
          minReplicas: 1
          maxReplicas: 5
          targetCpuUtilizationPercent: 50
          targetRequestRatePerSecond: 20.0
          targetErrorRatePercent: 5.0
          targetQueueDepth: 10
          mode: weighted
          cpuWeight: 1.0
          requestRateWeight: 3.0
          errorRateWeight: 2.0
          queueDepthWeight: 1.5
          scaleUpCooldownSeconds: 30
          scaleDownCooldownSeconds: 900
        """
        .formatted(name);
  }

  // The console reads the deployment JSON this test asserts on to build its own Autoscale panel
  // -- before deploymentStatus() put this on the wire, the field was accepted by PUT
  // (DeploymentManifestParser) but silently dropped from every GET response.
  @Test
  void put_then_get_a_deployment_round_trips_its_autoscale_policy() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        deploymentYamlWithAutoscale("orders-service")))
                .build());
    assertEquals(200, put.statusCode());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .GET()
                .build());
    assertEquals(200, get.statusCode());
    Map<String, Object> status = Json.asObject(Json.parse(get.body()));
    Map<String, Object> spec = Json.asObject(status.get("spec"));
    Map<String, Object> autoscale = Json.asObject(spec.get("autoscale"));
    assertEquals(1L, autoscale.get("minReplicas"));
    assertEquals(5L, autoscale.get("maxReplicas"));
    assertEquals(50L, autoscale.get("targetCpuUtilizationPercent"));
    assertEquals(20.0, autoscale.get("targetRequestRatePerSecond"));
    assertEquals(5.0, autoscale.get("targetErrorRatePercent"));
    assertEquals(10L, autoscale.get("targetQueueDepth"));
    assertEquals("WEIGHTED", autoscale.get("combinationMode"));
    assertEquals(1.0, autoscale.get("cpuWeight"));
    assertEquals(3.0, autoscale.get("requestRateWeight"));
    assertEquals(2.0, autoscale.get("errorRateWeight"));
    assertEquals(1.5, autoscale.get("queueDepthWeight"));
    assertEquals(30L, autoscale.get("scaleUpCooldownSeconds"));
    assertEquals(900L, autoscale.get("scaleDownCooldownSeconds"));
    assertFalse(
        status.containsKey("lastScaleTime"), "the autoscaler has not moved this deployment yet");
  }

  // A deployment submitted with no autoscale: block (the common case, and every manifest written
  // before this field existed) must not gain a synthesized "autoscale" key -- ifPresent means
  // ifPresent, not "default it in."
  @Test
  void a_deployment_with_no_autoscale_block_has_no_autoscale_key_on_the_wire() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml("orders-service", 1)))
                .build());
    assertEquals(200, put.statusCode());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .GET()
                .build());
    Map<String, Object> spec = Json.asObject(Json.asObject(Json.parse(get.body())).get("spec"));
    assertFalse(spec.containsKey("autoscale"));
  }

  private static String deploymentYamlWithDisruption(String name) {
    return """
        kind: Deployment
        name: %s
        module:
          name: com.gimle.example.orders
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
        replicas: 2
        disruption:
          maxUnavailable: 2
          maxSurge: 1
        """
        .formatted(name);
  }

  // The console reads the deployment JSON this test asserts on to build its own disruption-budget
  // panel -- before deploymentStatus() put this on the wire, the field was accepted by PUT
  // (DeploymentManifestParser) but silently dropped from every GET response, the same gap
  // put_then_get_a_deployment_round_trips_its_autoscale_policy above once had for autoscale.
  @Test
  void put_then_get_a_deployment_round_trips_its_disruption_budget() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        deploymentYamlWithDisruption("orders-service")))
                .build());
    assertEquals(200, put.statusCode());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .GET()
                .build());
    assertEquals(200, get.statusCode());
    Map<String, Object> status = Json.asObject(Json.parse(get.body()));
    Map<String, Object> spec = Json.asObject(status.get("spec"));
    Map<String, Object> disruption = Json.asObject(spec.get("disruption"));
    assertEquals(2L, disruption.get("maxUnavailable"));
    assertEquals(1L, disruption.get("maxSurge"));
  }

  // Same "ifPresent means ifPresent" contract autoscale already established above.
  @Test
  void a_deployment_with_no_disruption_block_has_no_disruption_key_on_the_wire() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml("orders-service", 1)))
                .build());
    assertEquals(200, put.statusCode());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .GET()
                .build());
    Map<String, Object> spec = Json.asObject(Json.asObject(Json.parse(get.body())).get("spec"));
    assertFalse(spec.containsKey("disruption"));
  }

  @Test
  void put_with_a_manifest_name_mismatch_is_rejected() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/catalog-service"))
                .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml("orders-service", 1)))
                .build());

    assertEquals(400, put.statusCode());
  }

  @Test
  void put_with_malformed_yaml_is_rejected() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .PUT(HttpRequest.BodyPublishers.ofString("not: [valid"))
                .build());

    assertEquals(400, put.statusCode());
  }

  @Test
  void get_of_an_unknown_deployment_is_404() throws Exception {
    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/nope")).GET().build());

    assertEquals(404, get.statusCode());
  }

  @Test
  void delete_removes_a_deployment() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
            .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml("orders-service", 1)))
            .build());

    HttpResponse<String> delete =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .DELETE()
                .build());
    assertEquals(200, delete.statusCode());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .GET()
                .build());
    assertEquals(404, get.statusCode());
  }

  @Test
  void deployments_list_endpoint_returns_every_deployment() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
            .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml("orders-service", 2)))
            .build());
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/catalog-service"))
            .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml("catalog-service", 1)))
            .build());

    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/deployments")).GET().build());

    assertEquals(200, list.statusCode());
    List<Map<String, Object>> body = Json.asObjectList(Json.parse(list.body()));
    assertEquals(2, body.size());
  }

  @Test
  void deployments_list_endpoint_is_empty_with_none_submitted() throws Exception {
    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/deployments")).GET().build());

    assertEquals(200, list.statusCode());
    assertTrue(Json.asObjectList(Json.parse(list.body())).isEmpty());
  }

  private static String jobYaml(String name) {
    return """
        kind: Job
        name: %s
        module:
          name: com.gimle.example.cleanup
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
        backoffLimit: 3
        """
        .formatted(name);
  }

  @Test
  void put_then_get_a_job_round_trips() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/jobs/nightly-cleanup"))
                .PUT(HttpRequest.BodyPublishers.ofString(jobYaml("nightly-cleanup")))
                .build());
    assertEquals(200, put.statusCode());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/jobs/nightly-cleanup")).GET().build());
    assertEquals(200, get.statusCode());
    Map<String, Object> status = Json.asObject(Json.parse(get.body()));
    Map<String, Object> spec = Json.asObject(status.get("spec"));
    assertEquals("nightly-cleanup", spec.get("name"));
    assertEquals(3L, spec.get("backoffLimit"));
    // Absent until a run has actually been placed by JobReconciler -- nothing reconciles in this
    // handler-only test, so "RUNNING" (the documented default phase) with no currentRun is exactly
    // what a job looks like the instant after admission.
    assertEquals("RUNNING", status.get("phase"));
    assertFalse(status.containsKey("currentRun"));
  }

  @Test
  void put_a_job_with_a_deployment_kind_manifest_is_rejected() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/jobs/nightly-cleanup"))
                .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml("nightly-cleanup", 1)))
                .build());

    assertEquals(400, put.statusCode());
  }

  @Test
  void put_a_job_with_a_manifest_name_mismatch_is_rejected() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/jobs/other-name"))
                .PUT(HttpRequest.BodyPublishers.ofString(jobYaml("nightly-cleanup")))
                .build());

    assertEquals(400, put.statusCode());
  }

  @Test
  void put_a_job_with_malformed_yaml_is_rejected() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/jobs/nightly-cleanup"))
                .PUT(HttpRequest.BodyPublishers.ofString("not: [valid"))
                .build());

    assertEquals(400, put.statusCode());
  }

  @Test
  void get_of_an_unknown_job_is_404() throws Exception {
    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/jobs/nope")).GET().build());

    assertEquals(404, get.statusCode());
  }

  @Test
  void delete_removes_a_job() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/jobs/nightly-cleanup"))
            .PUT(HttpRequest.BodyPublishers.ofString(jobYaml("nightly-cleanup")))
            .build());

    HttpResponse<String> delete =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/jobs/nightly-cleanup")).DELETE().build());
    assertEquals(200, delete.statusCode());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/jobs/nightly-cleanup")).GET().build());
    assertEquals(404, get.statusCode());
  }

  @Test
  void jobs_list_endpoint_returns_every_job() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/jobs/nightly-cleanup"))
            .PUT(HttpRequest.BodyPublishers.ofString(jobYaml("nightly-cleanup")))
            .build());
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/jobs/weekly-report"))
            .PUT(HttpRequest.BodyPublishers.ofString(jobYaml("weekly-report")))
            .build());

    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/jobs")).GET().build());

    assertEquals(200, list.statusCode());
    List<Map<String, Object>> body = Json.asObjectList(Json.parse(list.body()));
    assertEquals(2, body.size());
  }

  @Test
  void jobs_list_endpoint_is_empty_with_none_submitted() throws Exception {
    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/jobs")).GET().build());

    assertEquals(200, list.statusCode());
    assertTrue(Json.asObjectList(Json.parse(list.body())).isEmpty());
  }

  @Test
  void jobs_endpoint_method_not_allowed_for_post() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/jobs/nightly-cleanup"))
                .POST(HttpRequest.BodyPublishers.ofString("irrelevant"))
                .build());

    assertEquals(405, response.statusCode());
  }

  private static String cronJobYaml(String name) {
    return """
        kind: CronJob
        name: %s
        schedule: "0 2 * * *"
        jobTemplate:
          module:
            name: com.gimle.example.cleanup
            version: 1.0.0
          artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
          backoffLimit: 3
        """
        .formatted(name);
  }

  @Test
  void put_then_get_a_cronjob_round_trips() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/nightly-cleanup"))
                .PUT(HttpRequest.BodyPublishers.ofString(cronJobYaml("nightly-cleanup")))
                .build());
    assertEquals(200, put.statusCode());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/nightly-cleanup"))
                .GET()
                .build());
    assertEquals(200, get.statusCode());
    Map<String, Object> status = Json.asObject(Json.parse(get.body()));
    Map<String, Object> spec = Json.asObject(status.get("spec"));
    assertEquals("nightly-cleanup", spec.get("name"));
    assertEquals("0 2 * * *", spec.get("schedule"));
    assertEquals("ALLOW", spec.get("concurrencyPolicy"));
    assertEquals(Boolean.FALSE, spec.get("suspend"));
    assertFalse(status.containsKey("lastScheduleTime"), "never fired yet");
  }

  @Test
  void put_then_get_a_suspended_cronjob_round_trips_its_suspend_flag() throws Exception {
    String suspended = cronJobYaml("nightly-cleanup") + "suspend: true\n";
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/nightly-cleanup"))
                .PUT(HttpRequest.BodyPublishers.ofString(suspended))
                .build());
    assertEquals(200, put.statusCode());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/nightly-cleanup"))
                .GET()
                .build());
    assertEquals(200, get.statusCode());
    Map<String, Object> spec = Json.asObject(Json.asObject(Json.parse(get.body())).get("spec"));
    assertEquals(Boolean.TRUE, spec.get("suspend"));
  }

  /**
   * A CronJobSpec has nothing of its own to charge against quota/limit-range (see {@code
   * WorkloadResourceProfile}'s own javadoc), so the one real effect of running it through {@code
   * admitWorkload} is {@code TenantQuotaPlugin}'s "unknown tenantId" check -- CronJob shouldn't be
   * the one workload kind that lets a nonexistent tenant through where Deployment/Job/DaemonSet/
   * StatefulSet all reject it.
   */
  @Test
  void put_a_cronjob_for_an_unknown_tenant_is_rejected() throws Exception {
    String yaml =
        """
        kind: CronJob
        name: nightly-cleanup
        schedule: "0 2 * * *"
        jobTemplate:
          module:
            name: com.gimle.example.cleanup
            version: 1.0.0
          artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
          backoffLimit: 3
        tenantId: does-not-exist
        """;

    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/nightly-cleanup"))
                .PUT(HttpRequest.BodyPublishers.ofString(yaml))
                .build());

    assertEquals(409, put.statusCode());
    assertTrue(put.body().contains("unknown tenantId"), put.body());
  }

  @Test
  void put_a_cronjob_with_a_deployment_kind_manifest_is_rejected() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/nightly-cleanup"))
                .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml("nightly-cleanup", 1)))
                .build());

    assertEquals(400, put.statusCode());
  }

  @Test
  void put_a_cronjob_with_a_manifest_name_mismatch_is_rejected() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/other-name"))
                .PUT(HttpRequest.BodyPublishers.ofString(cronJobYaml("nightly-cleanup")))
                .build());

    assertEquals(400, put.statusCode());
  }

  @Test
  void put_a_cronjob_with_malformed_yaml_is_rejected() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/nightly-cleanup"))
                .PUT(HttpRequest.BodyPublishers.ofString("not: [valid"))
                .build());

    assertEquals(400, put.statusCode());
  }

  @Test
  void get_of_an_unknown_cronjob_is_404() throws Exception {
    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/nope")).GET().build());

    assertEquals(404, get.statusCode());
  }

  @Test
  void delete_removes_a_cronjob() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/nightly-cleanup"))
            .PUT(HttpRequest.BodyPublishers.ofString(cronJobYaml("nightly-cleanup")))
            .build());

    HttpResponse<String> delete =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/nightly-cleanup"))
                .DELETE()
                .build());
    assertEquals(200, delete.statusCode());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/nightly-cleanup"))
                .GET()
                .build());
    assertEquals(404, get.statusCode());
  }

  @Test
  void cronjobs_list_endpoint_returns_every_cronjob() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/nightly-cleanup"))
            .PUT(HttpRequest.BodyPublishers.ofString(cronJobYaml("nightly-cleanup")))
            .build());
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/weekly-report"))
            .PUT(HttpRequest.BodyPublishers.ofString(cronJobYaml("weekly-report")))
            .build());

    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs")).GET().build());

    assertEquals(200, list.statusCode());
    List<Map<String, Object>> body = Json.asObjectList(Json.parse(list.body()));
    assertEquals(2, body.size());
  }

  @Test
  void cronjobs_list_endpoint_is_empty_with_none_submitted() throws Exception {
    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs")).GET().build());

    assertEquals(200, list.statusCode());
    assertTrue(Json.asObjectList(Json.parse(list.body())).isEmpty());
  }

  @Test
  void cronjobs_endpoint_method_not_allowed_for_post() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/nightly-cleanup"))
                .POST(HttpRequest.BodyPublishers.ofString("irrelevant"))
                .build());

    assertEquals(405, response.statusCode());
  }

  @Test
  void trigger_fires_immediately_and_the_generated_job_appears_on_the_jobs_list() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/nightly-cleanup"))
            .PUT(HttpRequest.BodyPublishers.ofString(cronJobYaml("nightly-cleanup")))
            .build());

    HttpResponse<String> trigger =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/nightly-cleanup/trigger"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());

    assertEquals(200, trigger.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(trigger.body()));
    String jobName = (String) body.get("jobName");
    assertTrue(jobName.startsWith("nightly-cleanup-"));

    HttpResponse<String> job =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/jobs/" + jobName)).GET().build());
    assertEquals(200, job.statusCode());
  }

  @Test
  void trigger_on_an_unknown_cronjob_is_404() throws Exception {
    HttpResponse<String> trigger =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/nope/trigger"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());

    assertEquals(404, trigger.statusCode());
  }

  @Test
  void trigger_is_rejected_by_get() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/nightly-cleanup"))
            .PUT(HttpRequest.BodyPublishers.ofString(cronJobYaml("nightly-cleanup")))
            .build());

    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/nightly-cleanup/trigger"))
                .GET()
                .build());

    assertEquals(405, response.statusCode());
  }

  @Test
  void an_unknown_cronjob_subresource_is_404() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/nightly-cleanup/bogus"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());

    assertEquals(404, response.statusCode());
  }

  private static String daemonSetYaml(String name) {
    return """
        kind: DaemonSet
        name: %s
        module:
          name: com.gimle.example.node-exporter
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/node-exporter-1.0.0.jar
        placement:
          requiredLabels: [gpu]
        """
        .formatted(name);
  }

  @Test
  void put_then_get_a_daemonset_round_trips() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets/node-exporter"))
                .PUT(HttpRequest.BodyPublishers.ofString(daemonSetYaml("node-exporter")))
                .build());
    assertEquals(200, put.statusCode());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets/node-exporter"))
                .GET()
                .build());
    assertEquals(200, get.statusCode());
    Map<String, Object> status = Json.asObject(Json.parse(get.body()));
    Map<String, Object> spec = Json.asObject(status.get("spec"));
    assertEquals("node-exporter", spec.get("name"));
    Map<String, Object> placement = Json.asObject(spec.get("placement"));
    assertEquals(List.of("gpu"), placement.get("requiredLabels"));
    assertTrue(Json.asObjectList(status.get("instances")).isEmpty(), "nothing reconciled yet");
    assertFalse(
        status.containsKey("desired"),
        "desired is absent until DaemonSetReconciler's first tick, the same shape "
            + "getEffectiveReplicas already uses");
    assertFalse(status.containsKey("unplacedCount"));
  }

  /**
   * {@code desired} and {@code unplacedCount} are read straight off {@code
   * StateStore#daemonSetDesiredCounts} -- this test sets it directly (bypassing a real reconciler
   * tick) the same way it bypasses the scheduler for every other daemonset status field here, and
   * asserts the API surfaces it alongside the placed (instances) count.
   */
  @Test
  void get_a_daemonset_surfaces_the_reconciler_published_desired_count() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets/node-exporter"))
            .PUT(HttpRequest.BodyPublishers.ofString(daemonSetYaml("node-exporter")))
            .build());
    // An untenanted manifest resolves to Tenant.DEFAULT_TENANT_ID at parse time, never
    // Optional.empty() -- see ApiServer's own note on why no workload ever lands in the
    // untenanted namespace.
    store.putDaemonSetDesiredCount(Optional.of(Tenant.DEFAULT_TENANT_ID), "node-exporter", 3);

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets/node-exporter"))
                .GET()
                .build());

    assertEquals(200, get.statusCode());
    Map<String, Object> status = Json.asObject(Json.parse(get.body()));
    assertEquals(3, ((Number) status.get("desired")).intValue());
    // No instances placed yet, so the whole desired count is currently unplaced.
    assertEquals(3, ((Number) status.get("unplacedCount")).intValue());
  }

  @Test
  void daemonsets_list_endpoint_also_surfaces_the_desired_count() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets/node-exporter"))
            .PUT(HttpRequest.BodyPublishers.ofString(daemonSetYaml("node-exporter")))
            .build());
    store.putDaemonSetDesiredCount(Optional.of(Tenant.DEFAULT_TENANT_ID), "node-exporter", 2);

    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets")).GET().build());

    assertEquals(200, list.statusCode());
    List<Map<String, Object>> statuses = Json.asObjectList(Json.parse(list.body()));
    assertEquals(1, statuses.size());
    assertEquals(2, ((Number) statuses.get(0).get("desired")).intValue());
    assertEquals(2, ((Number) statuses.get(0).get("unplacedCount")).intValue());
  }

  @Test
  void put_a_daemonset_with_a_deployment_kind_manifest_is_rejected() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets/node-exporter"))
                .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml("node-exporter", 1)))
                .build());

    assertEquals(400, put.statusCode());
  }

  @Test
  void put_a_daemonset_with_anti_affinity_set_is_rejected() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets/node-exporter"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        kind: DaemonSet
                        name: node-exporter
                        module:
                          name: com.gimle.example.node-exporter
                          version: 1.0.0
                        artifactPath: /var/gimle/artifacts/node-exporter-1.0.0.jar
                        placement:
                          antiAffinity: false
                        """))
                .build());

    assertEquals(400, put.statusCode());
  }

  @Test
  void put_a_daemonset_with_a_manifest_name_mismatch_is_rejected() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets/other-name"))
                .PUT(HttpRequest.BodyPublishers.ofString(daemonSetYaml("node-exporter")))
                .build());

    assertEquals(400, put.statusCode());
  }

  @Test
  void put_a_daemonset_with_malformed_yaml_is_rejected() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets/node-exporter"))
                .PUT(HttpRequest.BodyPublishers.ofString("not: [valid"))
                .build());

    assertEquals(400, put.statusCode());
  }

  @Test
  void get_of_an_unknown_daemonset_is_404() throws Exception {
    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets/nope")).GET().build());

    assertEquals(404, get.statusCode());
  }

  @Test
  void delete_removes_a_daemonset() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets/node-exporter"))
            .PUT(HttpRequest.BodyPublishers.ofString(daemonSetYaml("node-exporter")))
            .build());

    HttpResponse<String> delete =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets/node-exporter"))
                .DELETE()
                .build());
    assertEquals(200, delete.statusCode());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets/node-exporter"))
                .GET()
                .build());
    assertEquals(404, get.statusCode());
  }

  @Test
  void daemonsets_list_endpoint_returns_every_daemonset() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets/node-exporter"))
            .PUT(HttpRequest.BodyPublishers.ofString(daemonSetYaml("node-exporter")))
            .build());
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets/log-shipper"))
            .PUT(HttpRequest.BodyPublishers.ofString(daemonSetYaml("log-shipper")))
            .build());

    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets")).GET().build());

    assertEquals(200, list.statusCode());
    List<Map<String, Object>> body = Json.asObjectList(Json.parse(list.body()));
    assertEquals(2, body.size());
  }

  @Test
  void daemonsets_list_endpoint_is_empty_with_none_submitted() throws Exception {
    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets")).GET().build());

    assertEquals(200, list.statusCode());
    assertTrue(Json.asObjectList(Json.parse(list.body())).isEmpty());
  }

  @Test
  void daemonsets_endpoint_method_not_allowed_for_post() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets/node-exporter"))
                .POST(HttpRequest.BodyPublishers.ofString("irrelevant"))
                .build());

    assertEquals(405, response.statusCode());
  }

  private static String statefulSetYaml(String name) {
    return """
        kind: StatefulSet
        name: %s
        module:
          name: com.gimle.example.orders
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
        replicas: 3
        """
        .formatted(name);
  }

  @Test
  void put_then_get_a_statefulset_round_trips() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/statefulsets/orders"))
                .PUT(HttpRequest.BodyPublishers.ofString(statefulSetYaml("orders")))
                .build());
    assertEquals(200, put.statusCode());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/statefulsets/orders")).GET().build());
    assertEquals(200, get.statusCode());
    Map<String, Object> status = Json.asObject(Json.parse(get.body()));
    Map<String, Object> spec = Json.asObject(status.get("spec"));
    assertEquals("orders", spec.get("name"));
    assertEquals(3L, spec.get("replicas"));
    assertTrue(Json.asObjectList(status.get("instances")).isEmpty(), "nothing reconciled yet");
    assertEquals(3L, status.get("unplacedCount"));
  }

  @Test
  void put_a_statefulset_with_a_deployment_kind_manifest_is_rejected() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/statefulsets/orders"))
                .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml("orders", 1)))
                .build());

    assertEquals(400, put.statusCode());
  }

  @Test
  void put_a_statefulset_with_a_manifest_name_mismatch_is_rejected() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/statefulsets/other-name"))
                .PUT(HttpRequest.BodyPublishers.ofString(statefulSetYaml("orders")))
                .build());

    assertEquals(400, put.statusCode());
  }

  @Test
  void put_a_statefulset_with_malformed_yaml_is_rejected() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/statefulsets/orders"))
                .PUT(HttpRequest.BodyPublishers.ofString("not: [valid"))
                .build());

    assertEquals(400, put.statusCode());
  }

  @Test
  void get_of_an_unknown_statefulset_is_404() throws Exception {
    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/statefulsets/nope")).GET().build());

    assertEquals(404, get.statusCode());
  }

  @Test
  void delete_removes_a_statefulset() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/statefulsets/orders"))
            .PUT(HttpRequest.BodyPublishers.ofString(statefulSetYaml("orders")))
            .build());

    HttpResponse<String> delete =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/statefulsets/orders")).DELETE().build());
    assertEquals(200, delete.statusCode());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/statefulsets/orders")).GET().build());
    assertEquals(404, get.statusCode());
  }

  @Test
  void statefulsets_list_endpoint_returns_every_statefulset() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/statefulsets/orders"))
            .PUT(HttpRequest.BodyPublishers.ofString(statefulSetYaml("orders")))
            .build());
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/statefulsets/inventory"))
            .PUT(HttpRequest.BodyPublishers.ofString(statefulSetYaml("inventory")))
            .build());

    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/statefulsets")).GET().build());

    assertEquals(200, list.statusCode());
    List<Map<String, Object>> body = Json.asObjectList(Json.parse(list.body()));
    assertEquals(2, body.size());
  }

  @Test
  void statefulsets_list_endpoint_is_empty_with_none_submitted() throws Exception {
    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/statefulsets")).GET().build());

    assertEquals(200, list.statusCode());
    assertTrue(Json.asObjectList(Json.parse(list.body())).isEmpty());
  }

  @Test
  void statefulsets_endpoint_method_not_allowed_for_post() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/statefulsets/orders"))
                .POST(HttpRequest.BodyPublishers.ofString("irrelevant"))
                .build());

    assertEquals(405, response.statusCode());
  }

  @Test
  void nodes_list_endpoint_returns_registered_nodes_with_their_last_heartbeat() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/register"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"capabilities\":{\"supportedTiers\":[\"TIER_1\"]}}"))
            .build());
    String heartbeatBody =
        """
        {"capacity":{"totalMemoryBytes":1000,"assignedMemoryBytes":0,"totalCpuMillicores":1000,\
        "assignedCpuMillicores":0},"instances":[]}
        """;
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/heartbeat"))
            .POST(HttpRequest.BodyPublishers.ofString(heartbeatBody))
            .build());

    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/nodes")).GET().build());

    assertEquals(200, list.statusCode());
    List<Map<String, Object>> body = Json.asObjectList(Json.parse(list.body()));
    assertEquals(1, body.size());
    assertEquals("node-a", body.get(0).get("nodeId"));
    assertTrue(body.get(0).containsKey("lastHeartbeatAt"));
    Map<String, Object> capacity = Json.asObject(body.get(0).get("capacity"));
    assertEquals(1000L, capacity.get("totalMemoryBytes"));
    assertEquals(0L, capacity.get("assignedMemoryBytes"));
    assertEquals(1000L, capacity.get("totalCpuMillicores"));
    assertEquals(0L, capacity.get("assignedCpuMillicores"));
  }

  /**
   * A node's labels were settable only through its own launch configuration, so a cluster whose
   * agent an operator cannot relaunch could never satisfy a manifest requiring a label -- including
   * the gateway's own bundled DaemonSet on the project's single-node reference cluster.
   */
  @Test
  void an_operator_can_label_a_running_node_and_the_label_counts_for_placement() throws Exception {
    registerNodeA();

    HttpResponse<String> labelled =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/labels"))
                .PUT(HttpRequest.BodyPublishers.ofString("{\"labels\":[\"edge\"]}"))
                .build());

    assertEquals(200, labelled.statusCode());
    Map<String, Object> capabilities = capabilitiesOfNodeA();
    assertEquals(List.of("edge"), capabilities.get("labels"));
    assertEquals(List.of("edge"), capabilities.get("operatorLabels"));
  }

  @Test
  void a_re_registering_node_keeps_the_labels_an_operator_applied_to_it() throws Exception {
    registerNodeA();
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/labels"))
            .PUT(HttpRequest.BodyPublishers.ofString("{\"labels\":[\"edge\"]}"))
            .build());

    // The agent restarts and re-reports only what it knows about itself.
    registerNodeA();

    Map<String, Object> capabilities = capabilitiesOfNodeA();
    assertEquals(List.of("edge"), capabilities.get("operatorLabels"));
    assertEquals(List.of("edge"), capabilities.get("labels"));
  }

  @Test
  void labelling_a_node_that_was_never_registered_is_a_404() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/ghost/labels"))
                .PUT(HttpRequest.BodyPublishers.ofString("{\"labels\":[\"edge\"]}"))
                .build());

    assertEquals(404, response.statusCode());
  }

  private void registerNodeA() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/register"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"capabilities\":{\"supportedTiers\":[\"TIER_1\"]}}"))
            .build());
  }

  private Map<String, Object> capabilitiesOfNodeA() throws Exception {
    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/nodes")).GET().build());
    return Json.asObject(Json.asObjectList(Json.parse(list.body())).get(0).get("capabilities"));
  }

  @Test
  void nodes_list_endpoint_omits_last_heartbeat_and_capacity_for_a_node_that_never_sent_one()
      throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/register"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"capabilities\":{\"supportedTiers\":[\"TIER_1\"]}}"))
            .build());

    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/nodes")).GET().build());

    assertEquals(200, list.statusCode());
    List<Map<String, Object>> body = Json.asObjectList(Json.parse(list.body()));
    assertEquals(1, body.size());
    assertFalse(body.get(0).containsKey("lastHeartbeatAt"));
    assertFalse(body.get(0).containsKey("capacity"));
  }

  @Test
  void cordon_endpoint_sets_the_cordon_flag_and_is_reflected_in_the_nodes_list() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/register"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"capabilities\":{\"supportedTiers\":[\"TIER_1\"]}}"))
            .build());

    HttpResponse<String> cordon =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/cordon"))
                .POST(HttpRequest.BodyPublishers.ofString(""))
                .build());

    assertEquals(200, cordon.statusCode());
    assertTrue(store.isNodeCordoned("node-a"));

    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/nodes")).GET().build());
    List<Map<String, Object>> body = Json.asObjectList(Json.parse(list.body()));
    assertEquals(Boolean.TRUE, body.get(0).get("cordoned"));
  }

  @Test
  void uncordon_endpoint_clears_the_cordon_flag() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/register"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"capabilities\":{\"supportedTiers\":[\"TIER_1\"]}}"))
            .build());
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/cordon"))
            .POST(HttpRequest.BodyPublishers.ofString(""))
            .build());

    HttpResponse<String> uncordon =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/uncordon"))
                .POST(HttpRequest.BodyPublishers.ofString(""))
                .build());

    assertEquals(200, uncordon.statusCode());
    assertFalse(store.isNodeCordoned("node-a"));
  }

  @Test
  void taint_endpoint_reserves_the_node_for_a_tenant_and_is_reflected_in_the_nodes_list()
      throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/register"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"capabilities\":{\"supportedTiers\":[\"TIER_1\"]}}"))
            .build());

    HttpResponse<String> taint =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/taint"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"tenantId\":\"tenant-a\"}"))
                .build());

    assertEquals(200, taint.statusCode());
    assertEquals(Set.of("tenant-a"), store.getNodeTaints("node-a"));

    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/nodes")).GET().build());
    List<Map<String, Object>> body = Json.asObjectList(Json.parse(list.body()));
    assertEquals(List.of("tenant-a"), body.get(0).get("taints"));
  }

  @Test
  void untaint_endpoint_clears_the_reservation_for_that_tenant() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/register"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"capabilities\":{\"supportedTiers\":[\"TIER_1\"]}}"))
            .build());
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/taint"))
            .POST(HttpRequest.BodyPublishers.ofString("{\"tenantId\":\"tenant-a\"}"))
            .build());

    HttpResponse<String> untaint =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/untaint"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"tenantId\":\"tenant-a\"}"))
                .build());

    assertEquals(200, untaint.statusCode());
    assertEquals(Set.of(), store.getNodeTaints("node-a"));
  }

  @Test
  void taint_endpoint_rejects_a_request_with_no_tenant_id() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/register"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"capabilities\":{\"supportedTiers\":[\"TIER_1\"]}}"))
            .build());

    HttpResponse<String> taint =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/taint"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build());

    assertEquals(400, taint.statusCode());
  }

  @Test
  void register_and_heartbeat_are_reflected_in_the_store() throws Exception {
    HttpResponse<String> register =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/register"))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"capabilities\":{\"supportedTiers\":[\"TIER_1\",\"TIER_2\"]}}"))
                .build());
    assertEquals(200, register.statusCode());
    assertEquals(
        new NodeRegistration(
            "node-a", new NodeCapabilities(Set.of(IsolationTier.TIER_1, IsolationTier.TIER_2))),
        store.getNodeRegistration("node-a").orElseThrow());

    String heartbeatBody =
        """
        {"capacity":{"totalMemoryBytes":1000,"assignedMemoryBytes":100,"totalCpuMillicores":4000,"assignedCpuMillicores":500},
         "instances":[{"deploymentName":"orders-service","instanceIndex":0,\
        "moduleId":{"name":"com.gimle.example.orders","version":"1.0.0"},\
        "lifecycleState":"ACTIVE","alive":true,"ready":true}]}
        """;
    HttpResponse<String> heartbeat =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/heartbeat"))
                .POST(HttpRequest.BodyPublishers.ofString(heartbeatBody))
                .build());
    assertEquals(200, heartbeat.statusCode());

    var observed = store.getNodeHeartbeat("node-a").orElseThrow();
    assertEquals(1000L, observed.heartbeat().capacity().totalMemoryBytes());
    assertEquals(1, observed.heartbeat().instances().size());
  }

  @Test
  void a_heartbeats_declared_tier_and_resource_limit_reach_the_deployments_read_surface()
      throws Exception {
    // The whole point of relaying these: a reader of GET /deployments gets the ceiling alongside
    // the usage, so 142Mi can be judged rather than merely reported. Exercises both directions of
    // the JSON hop -- observationFromJson on the heartbeat in, observationToJson on the read out.
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
            .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml("orders-service", 1)))
            .build());
    store.putAssignment(
        new InstanceAssignment(
            "orders-service",
            0,
            "node-a",
            InstanceAssignment.UNSPECIFIED_MODULE,
            "",
            OptionalInt.empty(),
            Optional.of(Tenant.DEFAULT_TENANT_ID)));

    String heartbeatBody =
        """
        {"capacity":{"totalMemoryBytes":1000,"assignedMemoryBytes":100,"totalCpuMillicores":4000,"assignedCpuMillicores":500},
         "instances":[{"deploymentName":"orders-service","instanceIndex":0,\
        "moduleId":{"name":"com.gimle.example.orders","version":"1.0.0"},\
        "lifecycleState":"ACTIVE","alive":true,"ready":true,"tenantId":"%s",\
        "memoryBytesUsed":148897792,\
        "isolationTier":"TIER_2","resourceLimit":{"memory":"256Mi","cpu":"1000m"}}]}
        """
            .formatted(Tenant.DEFAULT_TENANT_ID);
    assertEquals(
        200,
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/heartbeat"))
                .POST(HttpRequest.BodyPublishers.ofString(heartbeatBody))
                .build())
            .statusCode());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .GET()
                .build());
    assertEquals(200, get.statusCode());
    Map<String, Object> status = Json.asObject(Json.parse(get.body()));
    List<Map<String, Object>> instances = Json.asObjectList(status.get("instances"));
    assertEquals(1, instances.size());
    Map<String, Object> observation = Json.asObject(instances.get(0).get("observation"));
    assertEquals("TIER_2", observation.get("isolationTier"));
    assertEquals(148897792L, observation.get("memoryBytesUsed"));
    Map<String, Object> limit = Json.asObject(observation.get("resourceLimit"));
    assertEquals("256Mi", limit.get("memory"));
    assertEquals("1000m", limit.get("cpu"));
  }

  @Test
  void an_observation_with_no_declared_tier_or_limit_omits_both_rather_than_inventing_them()
      throws Exception {
    // A vessel instance has no module descriptor behind it, so its heartbeat carries neither.
    // The read surface must say nothing rather than render a ceiling nothing declared.
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
            .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml("orders-service", 1)))
            .build());
    store.putAssignment(
        new InstanceAssignment(
            "orders-service",
            0,
            "node-a",
            InstanceAssignment.UNSPECIFIED_MODULE,
            "",
            OptionalInt.empty(),
            Optional.of(Tenant.DEFAULT_TENANT_ID)));

    String heartbeatBody =
        """
        {"capacity":{"totalMemoryBytes":1000,"assignedMemoryBytes":100,"totalCpuMillicores":4000,"assignedCpuMillicores":500},
         "instances":[{"deploymentName":"orders-service","instanceIndex":0,\
        "moduleId":{"name":"com.gimle.example.orders","version":"1.0.0"},\
        "lifecycleState":"ACTIVE","alive":true,"ready":true,"tenantId":"%s"}]}
        """
            .formatted(Tenant.DEFAULT_TENANT_ID);
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/heartbeat"))
            .POST(HttpRequest.BodyPublishers.ofString(heartbeatBody))
            .build());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .GET()
                .build());
    Map<String, Object> observation =
        Json.asObject(
            Json.asObjectList(Json.asObject(Json.parse(get.body())).get("instances"))
                .get(0)
                .get("observation"));
    assertFalse(observation.containsKey("isolationTier"));
    assertFalse(observation.containsKey("resourceLimit"));
  }

  @Test
  void assignments_endpoint_joins_assignments_with_their_deployments_artifact_and_module_id()
      throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
            .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml("orders-service", 1)))
            .build());
    store.putAssignment(
        new InstanceAssignment(
            "orders-service",
            0,
            "node-a",
            InstanceAssignment.UNSPECIFIED_MODULE,
            "",
            OptionalInt.empty(),
            Optional.of(Tenant.DEFAULT_TENANT_ID)));

    HttpResponse<String> assignments =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/assignments"))
                .GET()
                .build());

    assertEquals(200, assignments.statusCode());
    List<Map<String, Object>> body = Json.asObjectList(Json.parse(assignments.body()));
    assertEquals(1, body.size());
    Map<String, Object> instance = body.get(0);
    assertEquals("orders-service", instance.get("deploymentName"));
    assertEquals(0L, instance.get("instanceIndex"));
    assertEquals("/var/gimle/artifacts/orders-1.0.0.jar", instance.get("artifactPath"));
    Map<String, Object> moduleId = Json.asObject(instance.get("moduleId"));
    assertEquals("com.gimle.example.orders", moduleId.get("name"));
    assertEquals("1.0.0", moduleId.get("version"));
  }

  @Test
  void assignments_endpoint_keeps_the_port_a_vessel_probe_names() throws Exception {
    // Two declared ports make naming one mandatory, so a response that drops the name yields a
    // spec the agent cannot reconstruct at all -- the manifest is valid, the wire shape was not.
    String vesselYaml =
        """
        kind: Deployment
        name: two-port-vessel
        module:
          name: com.gimle.example.orders
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
        replicas: 1
        vessel:
          env:
            ADMIN_PORT: {port: dynamic}
            HTTP_PORT: {port: dynamic}
          probes:
            liveness: {tcp: true, port: HTTP_PORT}
            readiness: {http: /healthz, port: ADMIN_PORT}
          resources:
            request: {memory: 64Mi, cpu: 100m}
            limit: {memory: 128Mi, cpu: 200m}
        """;
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/two-port-vessel"))
            .PUT(HttpRequest.BodyPublishers.ofString(vesselYaml))
            .build());
    store.putAssignment(
        new InstanceAssignment(
            "two-port-vessel",
            0,
            "node-a",
            InstanceAssignment.UNSPECIFIED_MODULE,
            "",
            OptionalInt.empty(),
            Optional.of(Tenant.DEFAULT_TENANT_ID)));

    HttpResponse<String> assignments =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/assignments"))
                .GET()
                .build());

    assertEquals(200, assignments.statusCode());
    Map<String, Object> probes =
        Json.asObject(
            Json.asObject(Json.asObjectList(Json.parse(assignments.body())).get(0).get("vessel"))
                .get("probes"));
    assertEquals("HTTP_PORT", Json.asObject(probes.get("liveness")).get("port"));
    assertEquals("ADMIN_PORT", Json.asObject(probes.get("readiness")).get("port"));
  }

  @Test
  void assignments_endpoint_is_empty_for_a_node_with_none() throws Exception {
    HttpResponse<String> assignments =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/assignments"))
                .GET()
                .build());

    assertEquals(200, assignments.statusCode());
    assertTrue(((List<?>) Json.parse(assignments.body())).isEmpty());
  }

  @Test
  void metrics_endpoint_rolls_up_average_request_and_error_rate_per_deployment() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
            .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml("orders-service", 2)))
            .build());
    store.putAssignment(
        new InstanceAssignment(
            "orders-service",
            0,
            "node-a",
            InstanceAssignment.UNSPECIFIED_MODULE,
            "",
            OptionalInt.empty(),
            Optional.of(Tenant.DEFAULT_TENANT_ID)));
    store.putAssignment(
        new InstanceAssignment(
            "orders-service",
            1,
            "node-a",
            InstanceAssignment.UNSPECIFIED_MODULE,
            "",
            OptionalInt.empty(),
            Optional.of(Tenant.DEFAULT_TENANT_ID)));
    ModuleId moduleId = new ModuleId("com.gimle.example.orders", Version.parse("1.0.0"));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            "node-a",
            new ResourceUsageSnapshot(1000L, 0, 1000, 0),
            List.of(
                InstanceObservation.builder("orders-service", 0, moduleId, "ACTIVE", true, true)
                    .load(10.0, 2.0, 0, 0L, 0L)
                    .tenantId(Optional.of(Tenant.DEFAULT_TENANT_ID))
                    .build(),
                InstanceObservation.builder("orders-service", 1, moduleId, "ACTIVE", true, true)
                    .load(20.0, 4.0, 0, 0L, 0L)
                    .tenantId(Optional.of(Tenant.DEFAULT_TENANT_ID))
                    .build())));

    HttpResponse<String> metrics =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/metrics")).GET().build());

    assertEquals(200, metrics.statusCode());
    List<Map<String, Object>> rows = Json.asObjectList(Json.parse(metrics.body()));
    assertEquals(1, rows.size());
    Map<String, Object> row = rows.get(0);
    assertEquals("orders-service", row.get("deploymentName"));
    assertEquals(2L, row.get("instanceCount"));
    assertEquals(15.0, row.get("avgRequestRatePerSecond"));
    assertEquals(3.0, row.get("avgErrorRatePerSecond"));
  }

  @Test
  void metrics_endpoint_reports_zero_instances_for_a_deployment_with_no_observations()
      throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
            .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml("orders-service", 1)))
            .build());

    HttpResponse<String> metrics =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/metrics")).GET().build());

    assertEquals(200, metrics.statusCode());
    List<Map<String, Object>> rows = Json.asObjectList(Json.parse(metrics.body()));
    assertEquals(1, rows.size());
    assertEquals(0L, rows.get(0).get("instanceCount"));
    assertEquals(0.0, rows.get(0).get("avgRequestRatePerSecond"));
    assertEquals(0.0, rows.get(0).get("avgErrorRatePerSecond"));
  }

  @Test
  void posting_an_instance_event_makes_it_readable_through_get_events() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/register"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"capabilities\":{\"supportedTiers\":[\"TIER_1\"]}}"))
            .build());
    String eventBody =
        """
        {"id":"evt-1","deploymentName":"orders-service","instanceIndex":0,\
        "kind":"ACTIVE","message":"module active","occurredAtEpochMilli":1000}
        """;

    HttpResponse<String> posted =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/events"))
                .POST(HttpRequest.BodyPublishers.ofString(eventBody))
                .build());
    assertEquals(200, posted.statusCode());

    HttpResponse<String> events =
        send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/events?deployment=orders-service&instance=0"))
                .GET()
                .build());
    assertEquals(200, events.statusCode());
    List<Map<String, Object>> body = Json.asObjectList(Json.parse(events.body()));
    assertEquals(1, body.size());
    assertEquals("evt-1", body.get(0).get("id"));
    assertEquals("orders-service", body.get(0).get("deploymentName"));
    assertEquals(0L, body.get(0).get("instanceIndex"));
    assertEquals("ACTIVE", body.get(0).get("kind"));
    assertEquals("module active", body.get(0).get("message"));
    assertFalse(body.get(0).containsKey("causeSummary"));
  }

  /**
   * QA finding: {@code gimle events <deployment> <idx>} with no {@code --tenant} at all silently
   * returned nothing even for a genuinely default-tenant deployment -- every other verb this class
   * exposes resolves a bare, untenanted name against whichever tenant actually owns it, but this
   * route defaulted straight to the untenanted namespace instead, a bucket a real (parsed, always
   * tenant-normalized) manifest never actually lands in.
   */
  @Test
  void a_default_tenant_deployments_events_are_visible_with_no_tenant_flag_at_all()
      throws Exception {
    store.putAssignment(
        new InstanceAssignment(
            "orders-service",
            0,
            "node-a",
            new ModuleId("com.example.orders", Version.parse("1.0.0")),
            "/tmp/orders.jar",
            OptionalInt.empty(),
            Optional.of(Tenant.DEFAULT_TENANT_ID)));
    String eventBody =
        """
        {"id":"evt-default","deploymentName":"orders-service","instanceIndex":0,\
        "kind":"ACTIVE","message":"module active","occurredAtEpochMilli":1000}
        """;
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/register"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"capabilities\":{\"supportedTiers\":[\"TIER_1\"]}}"))
            .build());
    assertEquals(
        200,
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/events"))
                .POST(HttpRequest.BodyPublishers.ofString(eventBody))
                .build())
            .statusCode());

    HttpResponse<String> noTenantFlag =
        send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/events?deployment=orders-service&instance=0"))
                .GET()
                .build());

    assertEquals(200, noTenantFlag.statusCode());
    assertEquals(1, Json.asObjectList(Json.parse(noTenantFlag.body())).size());
  }

  @Test
  void a_posted_transition_failed_event_carries_its_cause_summary() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/register"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"capabilities\":{\"supportedTiers\":[\"TIER_1\"]}}"))
            .build());
    String eventBody =
        """
        {"id":"evt-2","deploymentName":"orders-service","instanceIndex":0,\
        "kind":"TRANSITION_FAILED","message":"transition ACTIVE -> STOPPING failed",\
        "causeSummary":"java.lang.IllegalStateException: boom","occurredAtEpochMilli":2000}
        """;
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/events"))
            .POST(HttpRequest.BodyPublishers.ofString(eventBody))
            .build());

    HttpResponse<String> events =
        send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/events?deployment=orders-service&instance=0"))
                .GET()
                .build());
    List<Map<String, Object>> body = Json.asObjectList(Json.parse(events.body()));
    assertEquals(1, body.size());
    assertEquals("java.lang.IllegalStateException: boom", body.get(0).get("causeSummary"));
  }

  @Test
  void get_events_with_only_one_of_deployment_and_instance_is_rejected() throws Exception {
    HttpResponse<String> deploymentOnly =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/events?deployment=orders-service"))
                .GET()
                .build());
    HttpResponse<String> instanceOnly =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/events?instance=0")).GET().build());

    assertEquals(400, deploymentOnly.statusCode());
    assertEquals(400, instanceOnly.statusCode());
  }

  @Test
  void get_events_for_an_instance_with_no_events_is_empty() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/events?deployment=unknown&instance=0"))
                .GET()
                .build());

    assertEquals(200, response.statusCode());
    assertTrue(((List<?>) Json.parse(response.body())).isEmpty());
  }

  /**
   * QA finding: a StatefulSet/DaemonSet instance's own relayed event used to be filed under the
   * untenanted namespace regardless of its real tenant -- {@code handleAppendInstanceEvent} only
   * ever joined against {@code storeClient.listAssignments()}, Deployment-kind bookkeeping alone --
   * so a bare {@code GET /events} (no {@code ?tenant=}) against a non-default-tenant StatefulSet
   * instance found it (both landed in the untenanted bucket), while an explicit {@code
   * ?tenant=acme} for the very same instance came back empty, the exact opposite of every other
   * verb's own convention.
   */
  @Test
  void a_statefulset_instances_relayed_event_is_filed_under_its_real_tenant() throws Exception {
    store.putStatefulSetAssignment(
        new com.gimle.mimir.store.StatefulSetAssignment(
            "sessions",
            0,
            "node-a",
            new ModuleId("com.example.sessions", Version.parse("1.0.0")),
            "/tmp/sessions.jar",
            Optional.of("acme")));
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/register"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"capabilities\":{\"supportedTiers\":[\"TIER_1\"]}}"))
            .build());
    String eventBody =
        """
        {"id":"evt-sts","deploymentName":"sessions","instanceIndex":0,\
        "kind":"ACTIVE","message":"module active","occurredAtEpochMilli":1000}
        """;
    assertEquals(
        200,
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/events"))
                .POST(HttpRequest.BodyPublishers.ofString(eventBody))
                .build())
            .statusCode());

    HttpResponse<String> noTenantFlag =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/events?deployment=sessions&instance=0"))
                .GET()
                .build());
    assertEquals(200, noTenantFlag.statusCode());
    assertEquals(1, Json.asObjectList(Json.parse(noTenantFlag.body())).size(), "no --tenant flag");

    HttpResponse<String> wrongTenantFlag =
        send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/events?deployment=sessions&instance=0&tenant=default"))
                .GET()
                .build());
    assertEquals(200, wrongTenantFlag.statusCode());
    assertTrue(
        Json.asObjectList(Json.parse(wrongTenantFlag.body())).isEmpty(),
        "the wrong tenant must not see acme's own event");

    HttpResponse<String> rightTenantFlag =
        send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/events?deployment=sessions&instance=0&tenant=acme"))
                .GET()
                .build());
    assertEquals(200, rightTenantFlag.statusCode());
    assertEquals(1, Json.asObjectList(Json.parse(rightTenantFlag.body())).size(), "--tenant=acme");
  }

  @Test
  void method_not_allowed_on_a_valid_path() throws Exception {
    HttpResponse<String> response =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/register")).GET().build());

    assertEquals(405, response.statusCode());
  }

  // ---- tenants ----

  private static String tenantJson(long maxMemoryBytes, long maxCpuMillicores, int maxInstances) {
    return """
        {"quota":{"maxMemoryBytes":%d,"maxCpuMillicores":%d,"maxInstances":%d}}
        """
        .formatted(maxMemoryBytes, maxCpuMillicores, maxInstances);
  }

  @Test
  void tenant_put_get_list_delete_round_trips() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/acme"))
                .PUT(HttpRequest.BodyPublishers.ofString(tenantJson(1_000_000_000L, 4000, 10)))
                .build());
    assertEquals(200, put.statusCode());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/acme")).GET().build());
    assertEquals(200, get.statusCode());
    Map<String, Object> tenant = Json.asObject(Json.parse(get.body()));
    assertEquals("acme", tenant.get("id"));
    Map<String, Object> quota = Json.asObject(tenant.get("quota"));
    assertEquals(10L, quota.get("maxInstances"));

    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/tenants")).GET().build());
    assertEquals(200, list.statusCode());
    // Also carries the auto-seeded gimle-system tenant (see the reserved-tenant tests below) --
    // asserting "acme" is present rather than an exact total count.
    assertTrue(
        Json.asObjectList(Json.parse(list.body())).stream()
            .anyMatch(tenantJson -> "acme".equals(tenantJson.get("id"))));

    HttpResponse<String> delete =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/acme")).DELETE().build());
    assertEquals(200, delete.statusCode());

    HttpResponse<String> getAfterDelete =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/acme")).GET().build());
    assertEquals(404, getAfterDelete.statusCode());
  }

  /**
   * QA end-user-QA finding: {@code requireAuthorized}'s plaintext short-circuit used to return
   * {@code true} immediately, before the WRITE/DELETE audit-recording branch a few lines later ever
   * ran -- so a plaintext cluster (the default, and what most local/dev clusters run) audited
   * nothing at all, for any resource kind, ever. This class's own {@code @BeforeEach} already runs
   * in plaintext mode (see its own comment), so the PUT above already exercised the fix; this test
   * just makes the durable side effect explicit.
   */
  @Test
  void a_plaintext_write_is_still_recorded_in_the_durable_audit_trail() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/audited-acme"))
                .PUT(HttpRequest.BodyPublishers.ofString(tenantJson(1_000_000_000L, 4000, 10)))
                .build());
    assertEquals(200, put.statusCode());

    List<AuditEvent> events =
        store.listAuditEvents(
            Optional.of("anonymous"), Optional.of("TENANT"), Optional.empty(), Optional.empty());
    assertTrue(
        events.stream()
            .anyMatch(
                e -> "WRITE".equals(e.verb()) && Optional.of("audited-acme").equals(e.tenantId())),
        "a plaintext WRITE must be recorded under the synthetic anonymous principal, not silently"
            + " dropped");
  }

  @Test
  void get_of_an_unknown_tenant_is_404() throws Exception {
    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/nope")).GET().build());
    assertEquals(404, get.statusCode());
  }

  /**
   * {@code gimle-system} is seeded once at construction time, before {@link ApiServer#start()} is
   * ever called and with no {@code PUT /tenants/gimle-system} from this test itself -- {@code
   * startServer} in {@code @BeforeEach} is the only thing that ran.
   */
  @Test
  void gimle_system_tenant_exists_automatically_after_startup_with_no_explicit_seed_call()
      throws Exception {
    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/gimle-system")).GET().build());
    assertEquals(200, get.statusCode());
    Map<String, Object> tenant = Json.asObject(Json.parse(get.body()));
    assertEquals("gimle-system", tenant.get("id"));
    Map<String, Object> quota = Json.asObject(tenant.get("quota"));
    // Generous defaults, not zero/absent -- proves a real ResourceQuota was actually seeded, not
    // just a bare row.
    assertTrue(((Number) quota.get("maxMemoryBytes")).longValue() > 0);
    assertTrue(((Number) quota.get("maxCpuMillicores")).longValue() > 0);
    assertTrue(((Number) quota.get("maxInstances")).intValue() > 0);
  }

  /**
   * The idempotent half of the same seeding logic: a second {@link ApiServer} constructed against
   * the same store -- standing in for a restarted control-plane replica -- must find the row
   * already present and leave an operator's own since-adjusted quota untouched, not reset it back
   * to the seed default.
   */
  @Test
  void a_restart_equivalent_reseed_does_not_clobber_an_already_adjusted_quota() throws Exception {
    // Written straight to the store rather than through PUT /tenants/gimle-system: the reserved
    // tenant admits an operator credential only, and this plaintext client carries none. What is
    // under test here is the reseed, not how the adjusted row got there.
    store.putTenant(new Tenant("gimle-system", new ResourceQuota(123, 456, 7)));

    try (ApiServer restarted =
        new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())) {
      restarted.start();
      HttpResponse<String> get =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://localhost:" + restarted.port() + "/tenants/gimle-system"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, get.statusCode());
      Map<String, Object> quota = Json.asObject(Json.asObject(Json.parse(get.body())).get("quota"));
      assertEquals(123L, quota.get("maxMemoryBytes"));
      assertEquals(456L, quota.get("maxCpuMillicores"));
      assertEquals(7L, quota.get("maxInstances"));
    }
  }

  /**
   * The reserved tenant's veto is the one thing an ordinary grant cannot buy its way past, so the
   * complete absence of a credential must not buy it either. A plaintext caller is the anonymous
   * principal, a member of no group at all, and {@code gimle:operators} is a group -- so every one
   * of these is refused, and the seeded quota is still the seeded quota afterwards.
   */
  @Test
  void a_plaintext_caller_cannot_write_or_delete_the_reserved_system_tenant() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/gimle-system"))
                .PUT(HttpRequest.BodyPublishers.ofString(tenantJson(1, 1, 5)))
                .build());
    assertEquals(403, put.statusCode(), put.body());

    HttpResponse<String> delete =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/gimle-system")).DELETE().build());
    assertEquals(403, delete.statusCode(), delete.body());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/gimle-system")).GET().build());
    assertEquals(200, get.statusCode());
    Map<String, Object> quota = Json.asObject(Json.asObject(Json.parse(get.body())).get("quota"));
    assertTrue(
        ((Number) quota.get("maxInstances")).intValue() > 5,
        "the refused PUT must not have narrowed the seeded quota: " + get.body());
  }

  @Test
  void a_plaintext_caller_cannot_deploy_into_the_reserved_system_tenant() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/sys-workload"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        reservedTenantDeploymentYaml("sys-workload")))
                .build());
    assertEquals(403, put.statusCode(), put.body());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/sys-workload"))
                .GET()
                .build());
    assertEquals(404, get.statusCode(), "a refused submission must not have been stored");
  }

  /** The preview must report the same verdict the real submission would, not a rosier one. */
  @Test
  void a_plaintext_dry_run_into_the_reserved_system_tenant_reports_the_refusal() throws Exception {
    HttpResponse<String> preview =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/sys-workload?dryRun=true"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        reservedTenantDeploymentYaml("sys-workload")))
                .build());
    assertEquals(200, preview.statusCode(), preview.body());
    Map<String, Object> body = Json.asObject(Json.parse(preview.body()));
    assertEquals(false, body.get("admitted"), preview.body());
    assertEquals(403L, ((Number) body.get("wouldRespondStatus")).longValue(), preview.body());
  }

  private static String reservedTenantDeploymentYaml(String name) {
    return """
        kind: Deployment
        name: %s
        tenantId: gimle-system
        module:
          name: com.gimle.example.orders
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
        replicas: 1
        """
        .formatted(name);
  }

  /**
   * Plaintext gives every caller the identical unauthenticated identity, so there's no way to tell
   * a legitimate co-tenant from an uninvited caller reaching into someone else's tenant -- creating
   * a second real tenant is refused outright rather than quietly allowed. The reserved {@code
   * gimle-system} tenant, already seeded by this class's own {@code @BeforeEach}, doesn't count
   * toward the limit: "acme" is the first <i>real</i> tenant and succeeds despite gimle-system
   * already existing.
   */
  @Test
  void creating_a_second_real_tenant_under_plaintext_is_refused() throws Exception {
    HttpResponse<String> first =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/acme"))
                .PUT(HttpRequest.BodyPublishers.ofString(tenantJson(1_000_000_000L, 4000, 10)))
                .build());
    assertEquals(200, first.statusCode());

    HttpResponse<String> second =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/other"))
                .PUT(HttpRequest.BodyPublishers.ofString(tenantJson(1_000_000_000L, 4000, 10)))
                .build());
    assertEquals(403, second.statusCode());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/other")).GET().build());
    assertEquals(404, get.statusCode());
  }

  /**
   * The plaintext single-tenant guard only fires for a genuinely new tenant id -- a second PUT
   * against the same, already-existing tenant is an update, not a second tenant, and stays
   * permitted (every other test in this class that adjusts a tenant's quota relies on exactly
   * this).
   */
  @Test
  void updating_an_already_existing_tenant_under_plaintext_is_still_permitted() throws Exception {
    HttpResponse<String> first =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/acme"))
                .PUT(HttpRequest.BodyPublishers.ofString(tenantJson(1_000_000_000L, 4000, 10)))
                .build());
    assertEquals(200, first.statusCode());

    HttpResponse<String> update =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/acme"))
                .PUT(HttpRequest.BodyPublishers.ofString(tenantJson(2_000_000_000L, 8000, 20)))
                .build());
    assertEquals(200, update.statusCode());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/acme")).GET().build());
    Map<String, Object> quota = Json.asObject(Json.asObject(Json.parse(get.body())).get("quota"));
    assertEquals(20L, quota.get("maxInstances"));
  }

  // ---- tenant quota admission ----

  /** {@code TestModuleBuilder.minimalDescriptor} fixes the request at 16Mi memory / 10m cpu. */
  private Path buildFixtureJar(String uniqueName) {
    return TestModuleBuilder.module("module " + uniqueName + " {\n}\n")
        .withDescriptor(TestModuleBuilder.minimalDescriptor(uniqueName, "1.0.0"))
        .build(tempDir, uniqueName + ".jar");
  }

  private static String tenantedDeploymentYaml(
      String name, int replicas, String artifactPath, String moduleName, String tenantId) {
    return """
        kind: Deployment
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        replicas: %d
        tenantId: %s
        """
        .formatted(name, moduleName, artifactPath, replicas, tenantId);
  }

  @Test
  void deployment_submission_exceeding_tenant_quota_is_rejected() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/tight"))
            .PUT(HttpRequest.BodyPublishers.ofString(tenantJson(1, 1, 1)))
            .build());

    Path jar = buildFixtureJar("com.gimle.fixture.quota.over");
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/over-quota"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        tenantedDeploymentYaml(
                            "over-quota",
                            1,
                            jar.toAbsolutePath().toString(),
                            "com.gimle.fixture.quota.over",
                            "tight")))
                .build());

    assertEquals(409, put.statusCode());
    assertTrue(store.getDeployment(Optional.of("tight"), "over-quota").isEmpty());
  }

  @Test
  void deployment_submission_within_tenant_quota_succeeds() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/roomy"))
            .PUT(HttpRequest.BodyPublishers.ofString(tenantJson(1_000_000_000L, 4000, 10)))
            .build());

    Path jar = buildFixtureJar("com.gimle.fixture.quota.within");
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/within-quota"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        tenantedDeploymentYaml(
                            "within-quota",
                            1,
                            jar.toAbsolutePath().toString(),
                            "com.gimle.fixture.quota.within",
                            "roomy")))
                .build());

    assertEquals(200, put.statusCode());
    assertTrue(store.getDeployment(Optional.of("roomy"), "within-quota").isPresent());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/roomy")).GET().build());
    assertEquals(200, get.statusCode());
    Map<String, Object> tenant = Json.asObject(Json.parse(get.body()));
    Map<String, Object> usage = Json.asObject(tenant.get("usage"));
    // TestModuleBuilder.minimalDescriptor fixes the request at 16Mi memory / 10m cpu; one replica.
    assertEquals(16L * 1024 * 1024, ((Number) usage.get("memoryBytes")).longValue());
    assertEquals(10L, ((Number) usage.get("cpuMillicores")).longValue());
    assertEquals(1L, ((Number) usage.get("instances")).longValue());
    assertEquals(false, tenant.get("quotaViolating"));
  }

  @Test
  void deployment_submission_for_an_unregistered_tenant_is_rejected() throws Exception {
    Path jar = buildFixtureJar("com.gimle.fixture.quota.unknown");
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/unknown-tenant"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        tenantedDeploymentYaml(
                            "unknown-tenant",
                            1,
                            jar.toAbsolutePath().toString(),
                            "com.gimle.fixture.quota.unknown",
                            "does-not-exist")))
                .build());

    assertEquals(409, put.statusCode());
  }

  // ---- policy admission ----

  @Test
  void deployment_exceeding_a_configured_policy_ceiling_is_rejected() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/policy-acme"))
            .PUT(HttpRequest.BodyPublishers.ofString(tenantJson(1_000_000_000L, 4000, 10)))
            .build());
    send(
        HttpRequest.newBuilder(
                URI.create(baseUrl + "/config/policy-acme/policy.maxReplicasPerDeployment"))
            .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":\"1\",\"encrypted\":false}"))
            .build());

    Path jar = buildFixtureJar("com.gimle.fixture.policy.over");
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/over-policy-ceiling"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        tenantedDeploymentYaml(
                            "over-policy-ceiling",
                            2,
                            jar.toAbsolutePath().toString(),
                            "com.gimle.fixture.policy.over",
                            "policy-acme")))
                .build());

    assertEquals(409, put.statusCode());
    assertTrue(store.getDeployment(Optional.of("policy-acme"), "over-policy-ceiling").isEmpty());
  }

  @Test
  void deployment_within_a_configured_policy_ceiling_succeeds() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/policy-acme-2"))
            .PUT(HttpRequest.BodyPublishers.ofString(tenantJson(1_000_000_000L, 4000, 10)))
            .build());
    send(
        HttpRequest.newBuilder(
                URI.create(baseUrl + "/config/policy-acme-2/policy.maxReplicasPerDeployment"))
            .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":\"3\",\"encrypted\":false}"))
            .build());

    Path jar = buildFixtureJar("com.gimle.fixture.policy.within");
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/within-policy-ceiling"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        tenantedDeploymentYaml(
                            "within-policy-ceiling",
                            2,
                            jar.toAbsolutePath().toString(),
                            "com.gimle.fixture.policy.within",
                            "policy-acme-2")))
                .build());

    assertEquals(200, put.statusCode());
    assertTrue(
        store.getDeployment(Optional.of("policy-acme-2"), "within-policy-ceiling").isPresent());
  }

  // ---- the default tenant is a real, enforced tenant ----

  /**
   * Every constraint an operator writes against the tenant a manifest lands in when it names none
   * has to bind the workloads there. These four cover the three constraint kinds plus the CronJob
   * firing path, which materializes its Job outside any HTTP request and so needs its own proof
   * that admission is consulted at all.
   */
  private String defaultTenantDeploymentYaml(
      String name, int replicas, String artifactPath, String moduleName) {
    return """
        kind: Deployment
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        replicas: %d
        """
        .formatted(name, moduleName, artifactPath, replicas);
  }

  private HttpResponse<String> putDefaultTenantQuota(long memory, long cpu, int instances)
      throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/default"))
            .PUT(HttpRequest.BodyPublishers.ofString(tenantJson(memory, cpu, instances)))
            .build());
  }

  @Test
  void a_submission_past_the_default_tenants_quota_is_rejected() throws Exception {
    assertEquals(200, putDefaultTenantQuota(1, 1, 1).statusCode());

    Path jar = buildFixtureJar("com.gimle.fixture.defaultquota.over");
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/default-over-quota"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        defaultTenantDeploymentYaml(
                            "default-over-quota",
                            1,
                            jar.toAbsolutePath().toString(),
                            "com.gimle.fixture.defaultquota.over")))
                .build());

    assertEquals(409, put.statusCode(), put.body());
    assertTrue(put.body().contains("quota"), put.body());
    assertTrue(
        store.getDeployment(Optional.of(Tenant.DEFAULT_TENANT_ID), "default-over-quota").isEmpty());
  }

  @Test
  void a_cronjob_firing_past_the_default_tenants_quota_materializes_no_job() throws Exception {
    Path jar = buildFixtureJar("com.gimle.fixture.defaultquota.cronjob");
    String cronJob =
        """
        kind: CronJob
        name: nightly-over-quota
        schedule: "0 2 * * *"
        jobTemplate:
          module:
            name: com.gimle.fixture.defaultquota.cronjob
            version: 1.0.0
          artifactPath: %s
          backoffLimit: 1
        """
            .formatted(jar.toAbsolutePath().toString());
    assertEquals(
        200,
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/nightly-over-quota"))
                .PUT(HttpRequest.BodyPublishers.ofString(cronJob))
                .build())
            .statusCode());
    // No instance at all fits, so the firing's own one-instance Job is refused outright.
    assertEquals(200, putDefaultTenantQuota(1_000_000_000L, 4000, 0).statusCode());

    HttpResponse<String> trigger =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/cronjobs/nightly-over-quota/trigger"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());

    assertEquals(409, trigger.statusCode(), trigger.body());
    HttpResponse<String> jobs =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/jobs")).GET().build());
    assertTrue(
        Json.asObjectList(Json.parse(jobs.body())).isEmpty(),
        "a refused firing must materialize no Job: " + jobs.body());
  }

  @Test
  void a_replica_ceiling_configured_on_the_default_tenant_is_enforced() throws Exception {
    send(
        HttpRequest.newBuilder(
                URI.create(baseUrl + "/config/default/policy.maxReplicasPerDeployment"))
            .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":\"3\",\"encrypted\":false}"))
            .build());

    Path jar = buildFixtureJar("com.gimle.fixture.defaultpolicy.over");
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/default-over-policy"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        defaultTenantDeploymentYaml(
                            "default-over-policy",
                            4,
                            jar.toAbsolutePath().toString(),
                            "com.gimle.fixture.defaultpolicy.over")))
                .build());

    assertEquals(409, put.statusCode(), put.body());
    assertTrue(put.body().contains("policy.maxReplicasPerDeployment"), put.body());
    assertTrue(
        store
            .getDeployment(Optional.of(Tenant.DEFAULT_TENANT_ID), "default-over-policy")
            .isEmpty());
  }

  @Test
  void a_limit_range_min_request_on_the_default_tenant_is_enforced() throws Exception {
    // The fixture requests 16Mi/10m; this floor is above it on both dimensions.
    assertEquals(
        200,
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges/default"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"minRequest\": {\"memory\": \"24Mi\", \"cpu\": \"15m\"}}"))
                .build())
            .statusCode());

    Path jar = buildFixtureJar("com.gimle.fixture.defaultrange.under");
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/default-under-floor"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        defaultTenantDeploymentYaml(
                            "default-under-floor",
                            1,
                            jar.toAbsolutePath().toString(),
                            "com.gimle.fixture.defaultrange.under")))
                .build());

    assertEquals(409, put.statusCode(), put.body());
    assertTrue(put.body().contains("below minimum"), put.body());
    assertTrue(
        store
            .getDeployment(Optional.of(Tenant.DEFAULT_TENANT_ID), "default-under-floor")
            .isEmpty());
  }

  // ---- config/secrets distribution ----

  @Test
  void plain_config_put_and_list_round_trips() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/config/acme/greeting"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"value\":\"hello\",\"encrypted\":false}"))
                .build());
    assertEquals(200, put.statusCode());

    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/config/acme")).GET().build());
    assertEquals(200, list.statusCode());
    List<Map<String, Object>> entries = Json.asObjectList(Json.parse(list.body()));
    assertEquals(1, entries.size());
    assertEquals("greeting", entries.get(0).get("key"));
    assertEquals("hello", entries.get(0).get("value"));
    assertEquals(false, entries.get(0).get("encrypted"));
  }

  @Test
  void encrypted_config_round_trips_to_plaintext_on_read() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/config/acme/db-password"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"value\":\"s3cr3t\",\"encrypted\":true}"))
                .build());
    assertEquals(200, put.statusCode());

    // The value is stored as ciphertext -- never the plaintext -- in the state store itself.
    byte[] stored = store.getConfigEntry("acme", "db-password").orElseThrow().value();
    assertTrue(
        stored.length > 0 && new String(stored, StandardCharsets.UTF_8).indexOf("s3cr3t") < 0,
        "stored value must not contain the plaintext secret");

    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/config/acme")).GET().build());
    List<Map<String, Object>> entries = Json.asObjectList(Json.parse(list.body()));
    assertEquals(1, entries.size());
    assertEquals("s3cr3t", entries.get(0).get("value"));
    assertEquals(true, entries.get(0).get("encrypted"));
  }

  @Test
  void config_delete_removes_the_entry() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/config/acme/temp"))
            .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":\"x\",\"encrypted\":false}"))
            .build());

    HttpResponse<String> delete =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/config/acme/temp")).DELETE().build());
    assertEquals(200, delete.statusCode());

    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/config/acme")).GET().build());
    assertTrue(Json.asObjectList(Json.parse(list.body())).isEmpty());
  }

  /**
   * Matches every other controlplane-owned resource kind's own idempotent-success convention for
   * deleting a name that never existed (deployment/job/cronjob/daemonset/statefulset/service/
   * tenant/networkpolicy/role/rolebinding/account/limitrange/configmap all 2xx/no-op) -- {@code
   * config} used to be the one outlier that 404'd instead.
   */
  @Test
  void config_delete_of_a_nonexistent_key_is_idempotent_success() throws Exception {
    HttpResponse<String> delete =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/config/acme/never-existed"))
                .DELETE()
                .build());
    assertEquals(200, delete.statusCode());
  }

  /**
   * End-to-end proof of {@code ConfigVersionStore}'s wiring through {@code ApiServer}'s own HTTP
   * surface -- the store-level semantics (numbering, tombstones, never rewriting history) are
   * {@code ConfigVersionStoreTest}'s own job; this only proves the routes/auth/JSON shape.
   */
  @Test
  void config_versions_and_rollback_round_trip_through_the_http_surface() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/config/acme/greeting"))
            .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":\"v1\",\"encrypted\":false}"))
            .build());
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/config/acme/greeting"))
            .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":\"v2\",\"encrypted\":false}"))
            .build());

    HttpResponse<String> versions =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/config/acme/greeting/versions"))
                .GET()
                .build());
    assertEquals(200, versions.statusCode());
    List<Map<String, Object>> versionList =
        Json.asObjectList(Json.asObject(Json.parse(versions.body())).get("versions"));
    assertEquals(2, versionList.size());
    assertEquals("v1", versionList.get(0).get("value"));
    assertEquals("v2", versionList.get(1).get("value"));

    HttpResponse<String> rollback =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/config/acme/greeting/rollback"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"version\":1}"))
                .build());
    assertEquals(200, rollback.statusCode());
    Map<String, Object> rolledBack = Json.asObject(Json.parse(rollback.body()));
    assertEquals(3, ((Number) rolledBack.get("version")).intValue());
    assertEquals("v1", rolledBack.get("value"));

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/config/acme")).GET().build());
    assertEquals("v1", Json.asObjectList(Json.parse(get.body())).get(0).get("value"));
  }

  @Test
  void config_rollback_of_an_unknown_version_is_404() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/config/acme/greeting"))
            .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":\"v1\",\"encrypted\":false}"))
            .build());

    HttpResponse<String> rollback =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/config/acme/greeting/rollback"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"version\":99}"))
                .build());

    assertEquals(404, rollback.statusCode());
  }

  /** Same end-to-end proof as above, for {@code ConfigMapStore}'s own version ledger. */
  @Test
  void configmap_versions_and_rollback_round_trip_through_the_http_surface() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/configmaps/acme/app-config"))
            .PUT(HttpRequest.BodyPublishers.ofString("{\"data\":{\"a\":\"1\"}}"))
            .build());
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/configmaps/acme/app-config"))
            .PUT(HttpRequest.BodyPublishers.ofString("{\"data\":{\"a\":\"2\"}}"))
            .build());

    HttpResponse<String> versions =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/configmaps/acme/app-config/versions"))
                .GET()
                .build());
    assertEquals(200, versions.statusCode());
    List<Map<String, Object>> versionList =
        Json.asObjectList(Json.asObject(Json.parse(versions.body())).get("versions"));
    assertEquals(2, versionList.size());

    HttpResponse<String> rollback =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/configmaps/acme/app-config/rollback"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"version\":1}"))
                .build());
    assertEquals(200, rollback.statusCode());
    Map<String, Object> rolledBack = Json.asObject(Json.parse(rollback.body()));
    assertEquals(3, ((Number) rolledBack.get("version")).intValue());
    assertEquals(Map.of("a", "1"), rolledBack.get("data"));
  }

  // ---- /secrets/{tenantId}/... proxy to Fafnir ----

  @Test
  void secrets_put_and_get_round_trip_through_the_fafnir_proxy() throws Exception {
    store.putTenant(new Tenant("acme", new ResourceQuota(1_000_000_000L, 4000, 10)));
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        Json.write(Map.of("value", encode("hunter2")))))
                .build());
    assertEquals(200, put.statusCode());
    assertEquals(1L, Json.asObject(Json.parse(put.body())).get("version"));

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password"))
                .GET()
                .build());
    assertEquals(200, get.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(get.body()));
    assertEquals("hunter2", decode((String) body.get("value")));
  }

  @Test
  void secrets_list_never_proxies_a_value_field_and_is_absent_from_plain_config_list()
      throws Exception {
    store.putTenant(new Tenant("acme", new ResourceQuota(1_000_000_000L, 4000, 10)));
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password"))
            .PUT(
                HttpRequest.BodyPublishers.ofString(Json.write(Map.of("value", encode("hunter2")))))
            .build());

    HttpResponse<String> secretsList =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme")).GET().build());
    List<Object> secrets =
        Json.asArray(Json.asObject(Json.parse(secretsList.body())).get("secrets"));
    assertEquals(1, secrets.size());
    assertEquals(Set.of("key", "latestVersion", "deleted"), Json.asObject(secrets.get(0)).keySet());

    // Fafnir's own synthetic db-password@meta/db-password@1 bookkeeping entries must never leak
    // into the plain /config/{tenantId} listing -- the two resource kinds stay on two distinct
    // API surfaces because SECRET and CONFIG are independently grantable ResourceKinds, so a role
    // holding one must not implicitly see the other.
    HttpResponse<String> configList =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/config/acme")).GET().build());
    assertTrue(Json.asObjectList(Json.parse(configList.body())).isEmpty());
  }

  @Test
  void secrets_undelete_restores_a_soft_deleted_secret_through_the_fafnir_proxy() throws Exception {
    store.putTenant(new Tenant("acme", new ResourceQuota(1_000_000_000L, 4000, 10)));
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password"))
            .PUT(
                HttpRequest.BodyPublishers.ofString(Json.write(Map.of("value", encode("hunter2")))))
            .build());
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password")).DELETE().build());

    // The proxy only forwards GET/PUT/DELETE to Fafnir for the plain /secrets/* surface --
    // undelete's POST sub-route needs its own carve-out in ApiServer#handleSecretsProxy, exactly
    // the shape #handleSecretMapsProxy already carries for POST .../rollback.
    HttpResponse<String> undelete =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password/undelete"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
    assertEquals(200, undelete.statusCode());
    assertEquals(1L, Json.asObject(Json.parse(undelete.body())).get("version"));

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password"))
                .GET()
                .build());
    assertEquals(200, get.statusCode());
    assertEquals("hunter2", decode((String) Json.asObject(Json.parse(get.body())).get("value")));
  }

  @Test
  void secrets_delete_removes_the_secret() throws Exception {
    store.putTenant(new Tenant("acme", new ResourceQuota(1_000_000_000L, 4000, 10)));
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/temp"))
            .PUT(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("value", encode("x")))))
            .build());

    HttpResponse<String> delete =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/temp")).DELETE().build());
    assertEquals(200, delete.statusCode());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/temp")).GET().build());
    assertEquals(404, get.statusCode());
  }

  private static String encode(String plaintext) {
    return Base64.getEncoder().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8));
  }

  private static String decode(String base64) {
    return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
  }

  @Test
  void console_addons_are_advertised_to_an_unauthenticated_reader() throws Exception {
    Path consoleRoot = tempDir.resolve("console-addons-dist");
    Files.createDirectories(consoleRoot);
    Files.writeString(consoleRoot.resolve("index.html"), "<html>shell</html>");
    Path catalog = tempDir.resolve("catalog");
    Files.createDirectories(catalog.resolve("console"));
    Files.writeString(
        catalog.resolve("console").resolve("addons.json"),
        """
        {"addons": [{"id": "gateway", "title": "Gateway", "description": "d", "route": "/gateway"}]}
        """);
    try (URLClassLoader loader = new URLClassLoader(new URL[] {catalog.toUri().toURL()}, null)) {
      server.serveConsole(consoleRoot, ConsoleAddons.resolve(loader, "none"));
    }

    // No session, no client certificate: this route says which screens exist, not what they hold.
    HttpResponse<String> response =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/console/addons")).GET().build());

    assertEquals(200, response.statusCode());
    assertEquals(
        List.of(Map.of("id", "gateway", "enabled", false)),
        Json.asObject(Json.parse(response.body())).get("addons"));
  }

  @Test
  void console_static_files_are_served_once_wired() throws Exception {
    Path consoleRoot = tempDir.resolve("console-dist");
    Files.createDirectories(consoleRoot);
    Files.writeString(consoleRoot.resolve("index.html"), "<html>shell</html>");
    Files.writeString(consoleRoot.resolve("app.js"), "console.log('hi');");
    server.serveConsole(consoleRoot, ConsoleAddons.resolve(getClass().getClassLoader(), null));

    HttpResponse<String> asset =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/console/app.js")).GET().build());
    assertEquals(200, asset.statusCode());
    assertEquals("console.log('hi');", asset.body());

    HttpResponse<String> deepLink =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/console/deployments/orders-service"))
                .GET()
                .build());
    assertEquals(200, deepLink.statusCode());
    assertEquals("<html>shell</html>", deepLink.body());
  }

  /**
   * The whole operator volume flow through the real proxy: a stub agent serves its node-local
   * {@code /volumes} inventory, the control plane aggregates it with store-derived attachment, and
   * a destroy is forwarded for a detached volume but refused outright for an attached one --
   * without ever reaching the agent, proving the control-plane-side guard acts first.
   */
  @Test
  void volumes_are_aggregated_with_attachment_and_destroy_guards_attached_data() throws Exception {
    java.util.concurrent.atomic.AtomicBoolean agentSawDelete =
        new java.util.concurrent.atomic.AtomicBoolean();
    com.sun.net.httpserver.HttpServer agentStub =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    agentStub.createContext(
        "/volumes",
        exchange -> {
          byte[] body;
          if ("DELETE".equals(exchange.getRequestMethod())) {
            agentSawDelete.set(true);
            body = "{\"destroyed\": true}".getBytes(StandardCharsets.UTF_8);
          } else {
            body =
                ("[{\"statefulSet\": \"sessions\", \"instanceIndex\": 0, \"usedBytes\": 42,"
                        + " \"path\": \"/data/volumes/sessions/0\", \"inUse\": true, \"tenantId\":"
                        + " \"default\"}, {\"statefulSet\": \"sessions\", \"instanceIndex\": 1,"
                        + " \"usedBytes\": 7, \"path\": \"/data/volumes/sessions/1\", \"inUse\":"
                        + " false, \"tenantId\": \"default\"}]")
                    .getBytes(StandardCharsets.UTF_8);
          }
          exchange.sendResponseHeaders(200, body.length);
          try (java.io.OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    agentStub.start();
    try {
      store.putNodeRegistration(
          new NodeRegistration(
              "node-a",
              new NodeCapabilities(Set.of(IsolationTier.TIER_1, IsolationTier.TIER_2)),
              Optional.of("127.0.0.1:" + agentStub.getAddress().getPort())));
      store.putStatefulSetSpec(
          new com.gimle.mimir.manifest.StatefulSetSpec(
              "sessions",
              new ModuleId("com.example.sessions", Version.parse("1.0.0")),
              "/tmp/sessions.jar",
              1,
              com.gimle.mimir.manifest.PlacementConstraints.NONE,
              Optional.of(Tenant.DEFAULT_TENANT_ID),
              Optional.empty()));
      store.putStatefulSetIndexNode(Optional.of(Tenant.DEFAULT_TENANT_ID), "sessions", 0, "node-a");

      HttpResponse<String> listing =
          send(HttpRequest.newBuilder(URI.create(baseUrl + "/volumes")).GET().build());
      assertEquals(200, listing.statusCode());
      List<Map<String, Object>> volumes =
          Json.asObjectList(Json.asObject(Json.parse(listing.body())).get("volumes"));
      assertEquals(2, volumes.size());
      Map<String, Object> attached =
          volumes.stream()
              .filter(v -> ((Number) v.get("instanceIndex")).intValue() == 0)
              .findFirst()
              .orElseThrow();
      assertEquals("node-a", attached.get("nodeId"));
      assertEquals(Boolean.TRUE, attached.get("attached"));
      Map<String, Object> orphan =
          volumes.stream()
              .filter(v -> ((Number) v.get("instanceIndex")).intValue() == 1)
              .findFirst()
              .orElseThrow();
      // Index 1 has no sticky binding (replicas=1), so its on-disk data is a reclaimable orphan.
      assertEquals(Boolean.FALSE, orphan.get("attached"));

      HttpResponse<String> refusedDestroy =
          send(
              HttpRequest.newBuilder(
                      URI.create(baseUrl + "/volumes/node-a/sessions/0?tenant=default"))
                  .DELETE()
                  .build());
      assertEquals(409, refusedDestroy.statusCode());
      assertFalse(agentSawDelete.get(), "an attached volume's destroy must never reach the agent");

      HttpResponse<String> allowedDestroy =
          send(
              HttpRequest.newBuilder(
                      URI.create(baseUrl + "/volumes/node-a/sessions/1?tenant=default"))
                  .DELETE()
                  .build());
      assertEquals(200, allowedDestroy.statusCode());
      assertTrue(agentSawDelete.get());
    } finally {
      agentStub.stop(0);
    }
  }

  /**
   * A destroy that names no tenant addresses the untenanted namespace, never the {@code default}
   * tenant's identically-named volume at the same set and index. Silently resolving an omitted
   * tenant to {@code default} here -- correct for a workload route, whose manifest parser applies
   * that same default -- made a request naming an untenanted or differently-tenanted volume land on
   * the default tenant's data instead, on an irreversible operation.
   */
  @Test
  void a_destroy_naming_no_tenant_never_resolves_to_the_default_tenants_volume() throws Exception {
    java.util.List<String> agentDeletes = java.util.Collections.synchronizedList(new ArrayList<>());
    com.sun.net.httpserver.HttpServer agentStub =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    agentStub.createContext(
        "/volumes",
        exchange -> {
          if ("DELETE".equals(exchange.getRequestMethod())) {
            agentDeletes.add(exchange.getRequestURI().toString());
          }
          byte[] body = "{\"destroyed\": true}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (java.io.OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    agentStub.start();
    try {
      store.putNodeRegistration(
          new NodeRegistration(
              "node-a",
              new NodeCapabilities(Set.of(IsolationTier.TIER_1)),
              Optional.of("127.0.0.1:" + agentStub.getAddress().getPort())));
      // The default tenant's sessions[0] is attached, so a destroy resolving to it would be
      // refused with 409 -- which is exactly what an omitted tenant must NOT resolve to.
      store.putStatefulSetSpec(
          new com.gimle.mimir.manifest.StatefulSetSpec(
              "sessions",
              new ModuleId("com.example.sessions", Version.parse("1.0.0")),
              "/tmp/sessions.jar",
              1,
              com.gimle.mimir.manifest.PlacementConstraints.NONE,
              Optional.of(Tenant.DEFAULT_TENANT_ID),
              Optional.empty()));
      store.putStatefulSetIndexNode(Optional.of(Tenant.DEFAULT_TENANT_ID), "sessions", 0, "node-a");

      HttpResponse<String> untenantedDestroy =
          send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/volumes/node-a/sessions/0"))
                  .DELETE()
                  .build());

      assertEquals(200, untenantedDestroy.statusCode());
      assertEquals(1, agentDeletes.size());
      assertFalse(
          agentDeletes.get(0).contains("tenant="),
          "an omitted tenant must reach the agent omitted, not rewritten to a tenant id: "
              + agentDeletes.get(0));

      // And the tenanted coordinate is still guarded, proving the two are genuinely distinct.
      assertEquals(
          409,
          send(HttpRequest.newBuilder(
                      URI.create(baseUrl + "/volumes/node-a/sessions/0?tenant=default"))
                  .DELETE()
                  .build())
              .statusCode());
    } finally {
      agentStub.stop(0);
    }
  }

  /** A blank {@code ?tenant=} is the untenanted namespace, not a tenant literally named "". */
  @Test
  void a_blank_tenant_parameter_on_a_destroy_is_forwarded_as_untenanted() throws Exception {
    java.util.List<String> agentDeletes = java.util.Collections.synchronizedList(new ArrayList<>());
    com.sun.net.httpserver.HttpServer agentStub =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    agentStub.createContext(
        "/volumes",
        exchange -> {
          if ("DELETE".equals(exchange.getRequestMethod())) {
            agentDeletes.add(exchange.getRequestURI().toString());
          }
          byte[] body = "{\"destroyed\": true}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (java.io.OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    agentStub.start();
    try {
      store.putNodeRegistration(
          new NodeRegistration(
              "node-a",
              new NodeCapabilities(Set.of(IsolationTier.TIER_1)),
              Optional.of("127.0.0.1:" + agentStub.getAddress().getPort())));

      assertEquals(
          200,
          send(HttpRequest.newBuilder(URI.create(baseUrl + "/volumes/node-a/sessions/0?tenant="))
                  .DELETE()
                  .build())
              .statusCode());

      assertEquals(1, agentDeletes.size());
      assertFalse(agentDeletes.get(0).contains("tenant="), agentDeletes.get(0));
    } finally {
      agentStub.stop(0);
    }
  }

  @Test
  void the_bare_root_redirects_to_the_console_once_wired() throws Exception {
    Path consoleRoot = tempDir.resolve("console-dist-root-redirect");
    Files.createDirectories(consoleRoot);
    Files.writeString(consoleRoot.resolve("index.html"), "<html>shell</html>");
    server.serveConsole(consoleRoot, ConsoleAddons.resolve(getClass().getClassLoader(), null));

    HttpResponse<String> response =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/")).GET().build());

    assertEquals(302, response.statusCode());
    assertEquals("/console", response.headers().firstValue("Location").orElse(""));
  }

  @Test
  void a_log_request_for_a_never_registered_node_returns_404_not_502() throws Exception {
    HttpResponse<String> response =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/logs/nodes/ghost")).GET().build());

    assertEquals(404, response.statusCode());
    assertTrue(response.body().contains("unknown node: ghost"));
  }

  @Test
  void a_log_request_for_a_registered_node_with_no_advertised_address_yet_still_returns_502()
      throws Exception {
    store.putNodeRegistration(
        new NodeRegistration(
            "node-a", new NodeCapabilities(Set.of(IsolationTier.TIER_1, IsolationTier.TIER_2))));

    HttpResponse<String> response =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/logs/nodes/node-a")).GET().build());

    assertEquals(502, response.statusCode());
    assertTrue(response.body().contains("no known log-server address"));
  }
}
