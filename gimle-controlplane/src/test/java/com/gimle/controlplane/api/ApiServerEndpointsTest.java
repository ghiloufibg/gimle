package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.Json;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.manifest.StatefulSetSpec;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.DaemonSetAssignment;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.JobRun;
import com.gimle.mimir.store.StatefulSetAssignment;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * {@code GET /endpoints/{deployment}}: joins each of a deployment's assignments against the
 * assigned node's own registered address and the latest heartbeat's declared ports, mirroring
 * {@code findObservation}'s existing assignment-plus-heartbeat join. Built directly on {@link
 * StateMutation}/{@code putHeartbeat} rather than a real agent -- this route only ever reads what
 * the store already holds, so a real cluster isn't needed to exercise it.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerEndpointsTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessStore inProcessStore;
  private InProcessFafnir inProcessFafnir;
  private ApiServer server;
  private HttpClient client;
  private String baseUrl;

  @BeforeEach
  void startServer() throws IOException {
    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
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
  @Timeout(10)
  void returns_the_host_and_declared_ports_for_a_live_vessel_instance() throws Exception {
    ModuleId moduleId = new ModuleId("com.acme.billing-api", Version.parse("2.3.1"));
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutDeployment(
                new DeploymentSpec(
                    "billing-api",
                    moduleId,
                    "/artifacts/billing.jar",
                    1,
                    PlacementConstraints.NONE,
                    Optional.empty(),
                    Optional.of(Tenant.DEFAULT_TENANT_ID)),
                0));
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutAssignment(
                new InstanceAssignment(
                    "billing-api",
                    0,
                    "node-1",
                    moduleId,
                    "/artifacts/billing.jar",
                    OptionalInt.empty(),
                    Optional.of(Tenant.DEFAULT_TENANT_ID))));
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutNodeRegistration(
                new NodeRegistration(
                    "node-1", new NodeCapabilities(Set.of()), Optional.of("10.0.0.5:9101"))));
    inProcessStore
        .client()
        .putHeartbeat(
            new NodeHeartbeat(
                "node-1",
                new ResourceUsageSnapshot(0, 0, 0, 0),
                List.of(
                    InstanceObservation.builder("billing-api", 0, moduleId, "ACTIVE", true, true)
                        .ports(Map.of("HTTP_PORT", 54321))
                        .tenantId(Optional.of(Tenant.DEFAULT_TENANT_ID))
                        .build())));

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/endpoints/billing-api")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode());
    List<Object> body = Json.asArray(Json.parse(response.body()));
    assertEquals(1, body.size());
    Map<String, Object> endpoint = Json.asObject(body.get(0));
    assertEquals(0, ((Number) endpoint.get("instanceIndex")).intValue());
    assertEquals("node-1", endpoint.get("nodeId"));
    assertEquals("10.0.0.5", endpoint.get("host"));
    Map<String, Object> ports = Json.asObject(endpoint.get("ports"));
    assertEquals(54321, ((Number) ports.get("HTTP_PORT")).intValue());
  }

  @Test
  @Timeout(10)
  void an_instance_with_no_heartbeat_yet_is_still_listed_without_ports() throws Exception {
    ModuleId moduleId = new ModuleId("com.acme.greeter", Version.parse("1.0.0"));
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutDeployment(
                new DeploymentSpec(
                    "greeter",
                    moduleId,
                    "/artifacts/greeter.jar",
                    1,
                    PlacementConstraints.NONE,
                    Optional.empty(),
                    Optional.of(Tenant.DEFAULT_TENANT_ID)),
                0));
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutAssignment(
                new InstanceAssignment(
                    "greeter",
                    0,
                    "node-2",
                    moduleId,
                    "/artifacts/greeter.jar",
                    OptionalInt.empty(),
                    Optional.of(Tenant.DEFAULT_TENANT_ID))));

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/endpoints/greeter")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode());
    List<Object> body = Json.asArray(Json.parse(response.body()));
    assertEquals(1, body.size());
    Map<String, Object> endpoint = Json.asObject(body.get(0));
    assertEquals("node-2", endpoint.get("nodeId"));
    assertTrue(!endpoint.containsKey("ports"));
  }

  @Test
  @Timeout(10)
  void an_unknown_deployment_is_a_404() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/endpoints/does-not-exist")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(404, response.statusCode());
  }

  @Test
  @Timeout(10)
  void a_missing_deployment_name_is_a_400() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/endpoints/")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(400, response.statusCode());
  }

  /**
   * The generalization beyond Deployment: a Job's current run resolves through {@code
   * /endpoints/{name}} the same way a Deployment's replicas do, {@code attempt} playing {@code
   * instanceIndex}'s own role.
   */
  @Test
  @Timeout(10)
  void a_job_run_is_listed_under_its_own_endpoints_route() throws Exception {
    ModuleId moduleId = new ModuleId("com.acme.cleanup", Version.parse("1.0.0"));
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutJobSpec(
                new JobSpec(
                    "cleanup-job",
                    moduleId,
                    "/artifacts/cleanup.jar",
                    PlacementConstraints.NONE,
                    Optional.empty(),
                    3,
                    Optional.of(Tenant.DEFAULT_TENANT_ID),
                    Optional.empty())));
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutJobRun(
                new JobRun(
                    "cleanup-job",
                    0,
                    "node-3",
                    moduleId,
                    "/artifacts/cleanup.jar",
                    Instant.now(),
                    Optional.of(Tenant.DEFAULT_TENANT_ID))));

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/endpoints/cleanup-job")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode());
    List<Object> body = Json.asArray(Json.parse(response.body()));
    assertEquals(1, body.size());
    Map<String, Object> endpoint = Json.asObject(body.get(0));
    assertEquals(0, ((Number) endpoint.get("instanceIndex")).intValue());
    assertEquals("node-3", endpoint.get("nodeId"));
  }

  /** A DaemonSet's per-node assignment resolves the same way, {@code instanceIndex} fixed at 0. */
  @Test
  @Timeout(10)
  void a_daemonset_assignment_is_listed_under_its_own_endpoints_route() throws Exception {
    ModuleId moduleId = new ModuleId("com.acme.node-exporter", Version.parse("1.0.0"));
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutDaemonSetSpec(
                new DaemonSetSpec(
                    "node-exporter",
                    moduleId,
                    "/artifacts/node-exporter.jar",
                    PlacementConstraints.NONE,
                    Optional.of(Tenant.DEFAULT_TENANT_ID),
                    Optional.empty())));
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutDaemonSetAssignment(
                new DaemonSetAssignment(
                    "node-exporter",
                    "node-4",
                    moduleId,
                    "/artifacts/node-exporter.jar",
                    Optional.of(Tenant.DEFAULT_TENANT_ID))));

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/endpoints/node-exporter")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode());
    List<Object> body = Json.asArray(Json.parse(response.body()));
    assertEquals(1, body.size());
    Map<String, Object> endpoint = Json.asObject(body.get(0));
    assertEquals(0, ((Number) endpoint.get("instanceIndex")).intValue());
    assertEquals("node-4", endpoint.get("nodeId"));
  }

  /** A StatefulSet's per-index assignment resolves the same way, a real {@code instanceIndex}. */
  @Test
  @Timeout(10)
  void a_statefulset_assignment_is_listed_under_its_own_endpoints_route() throws Exception {
    ModuleId moduleId = new ModuleId("com.acme.ledger", Version.parse("1.0.0"));
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutStatefulSetSpec(
                new StatefulSetSpec(
                    "ledger",
                    moduleId,
                    "/artifacts/ledger.jar",
                    1,
                    PlacementConstraints.NONE,
                    Optional.of(Tenant.DEFAULT_TENANT_ID),
                    Optional.empty())));
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutStatefulSetAssignment(
                new StatefulSetAssignment(
                    "ledger",
                    0,
                    "node-5",
                    moduleId,
                    "/artifacts/ledger.jar",
                    Optional.of(Tenant.DEFAULT_TENANT_ID))));

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/endpoints/ledger")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode());
    List<Object> body = Json.asArray(Json.parse(response.body()));
    assertEquals(1, body.size());
    Map<String, Object> endpoint = Json.asObject(body.get(0));
    assertEquals(0, ((Number) endpoint.get("instanceIndex")).intValue());
    assertEquals("node-5", endpoint.get("nodeId"));
  }
}
