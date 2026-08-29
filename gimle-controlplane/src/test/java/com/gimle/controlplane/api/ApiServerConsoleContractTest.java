package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.protocol.Json;
import com.gimle.core.tenant.Tenant;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Scoped field-presence assertions against the exact JSON shapes the console's own {@code
 * src/types/} code expects -- not a re-test of everything {@link ApiServerTest} already covers,
 * just a schema-drift tripwire so a future change to these routes' JSON shapes is caught here
 * rather than silently breaking the console once it's wired in.
 */
// See ApiServerTest for why: real ApiServer + real HttpClient on a loopback ephemeral port,
// excluded from running concurrently with any other class doing the same.
@ResourceLock("gimle-controlplane-api-server-http")
// See ApiServerTest for why: ApiServer#start reads gimle.transport.protocol at construction
// time, so this class is exposed to it even though it never writes it itself.
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ApiServerConsoleContractTest {

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

  @Test
  void deployment_status_has_every_field_the_console_needs() throws Exception {
    String yaml =
        """
        kind: Deployment
        name: orders-service
        module:
          name: com.gimle.example.orders
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
        replicas: 2
        """;
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
            .PUT(HttpRequest.BodyPublishers.ofString(yaml))
            .build());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .GET()
                .build());
    Map<String, Object> status = Json.asObject(Json.parse(get.body()));
    assertTrue(status.containsKey("spec"));
    assertTrue(status.containsKey("instances"));
    assertTrue(status.containsKey("unplacedCount"));
    assertTrue(status.containsKey("quotaViolating"));
    assertTrue(status.containsKey("limitRangeViolating"));
    // limitRangeViolationReason is only present once the reconciler actually flags a violation --
    // see limitrange_violating_deployment_status_includes_the_violation_reason below for that
    // shape.

    Map<String, Object> spec = Json.asObject(status.get("spec"));
    assertTrue(spec.containsKey("name"));
    assertTrue(spec.containsKey("moduleId"));
    assertTrue(spec.containsKey("artifactPath"));
    assertTrue(spec.containsKey("replicas"));
    Map<String, Object> moduleId = Json.asObject(spec.get("moduleId"));
    assertTrue(moduleId.containsKey("name"));
    assertTrue(moduleId.containsKey("version"));

    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/deployments")).GET().build());
    List<Map<String, Object>> all = Json.asObjectList(Json.parse(list.body()));
    assertEquals(1, all.size());
    assertTrue(all.get(0).containsKey("spec"));
  }

  @Test
  void limitrange_violating_deployment_status_includes_the_violation_reason() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
            .PUT(
                HttpRequest.BodyPublishers.ofString(
                    """
                    kind: Deployment
                    name: orders-service
                    module:
                      name: com.gimle.example.orders
                      version: 1.0.0
                    artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
                    replicas: 2
                    """))
            .build());
    // A direct store backdoor, the same shortcut InProcessStore's own javadoc documents -- this
    // contract test only needs the field to be present once violating, not a real reconciler tick.
    inProcessStore
        .store()
        .putLimitRangeViolation(
            Optional.of(Tenant.DEFAULT_TENANT_ID),
            "orders-service",
            "request memory 16Mi below minimum 32Mi");

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .GET()
                .build());
    Map<String, Object> status = Json.asObject(Json.parse(get.body()));
    assertEquals(Boolean.TRUE, status.get("limitRangeViolating"));
    assertEquals("request memory 16Mi below minimum 32Mi", status.get("limitRangeViolationReason"));
  }

  @Test
  void node_list_entry_has_every_field_the_console_needs() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/register"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"capabilities\":{\"supportedTiers\":[\"TIER_1\"]}}"))
            .build());
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/heartbeat"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"capacity":{"totalMemoryBytes":1,"assignedMemoryBytes":2,\
                    "totalCpuMillicores":3,"assignedCpuMillicores":4},"instances":[]}
                    """))
            .build());

    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/nodes")).GET().build());
    List<Map<String, Object>> nodes = Json.asObjectList(Json.parse(list.body()));
    assertEquals(1, nodes.size());
    Map<String, Object> node = nodes.get(0);
    assertTrue(node.containsKey("nodeId"));
    assertTrue(node.containsKey("capabilities"));
    Map<String, Object> capabilities = Json.asObject(node.get("capabilities"));
    assertTrue(capabilities.containsKey("supportedTiers"));
    assertTrue(node.containsKey("lastHeartbeatAt"));

    assertTrue(node.containsKey("capacity"));
    Map<String, Object> capacity = Json.asObject(node.get("capacity"));
    assertTrue(capacity.containsKey("totalMemoryBytes"));
    assertTrue(capacity.containsKey("assignedMemoryBytes"));
    assertTrue(capacity.containsKey("totalCpuMillicores"));
    assertTrue(capacity.containsKey("assignedCpuMillicores"));
  }

  @Test
  void tenant_has_every_field_the_console_needs() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/acme"))
            .PUT(
                HttpRequest.BodyPublishers.ofString(
                    "{\"quota\":{\"maxMemoryBytes\":1,\"maxCpuMillicores\":2,\"maxInstances\":3}}"))
            .build());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/acme")).GET().build());
    Map<String, Object> tenant = Json.asObject(Json.parse(get.body()));
    assertTrue(tenant.containsKey("id"));
    assertTrue(tenant.containsKey("quota"));
    Map<String, Object> quota = Json.asObject(tenant.get("quota"));
    assertTrue(quota.containsKey("maxMemoryBytes"));
    assertTrue(quota.containsKey("maxCpuMillicores"));
    assertTrue(quota.containsKey("maxInstances"));
  }

  @Test
  void config_entry_has_every_field_the_console_needs() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/config/acme/db.password"))
            .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":\"secret\",\"encrypted\":true}"))
            .build());

    HttpResponse<String> list =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/config/acme")).GET().build());
    List<Map<String, Object>> entries = Json.asObjectList(Json.parse(list.body()));
    assertEquals(1, entries.size());
    Map<String, Object> entry = entries.get(0);
    assertTrue(entry.containsKey("key"));
    assertTrue(entry.containsKey("value"));
    assertTrue(entry.containsKey("encrypted"));
  }
}
