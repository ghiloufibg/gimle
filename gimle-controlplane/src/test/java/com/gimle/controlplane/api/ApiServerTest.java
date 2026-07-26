package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.store.InstanceAssignment;
import com.gimle.controlplane.store.StateStore;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.protocol.Json;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeRegistration;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/** Exercises {@link ApiServer} over a real loopback HTTP connection, not a mocked handler. */
class ApiServerTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private StateStore store;
  private ApiServer server;
  private HttpClient client;
  private String baseUrl;

  @BeforeEach
  void start_server() throws IOException {
    store = new StateStore(tempDir.resolve("store"));
    server = new ApiServer(store, 0);
    server.start();
    baseUrl = "http://localhost:" + server.port();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stop_server() {
    server.close();
  }

  private HttpResponse<String> send(HttpRequest request) throws Exception {
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static String deployment_yaml(String name, int replicas) {
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
                .PUT(HttpRequest.BodyPublishers.ofString(deployment_yaml("orders-service", 3)))
                .build());
    assertEquals(200, put.statusCode());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .GET()
                .build());
    assertEquals(200, get.statusCode());
    @SuppressWarnings("unchecked")
    Map<String, Object> status = (Map<String, Object>) Json.parse(get.body());
    @SuppressWarnings("unchecked")
    Map<String, Object> spec = (Map<String, Object>) status.get("spec");
    assertEquals("orders-service", spec.get("name"));
    assertEquals(3L, spec.get("replicas"));
    assertEquals(3L, status.get("unplacedCount"));
  }

  @Test
  void put_with_a_manifest_name_mismatch_is_rejected() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/catalog-service"))
                .PUT(HttpRequest.BodyPublishers.ofString(deployment_yaml("orders-service", 1)))
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
            .PUT(HttpRequest.BodyPublishers.ofString(deployment_yaml("orders-service", 1)))
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
        store.get_node_registration("node-a").orElseThrow());

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

    var observed = store.get_node_heartbeat("node-a").orElseThrow();
    assertEquals(1000L, observed.heartbeat().capacity().totalMemoryBytes());
    assertEquals(1, observed.heartbeat().instances().size());
  }

  @Test
  void assignments_endpoint_joins_assignments_with_their_deployments_artifact_and_module_id()
      throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
            .PUT(HttpRequest.BodyPublishers.ofString(deployment_yaml("orders-service", 1)))
            .build());
    store.put_assignment(new InstanceAssignment("orders-service", 0, "node-a"));

    HttpResponse<String> assignments =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/assignments"))
                .GET()
                .build());

    assertEquals(200, assignments.statusCode());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> body = (List<Map<String, Object>>) Json.parse(assignments.body());
    assertEquals(1, body.size());
    Map<String, Object> instance = body.get(0);
    assertEquals("orders-service", instance.get("deploymentName"));
    assertEquals(0L, instance.get("instanceIndex"));
    assertEquals("/var/gimle/artifacts/orders-1.0.0.jar", instance.get("artifactPath"));
    @SuppressWarnings("unchecked")
    Map<String, Object> moduleId = (Map<String, Object>) instance.get("moduleId");
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
  void method_not_allowed_on_a_valid_path() throws Exception {
    HttpResponse<String> response =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/register")).GET().build());

    assertEquals(405, response.statusCode());
  }
}
