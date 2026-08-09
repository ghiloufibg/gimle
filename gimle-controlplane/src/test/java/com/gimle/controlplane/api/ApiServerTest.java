package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.Json;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.StateStore;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
  void assignments_endpoint_joins_assignments_with_their_deployments_artifact_and_module_id()
      throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
            .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml("orders-service", 1)))
            .build());
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));

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
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putAssignment(new InstanceAssignment("orders-service", 1, "node-a"));
    ModuleId moduleId = new ModuleId("com.gimle.example.orders", Version.parse("1.0.0"));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            "node-a",
            new ResourceUsageSnapshot(1000L, 0, 1000, 0),
            List.of(
                new InstanceObservation(
                    "orders-service", 0, moduleId, "ACTIVE", true, true, 10.0, 0, 0L, 0L, 2.0),
                new InstanceObservation(
                    "orders-service", 1, moduleId, "ACTIVE", true, true, 20.0, 0, 0L, 0L, 4.0))));

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
  void get_events_requires_deployment_and_instance_query_params() throws Exception {
    HttpResponse<String> response =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/events")).GET().build());

    assertEquals(400, response.statusCode());
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

  @Test
  void method_not_allowed_on_a_valid_path() throws Exception {
    HttpResponse<String> response =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/register")).GET().build());

    assertEquals(405, response.statusCode());
  }

  // ---- tenants (Phase 5 design §5.1) ----

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
    assertEquals(1, Json.asObjectList(Json.parse(list.body())).size());

    HttpResponse<String> delete =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/acme")).DELETE().build());
    assertEquals(200, delete.statusCode());

    HttpResponse<String> getAfterDelete =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/acme")).GET().build());
    assertEquals(404, getAfterDelete.statusCode());
  }

  @Test
  void get_of_an_unknown_tenant_is_404() throws Exception {
    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/nope")).GET().build());
    assertEquals(404, get.statusCode());
  }

  // ---- tenant quota admission (Phase 5 design §5.2) ----

  /** {@code TestModuleBuilder.minimalDescriptor} fixes the request at 16Mi memory / 10m cpu. */
  private Path buildFixtureJar(String uniqueName) {
    return TestModuleBuilder.module("module " + uniqueName + " {\n}\n")
        .withDescriptor(TestModuleBuilder.minimalDescriptor(uniqueName, "1.0.0"))
        .build(tempDir, uniqueName + ".jar");
  }

  private static String tenantedDeploymentYaml(
      String name, int replicas, String artifactPath, String moduleName, String tenantId) {
    return """
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
    assertTrue(store.getDeployment("over-quota").isEmpty());
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
    assertTrue(store.getDeployment("within-quota").isPresent());
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

  // ---- config/secrets distribution (Phase 5 design §6) ----

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

  @Test
  void console_static_files_are_served_once_wired() throws Exception {
    Path consoleRoot = tempDir.resolve("console-dist");
    Files.createDirectories(consoleRoot);
    Files.writeString(consoleRoot.resolve("index.html"), "<html>shell</html>");
    Files.writeString(consoleRoot.resolve("app.js"), "console.log('hi');");
    server.serveConsole(consoleRoot);

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
