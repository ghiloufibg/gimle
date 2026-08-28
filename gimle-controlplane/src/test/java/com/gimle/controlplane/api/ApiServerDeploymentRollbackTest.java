package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real {@link ApiServer} + real {@code java.net.http.HttpClient} on a loopback ephemeral port --
 * the same harness {@link ApiServerTest} already establishes -- proving {@code
 * /deployments/{name}/revisions} and {@code /deployments/{name}/rollback} end to end: revision
 * minting on content change, rollback restoring an earlier revision as a brand-new one, and the
 * 404/409 failure paths.
 */
class ApiServerDeploymentRollbackTest {

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

  private static String deploymentYaml(String name, String version, int replicas) {
    return """
        kind: Deployment
        name: %s
        module:
          name: com.gimle.example.orders
          version: %s
        artifactPath: /var/gimle/artifacts/orders-%s.jar
        replicas: %d
        """
        .formatted(name, version, version, replicas);
  }

  private HttpResponse<String> putDeployment(String name, String version, int replicas)
      throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name))
            .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml(name, version, replicas)))
            .build());
  }

  private HttpResponse<String> getRevisions(String name) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name + "/revisions"))
            .GET()
            .build());
  }

  private HttpResponse<String> deleteDeployment(String name) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name)).DELETE().build());
  }

  private HttpResponse<String> rollback(String name, String body) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name + "/rollback"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build());
  }

  @Test
  void the_first_apply_mints_revision_one() throws Exception {
    assertEquals(200, putDeployment("orders-service", "1.0.0", 1).statusCode());

    HttpResponse<String> revisions = getRevisions("orders-service");
    assertEquals(200, revisions.statusCode());
    List<Map<String, Object>> values =
        Json.asObjectList(Json.asObject(Json.parse(revisions.body())).get("revisions"));
    assertEquals(1, values.size());
    assertEquals(1L, values.get(0).get("revision"));
    assertFalse(values.get(0).containsKey("rollbackOfRevision"));
  }

  @Test
  void a_replica_only_change_mints_no_new_revision() throws Exception {
    assertEquals(200, putDeployment("orders-service", "1.0.0", 1).statusCode());
    assertEquals(200, putDeployment("orders-service", "1.0.0", 5).statusCode());

    List<Map<String, Object>> values =
        Json.asObjectList(
            Json.asObject(Json.parse(getRevisions("orders-service").body())).get("revisions"));
    assertEquals(1, values.size());
  }

  @Test
  void a_module_version_change_mints_a_new_revision() throws Exception {
    assertEquals(200, putDeployment("orders-service", "1.0.0", 1).statusCode());
    assertEquals(200, putDeployment("orders-service", "1.1.0", 1).statusCode());

    List<Map<String, Object>> values =
        Json.asObjectList(
            Json.asObject(Json.parse(getRevisions("orders-service").body())).get("revisions"));
    // Newest-first.
    assertEquals(2, values.size());
    assertEquals(2L, values.get(0).get("revision"));
    assertEquals(1L, values.get(1).get("revision"));
  }

  @Test
  void deleting_then_recreating_a_deployment_starts_revision_history_fresh() throws Exception {
    putDeployment("orders-service", "1.0.0", 1);
    putDeployment("orders-service", "1.1.0", 1); // revision 2

    assertEquals(200, deleteDeployment("orders-service").statusCode());
    assertEquals(200, putDeployment("orders-service", "2.0.0", 1).statusCode());

    List<Map<String, Object>> values =
        Json.asObjectList(
            Json.asObject(Json.parse(getRevisions("orders-service").body())).get("revisions"));
    // The new Deployment's own first revision, numbered 1 again -- not a continuation of the
    // deleted Deployment's history, and rolling back to the old revision 2 is no longer possible.
    assertEquals(1, values.size());
    assertEquals(1L, values.get(0).get("revision"));

    assertEquals(404, rollback("orders-service", Json.write(Map.of("toRevision", 2))).statusCode());
  }

  @Test
  void rolling_back_with_no_explicit_revision_restores_the_previous_one() throws Exception {
    putDeployment("orders-service", "1.0.0", 1);
    putDeployment("orders-service", "1.1.0", 1);

    HttpResponse<String> rolledBack = rollback("orders-service", "");
    assertEquals(200, rolledBack.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(rolledBack.body()));
    assertEquals(3L, body.get("revision"));
    assertEquals(1L, body.get("rollbackOfRevision"));

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .GET()
                .build());
    Map<String, Object> spec = Json.asObject(Json.asObject(Json.parse(get.body())).get("spec"));
    Map<String, Object> moduleId = Json.asObject(spec.get("moduleId"));
    assertEquals("1.0.0", moduleId.get("version"));
  }

  @Test
  void rolling_back_to_an_explicit_revision_restores_that_one() throws Exception {
    putDeployment("orders-service", "1.0.0", 1);
    putDeployment("orders-service", "1.1.0", 1);
    putDeployment("orders-service", "1.2.0", 1);

    HttpResponse<String> rolledBack =
        rollback("orders-service", Json.write(Map.of("toRevision", 1)));
    assertEquals(200, rolledBack.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(rolledBack.body()));
    assertEquals(4L, body.get("revision"));
    assertEquals(1L, body.get("rollbackOfRevision"));

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/orders-service"))
                .GET()
                .build());
    Map<String, Object> spec = Json.asObject(Json.asObject(Json.parse(get.body())).get("spec"));
    assertEquals("1.0.0", Json.asObject(spec.get("moduleId")).get("version"));
  }

  @Test
  void rollback_is_forward_only_and_appends_yet_another_revision_not_a_third_copy()
      throws Exception {
    putDeployment("orders-service", "1.0.0", 1);
    putDeployment("orders-service", "1.1.0", 1);
    rollback("orders-service", "");

    List<Map<String, Object>> values =
        Json.asObjectList(
            Json.asObject(Json.parse(getRevisions("orders-service").body())).get("revisions"));
    assertEquals(3, values.size());
    assertEquals(3L, values.get(0).get("revision"));
  }

  @Test
  void rollback_of_an_unknown_deployment_is_404() throws Exception {
    assertEquals(404, rollback("never-deployed", "").statusCode());
  }

  @Test
  void rollback_with_no_earlier_revision_is_404() throws Exception {
    putDeployment("orders-service", "1.0.0", 1);

    assertEquals(404, rollback("orders-service", "").statusCode());
  }

  @Test
  void rollback_to_an_unknown_revision_number_is_404() throws Exception {
    putDeployment("orders-service", "1.0.0", 1);
    putDeployment("orders-service", "1.1.0", 1);

    assertEquals(
        404, rollback("orders-service", Json.write(Map.of("toRevision", 99))).statusCode());
  }

  @Test
  void revisions_of_an_unknown_deployment_is_an_empty_list_not_an_error() throws Exception {
    HttpResponse<String> revisions = getRevisions("never-deployed");
    assertEquals(200, revisions.statusCode());
    List<Map<String, Object>> values =
        Json.asObjectList(Json.asObject(Json.parse(revisions.body())).get("revisions"));
    assertTrue(values.isEmpty());
  }
}
