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
    assertEquals(8080L, spec.get("targetPort"));
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
   * The contract every other lane depends on: exactly {@code name}/{@code port}/{@code
   * targetPort}/{@code endpoints}, each endpoint exactly {@code host}/{@code port}.
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
                    {"name": "orders", "deploymentNames": ["orders-service"], "port": 8080, "targetPort": 8080}
                    """))
            .build());
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutAssignment(
                new InstanceAssignment(
                    "orders-service", 0, "node-1", moduleId, "/artifacts/orders.jar")));
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
                    new InstanceObservation(
                        "orders-service",
                        0,
                        moduleId,
                        "ACTIVE",
                        true,
                        true,
                        0.0,
                        0,
                        0L,
                        0L,
                        0.0,
                        Map.of("HTTP_PORT", 51234)))));

    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/services/orders/endpoints"))
                .GET()
                .build());

    assertEquals(200, response.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    assertEquals("orders", body.get("name"));
    assertEquals(8080L, body.get("port"));
    assertEquals(8080L, body.get("targetPort"));
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
}
