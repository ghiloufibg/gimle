package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.store.InstanceAssignment;
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
 * {@code POST}/{@code GET}/{@code DELETE /services*} and {@code GET /services/{name}/endpoints}
 * over a real loopback HTTP connection, the same style {@code ApiServerEndpointsTest} already
 * established for the analogous Deployment-facing route.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerServicesTest {

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

  private HttpResponse<String> send(HttpRequest request) throws Exception {
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static String serviceJson(String name, String deploymentName, int port) {
    return """
        {"name": "%s", "deploymentNames": ["%s"], "port": %d}
        """
        .formatted(name, deploymentName, port);
  }

  @Test
  @Timeout(10)
  void an_external_name_service_round_trips_and_resolves_to_its_external_host() throws Exception {
    HttpResponse<String> post =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {"name": "billing", "port": 443, "targetPort": 8443,
                         "externalName": "billing.example.com"}
                        """))
                .build());
    assertEquals(200, post.statusCode());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/services/billing")).GET().build());
    assertEquals(200, get.statusCode());
    Map<String, Object> spec = Json.asObject(Json.parse(get.body()));
    assertEquals("billing.example.com", spec.get("externalName"));
    assertEquals(List.of(), spec.get("deploymentNames"));

    HttpResponse<String> endpoints =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/services/billing/endpoints"))
                .GET()
                .build());
    assertEquals(200, endpoints.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(endpoints.body()));
    List<Map<String, Object>> endpointList = Json.asObjectList(body.get("endpoints"));
    assertEquals(1, endpointList.size());
    assertEquals("billing.example.com", endpointList.get(0).get("host"));
    assertEquals(8443, ((Number) endpointList.get(0).get("port")).intValue());
  }

  @Test
  @Timeout(10)
  void an_external_name_service_naming_deployments_too_is_rejected() throws Exception {
    HttpResponse<String> post =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {"name": "billing", "deploymentNames": ["orders-service"], "port": 443,
                         "externalName": "billing.example.com"}
                        """))
                .build());
    assertEquals(400, post.statusCode());
  }

  @Test
  @Timeout(10)
  void session_affinity_round_trips_on_both_service_and_endpoints_shapes() throws Exception {
    HttpResponse<String> post =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {"name": "orders", "deploymentNames": ["orders-service"], "port": 8080,
                         "sessionAffinity": true}
                        """))
                .build());
    assertEquals(200, post.statusCode());

    Map<String, Object> spec =
        Json.asObject(
            Json.parse(
                send(HttpRequest.newBuilder(URI.create(baseUrl + "/services/orders")).GET().build())
                    .body()));
    assertEquals(true, spec.get("sessionAffinity"));

    Map<String, Object> endpoints =
        Json.asObject(
            Json.parse(
                send(HttpRequest.newBuilder(URI.create(baseUrl + "/services/orders/endpoints"))
                        .GET()
                        .build())
                    .body()));
    assertEquals(true, endpoints.get("sessionAffinity"));
  }

  @Test
  @Timeout(10)
  void post_then_get_a_service_round_trips() throws Exception {
    HttpResponse<String> post =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        serviceJson("orders", "orders-service", 8080)))
                .build());
    assertEquals(200, post.statusCode());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/services/orders")).GET().build());
    assertEquals(200, get.statusCode());
    Map<String, Object> spec = Json.asObject(Json.parse(get.body()));
    assertEquals("orders", spec.get("name"));
    assertEquals(8080L, spec.get("port"));
    assertFalse(
        spec.containsKey("targetPort"),
        "a Service that declared no targetPort must not report one back");
    assertEquals(List.of("orders-service"), spec.get("deploymentNames"));
  }

  @Test
  @Timeout(10)
  void a_target_port_distinct_from_port_round_trips() throws Exception {
    String body =
        """
        {"name": "orders", "deploymentNames": ["orders-service"], "port": 8080, "targetPort": 9090}
        """;
    assertEquals(
        200,
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build())
            .statusCode());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/services/orders")).GET().build());
    Map<String, Object> spec = Json.asObject(Json.parse(get.body()));
    assertEquals(8080L, spec.get("port"));
    assertEquals(9090L, spec.get("targetPort"));
  }

  @Test
  @Timeout(10)
  void get_of_an_unknown_service_is_404() throws Exception {
    HttpResponse<String> response =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/services/nope")).GET().build());
    assertEquals(404, response.statusCode());
  }

  @Test
  @Timeout(10)
  void delete_removes_a_service() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
            .POST(
                HttpRequest.BodyPublishers.ofString(serviceJson("orders", "orders-service", 8080)))
            .build());

    HttpResponse<String> delete =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/services/orders")).DELETE().build());
    assertEquals(200, delete.statusCode());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/services/orders")).GET().build());
    assertEquals(404, get.statusCode());
  }

  /**
   * {@code B6}: {@code DELETE /services/{name}} with no explicit {@code ?tenant=} against a
   * tenant-scoped Service used to resolve {@code tenant} as {@link Optional#empty()} and remove the
   * untenanted key -- a silent no-op that still answered 200, leaving the real, tenant-scoped entry
   * (and its endpoints) fully intact. The GET branch shares the same {@code tenant} resolution and
   * had the identical bug for reads, asserted here too. Both now fall back to {@link
   * ApiServer#resolveTenantForServiceName}, the same fallback the {@code /endpoints} sub-route
   * already had (see {@link #a_tenant_scoped_service_resolves_its_endpoints_from_the_bare_name}).
   */
  @Test
  @Timeout(10)
  void delete_removes_a_tenant_scoped_service_addressed_by_its_bare_name() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"name": "orders", "tenantId": "acme",
                     "deploymentNames": ["orders-service"], "port": 8080}
                    """))
            .build());

    // GET with no ?tenant= must already resolve the tenant-scoped Service by its bare name...
    HttpResponse<String> getBeforeDelete =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/services/orders")).GET().build());
    assertEquals(200, getBeforeDelete.statusCode());
    assertEquals("orders", Json.asObject(Json.parse(getBeforeDelete.body())).get("name"));

    // ...and DELETE with no ?tenant= must remove that same real entry, not silently no-op
    // against an untenanted key nothing was ever stored under.
    HttpResponse<String> delete =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/services/orders")).DELETE().build());
    assertEquals(200, delete.statusCode());

    HttpResponse<String> getAfterDelete =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/services/orders")).GET().build());
    assertEquals(404, getAfterDelete.statusCode(), "the Service must actually be gone");

    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/services")).GET().build());
    assertTrue(
        Json.asArray(Json.parse(list.body())).isEmpty(),
        "the Service must not reappear in the collection listing");
  }

  @Test
  @Timeout(10)
  void services_list_endpoint_returns_every_service() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
            .POST(
                HttpRequest.BodyPublishers.ofString(serviceJson("orders", "orders-service", 8080)))
            .build());
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    serviceJson("catalog", "catalog-service", 8081)))
            .build());

    HttpResponse<String> response =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/services")).GET().build());
    assertEquals(200, response.statusCode());
    List<Object> body = Json.asArray(Json.parse(response.body()));
    assertEquals(2, body.size());
  }

  @Test
  @Timeout(10)
  void services_list_endpoint_is_empty_with_none_submitted() throws Exception {
    HttpResponse<String> response =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/services")).GET().build());
    assertEquals(200, response.statusCode());
    assertTrue(Json.asArray(Json.parse(response.body())).isEmpty());
  }

  @Test
  @Timeout(10)
  void a_missing_service_name_on_post_is_a_400() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {"deploymentNames": ["orders-service"], "port": 8080}
                        """))
                .build());
    assertEquals(400, response.statusCode());
  }

  @Test
  @Timeout(10)
  void an_empty_deployment_names_is_a_400() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {"name": "orders", "deploymentNames": [], "port": 8080}
                        """))
                .build());
    assertEquals(400, response.statusCode());
  }

  /**
   * The contract every other lane depends on: exactly {@code name}/{@code port}/{@code endpoints}
   * (plus {@code targetPort} only when one was declared), each endpoint exactly {@code host}/{@code
   * port}/{@code nodeId}.
   */
  @Test
  @Timeout(10)
  void endpoints_route_returns_the_exact_contract_shape_for_a_live_ready_instance()
      throws Exception {
    ModuleId moduleId = new ModuleId("com.acme.orders", Version.parse("1.0.0"));
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"name": "orders", "deploymentNames": ["orders-service"], "port": 8080}
                    """))
            .build());
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutAssignment(
                new InstanceAssignment(
                    "orders-service",
                    0,
                    "node-1",
                    moduleId,
                    "/artifacts/orders.jar",
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
                    InstanceObservation.builder("orders-service", 0, moduleId, "ACTIVE", true, true)
                        .tenantId(Optional.of(Tenant.DEFAULT_TENANT_ID))
                        .ports(Map.of("HTTP_PORT", 51234))
                        .build())));

    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/services/orders/endpoints"))
                .GET()
                .build());

    assertEquals(200, response.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    assertEquals("orders", body.get("name"));
    assertEquals(8080L, body.get("port"));
    assertFalse(body.containsKey("targetPort"));
    List<Object> endpoints = Json.asArray(body.get("endpoints"));
    assertEquals(1, endpoints.size());
    Map<String, Object> endpoint = Json.asObject(endpoints.get(0));
    assertEquals("10.0.0.5", endpoint.get("host"));
    assertEquals(51234L, endpoint.get("port"));
    assertEquals("node-1", endpoint.get("nodeId"));
    assertEquals(Set.of("host", "port", "nodeId"), endpoint.keySet());
  }

  @Test
  @Timeout(10)
  void endpoints_route_returns_200_with_an_empty_array_when_no_backing_instance_is_live_yet()
      throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
            .POST(
                HttpRequest.BodyPublishers.ofString(serviceJson("orders", "orders-service", 8080)))
            .build());

    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/services/orders/endpoints"))
                .GET()
                .build());

    assertEquals(200, response.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    assertTrue(Json.asArray(body.get("endpoints")).isEmpty());
  }

  /**
   * The whole path as an operator drives it: a Deployment applied with no tenantId and a Service
   * posted with no tenantId must front each other. A workload manifest's omitted tenantId resolves
   * to the default tenant, so a Service that stayed untenanted joined against a namespace no
   * workload can ever land in and reported no endpoints -- for every deployment, whatever ports
   * either side declared, and with no exclusion to say why.
   */
  @Test
  @Timeout(10)
  void a_service_declaring_no_tenant_fronts_a_deployment_that_declared_none_either()
      throws Exception {
    assertEquals(200, postService(serviceJson("orders", "orders-service", 8080)).statusCode());
    recordReadyInstance("orders-service", Map.of("http", 38451));

    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/services/orders/endpoints"))
                .GET()
                .build());

    assertEquals(200, response.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    List<Object> endpoints = Json.asArray(body.get("endpoints"));
    assertEquals(1, endpoints.size(), "expected the deployment's live instance: " + body);
    assertEquals(38451L, Json.asObject(endpoints.get(0)).get("port"));
  }

  /**
   * A hosted module that reports no port can never back a Service, and the resolver has always
   * known that -- it just threw the reason away, leaving an empty endpoint list indistinguishable
   * from "no replicas scheduled yet" to everything downstream of this API.
   */
  @Test
  @Timeout(10)
  void endpoints_route_states_why_a_ready_instance_reporting_no_port_backs_nothing()
      throws Exception {
    ModuleId moduleId = new ModuleId("com.acme.orders", Version.parse("1.0.0"));
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
            .POST(
                HttpRequest.BodyPublishers.ofString(serviceJson("orders", "orders-service", 8080)))
            .build());
    // Tenanted to the default tenant, as every workload a real PUT creates is -- see
    // recordReadyInstance's own comment.
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutAssignment(
                new InstanceAssignment(
                    "orders-service",
                    0,
                    "node-1",
                    moduleId,
                    "/artifacts/orders.jar",
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
                    InstanceObservation.builder("orders-service", 0, moduleId, "ACTIVE", true, true)
                        .tenantId(Optional.of(Tenant.DEFAULT_TENANT_ID))
                        .build())));

    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/services/orders/endpoints"))
                .GET()
                .build());

    assertEquals(200, response.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    assertTrue(Json.asArray(body.get("endpoints")).isEmpty(), response.body());
    List<Object> exclusions = Json.asArray(body.get("exclusions"));
    assertEquals(1, exclusions.size(), response.body());
    assertTrue(String.valueOf(exclusions.get(0)).contains("orders-service"), response.body());
  }

  @Test
  @Timeout(10)
  void endpoints_route_for_an_unknown_service_is_404() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/services/nope/endpoints")).GET().build());
    assertEquals(404, response.statusCode());
  }

  /**
   * The actual bug this persistence swap fixes: {@code ServiceRegistry} used to hold an in-memory
   * map per {@code ApiServer} replica, so a Service created against one replica was invisible to
   * another. Two {@code ApiServer} instances here share one {@link InProcessStore} through two
   * independent {@code StoreClient} connections -- the same "one store cluster, N stateless
   * control-plane replicas" shape production runs, just over loopback instead of a real network --
   * proving a Service POSTed to one is now visible via {@code GET /services} on the other.
   */
  @Test
  @Timeout(10)
  void a_service_posted_to_one_replica_is_visible_on_a_second_replica() throws Exception {
    try (StoreClient secondReplicaClient = inProcessStore.newClient();
        ApiServer secondReplica = new ApiServer(secondReplicaClient, 0, inProcessFafnir.client())) {
      secondReplica.start();
      String secondBaseUrl = "http://localhost:" + secondReplica.port();

      HttpResponse<String> post =
          send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          serviceJson("orders", "orders-service", 8080)))
                  .build());
      assertEquals(200, post.statusCode());

      HttpResponse<String> getFromSecondReplica =
          send(
              HttpRequest.newBuilder(URI.create(secondBaseUrl + "/services/orders")).GET().build());
      assertEquals(200, getFromSecondReplica.statusCode());
      Map<String, Object> spec = Json.asObject(Json.parse(getFromSecondReplica.body()));
      assertEquals("orders", spec.get("name"));
      assertEquals(8080L, spec.get("port"));

      HttpResponse<String> listFromSecondReplica =
          send(HttpRequest.newBuilder(URI.create(secondBaseUrl + "/services")).GET().build());
      assertEquals(1, Json.asArray(Json.parse(listFromSecondReplica.body())).size());
    }
  }

  @Test
  @Timeout(10)
  void posting_the_same_name_again_replaces_the_prior_spec() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
            .POST(
                HttpRequest.BodyPublishers.ofString(serviceJson("orders", "orders-service", 8080)))
            .build());
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    serviceJson("orders", "orders-service-v2", 9090)))
            .build());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/services/orders")).GET().build());
    Map<String, Object> spec = Json.asObject(Json.parse(get.body()));
    assertEquals(9090L, spec.get("port"));
    assertEquals(List.of("orders-service-v2"), spec.get("deploymentNames"));
  }

  private HttpResponse<String> postService(String body) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build());
  }

  /**
   * Recording a live, ready instance of {@code deploymentName} reporting exactly {@code ports}, the
   * same assignment/registration/heartbeat trio {@code ServiceEndpointResolver} joins over.
   */
  private void recordReadyInstance(String deploymentName, Map<String, Integer> ports) {
    ModuleId moduleId = new ModuleId("com.acme.orders", Version.parse("1.0.0"));
    // Tenanted to the default tenant, as every workload a real PUT creates is: a manifest omitting
    // tenantId resolves to it rather than staying untenanted, so an untenanted fixture here would
    // model a state the API cannot produce.
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutAssignment(
                new InstanceAssignment(
                    deploymentName,
                    0,
                    "node-1",
                    moduleId,
                    "/artifacts/orders.jar",
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
                    InstanceObservation.builder(deploymentName, 0, moduleId, "ACTIVE", true, true)
                        .tenantId(Optional.of(Tenant.DEFAULT_TENANT_ID))
                        .ports(ports)
                        .build())));
  }

  @Test
  @Timeout(10)
  void a_second_service_fronting_the_same_deployment_is_created_but_warned_about()
      throws Exception {
    assertEquals(200, postService(serviceJson("orders", "orders-service", 8080)).statusCode());

    HttpResponse<String> overlapping =
        postService(serviceJson("orders-legacy", "orders-service", 8081));

    assertEquals(200, overlapping.statusCode(), "an overlapping Service must still be created");
    List<String> warnings = overlapping.headers().allValues("X-Gimle-Warning");
    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(warnings.get(0).contains("orders-service"), warnings.get(0));
    assertTrue(warnings.get(0).contains("orders"), warnings.get(0));

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/services/orders-legacy")).GET().build());
    assertEquals(200, get.statusCode());
  }

  @Test
  @Timeout(10)
  void a_service_fronting_a_deployment_nothing_else_fronts_earns_no_warning() throws Exception {
    postService(serviceJson("orders", "orders-service", 8080));

    HttpResponse<String> other = postService(serviceJson("billing", "billing-service", 8081));

    assertEquals(List.of(), other.headers().allValues("X-Gimle-Warning"));
  }

  @Test
  @Timeout(10)
  void re_posting_a_service_under_its_own_name_does_not_warn_about_itself() throws Exception {
    postService(serviceJson("orders", "orders-service", 8080));

    HttpResponse<String> again = postService(serviceJson("orders", "orders-service", 8080));

    assertEquals(List.of(), again.headers().allValues("X-Gimle-Warning"));
  }

  @Test
  @Timeout(10)
  void two_tenants_each_fronting_a_same_named_deployment_do_not_overlap() throws Exception {
    postService(
        """
        {"name": "web", "tenantId": "tenant-a", "deploymentNames": ["orders-service"], "port": 80}
        """);

    HttpResponse<String> other =
        postService(
            """
            {"name": "web2", "tenantId": "tenant-b", "deploymentNames": ["orders-service"],
             "port": 80}
            """);

    assertEquals(List.of(), other.headers().allValues("X-Gimle-Warning"));
  }

  @Test
  @Timeout(10)
  void a_target_port_no_backing_instance_reports_is_admitted_with_a_warning() throws Exception {
    recordReadyInstance("orders-service", Map.of("HTTP_PORT", 51234));

    HttpResponse<String> post =
        postService(
            """
            {"name": "orders", "deploymentNames": ["orders-service"], "port": 8080,
             "targetPort": 9090}
            """);

    assertEquals(200, post.statusCode(), "an unreported targetPort is level-triggered, not a 400");
    List<String> warnings = post.headers().allValues("X-Gimle-Warning");
    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(warnings.get(0).contains("9090"), warnings.get(0));
    assertTrue(warnings.get(0).contains("51234"), warnings.get(0));
  }

  @Test
  @Timeout(10)
  void a_target_port_a_backing_instance_does_report_earns_no_warning() throws Exception {
    recordReadyInstance("orders-service", Map.of("HTTP_PORT", 51234, "ADMIN_PORT", 51235));

    HttpResponse<String> post =
        postService(
            """
            {"name": "orders", "deploymentNames": ["orders-service"], "port": 8080,
             "targetPort": 51235}
            """);

    assertEquals(List.of(), post.headers().allValues("X-Gimle-Warning"));
  }

  @Test
  @Timeout(10)
  void a_service_declared_before_any_backing_instance_exists_earns_no_target_port_warning()
      throws Exception {
    HttpResponse<String> post =
        postService(
            """
            {"name": "orders", "deploymentNames": ["orders-service"], "port": 8080,
             "targetPort": 9090}
            """);

    assertEquals(List.of(), post.headers().allValues("X-Gimle-Warning"));
  }

  /**
   * The whole point of making {@code targetPort} authoritative: a multi-port instance used to
   * contribute nothing at all (no single port to pick), and now contributes exactly the declared
   * one.
   */
  @Test
  @Timeout(10)
  void a_declared_target_port_picks_that_port_out_of_a_multi_port_instance() throws Exception {
    recordReadyInstance("orders-service", Map.of("HTTP_PORT", 51234, "ADMIN_PORT", 51235));
    postService(
        """
        {"name": "orders", "deploymentNames": ["orders-service"], "port": 8080,
         "targetPort": 51235}
        """);

    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/services/orders/endpoints"))
                .GET()
                .build());

    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    assertEquals(51235L, body.get("targetPort"));
    List<Map<String, Object>> endpoints = Json.asObjectList(body.get("endpoints"));
    assertEquals(1, endpoints.size());
    assertEquals(51235L, endpoints.get(0).get("port"));
  }

  @Test
  @Timeout(10)
  void an_instance_not_reporting_the_declared_target_port_contributes_no_endpoint()
      throws Exception {
    recordReadyInstance("orders-service", Map.of("HTTP_PORT", 51234));
    postService(
        """
        {"name": "orders", "deploymentNames": ["orders-service"], "port": 8080,
         "targetPort": 9090}
        """);

    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/services/orders/endpoints"))
                .GET()
                .build());

    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    assertEquals(List.of(), Json.asObjectList(body.get("endpoints")));
  }

  /**
   * A tenant-scoped Service is keyed by {@code (tenant, name)}, but both of the gateway's endpoint
   * caches address their target by bare name -- {@code VesselEndpointCache} through {@code
   * /endpoints/{name}}, {@code ServiceEndpointCache} through this route. Without the same
   * resolve-the-tenant-from-the-name fallback {@code /endpoints/{name}} already has, every
   * tenant-scoped Service a gateway SERVICE route names answered 404, and Skald cached nothing for
   * it -- a silent NXDOMAIN for the whole zone.
   */
  @Test
  @Timeout(10)
  void a_tenant_scoped_service_resolves_its_endpoints_from_the_bare_name() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"name": "orders", "tenantId": "acme",
                     "deploymentNames": ["orders-service"], "port": 8080}
                    """))
            .build());

    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/services/orders/endpoints"))
                .GET()
                .build());

    assertEquals(200, response.statusCode());
    assertEquals("orders", Json.asObject(Json.parse(response.body())).get("name"));
  }

  /** An explicit {@code ?tenant=} still wins over the fallback, and a wrong one still 404s. */
  @Test
  @Timeout(10)
  void an_explicit_tenant_still_decides_which_service_is_addressed() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"name": "orders", "tenantId": "acme",
                     "deploymentNames": ["orders-service"], "port": 8080}
                    """))
            .build());

    assertEquals(
        200,
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/services/orders/endpoints?tenant=acme"))
                .GET()
                .build())
            .statusCode());
    assertEquals(
        404,
        send(HttpRequest.newBuilder(
                    URI.create(baseUrl + "/services/orders/endpoints?tenant=globex"))
                .GET()
                .build())
            .statusCode());
  }

  /**
   * QA finding: a long-lived Service's own {@code /endpoints} sub-route started 404ing "no such
   * service" while {@code GET /services} kept listing it fine -- both read the identical store, but
   * the bare-name fallback ({@code resolveTenantForServiceName}) used to pick whichever tenant's
   * same-named Service happened to iterate first out of an unordered collection, silently and
   * inconsistently. Two tenants genuinely sharing a name must now surface a clear 400 asking for
   * {@code ?tenant=}, never a coin-flip between "found" and "not found" for the exact same request.
   */
  @Test
  @Timeout(10)
  void an_ambiguous_service_name_across_two_tenants_is_a_400_not_a_flaky_404() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"name": "orders", "tenantId": "acme",
                     "deploymentNames": ["orders-service"], "port": 8080}
                    """))
            .build());
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/services"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"name": "orders", "tenantId": "globex",
                     "deploymentNames": ["orders-service"], "port": 8080}
                    """))
            .build());

    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/services/orders/endpoints"))
                .GET()
                .build());

    assertEquals(400, response.statusCode());
    assertTrue(response.body().contains("ambiguous"), response.body());
  }
}
