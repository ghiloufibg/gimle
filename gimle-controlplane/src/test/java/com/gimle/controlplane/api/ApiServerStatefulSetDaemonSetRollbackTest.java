package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * {@code /statefulsets/{name}/revisions}/{@code /rollback} and {@code
 * /daemonsets/{name}/revisions}/{@code /rollback} -- the same mechanism {@link
 * ApiServerDeploymentRollbackTest} proves for Deployment, exercised here for the two kinds whose
 * PUT handlers have no {@code AdmissionChain} to re-run on rollback (see {@code
 * ApiServer#handlePutStatefulSet}/{@code #handlePutDaemonSet}'s own "No tenant-quota check here"
 * comments) -- rollback re-validation there is artifact resolution only.
 */
class ApiServerStatefulSetDaemonSetRollbackTest {

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

  private static String statefulSetYaml(String name, String version) {
    return """
        kind: StatefulSet
        name: %s
        module:
          name: com.gimle.example.orders
          version: %s
        artifactPath: /var/gimle/artifacts/orders-%s.jar
        replicas: 3
        """
        .formatted(name, version, version);
  }

  private static String daemonSetYaml(String name, String version) {
    return """
        kind: DaemonSet
        name: %s
        module:
          name: com.gimle.example.node-exporter
          version: %s
        artifactPath: /var/gimle/artifacts/node-exporter-%s.jar
        placement:
          requiredLabels: [gpu]
        """
        .formatted(name, version, version);
  }

  private HttpResponse<String> put(String path, String yaml) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .PUT(HttpRequest.BodyPublishers.ofString(yaml))
            .build());
  }

  private HttpResponse<String> getRevisions(String path) throws Exception {
    return send(HttpRequest.newBuilder(URI.create(baseUrl + path + "/revisions")).GET().build());
  }

  private HttpResponse<String> rollback(String path, String body) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + path + "/rollback"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build());
  }

  @Test
  void a_statefulset_module_version_change_mints_a_new_revision() throws Exception {
    assertEquals(200, put("/statefulsets/orders", statefulSetYaml("orders", "1.0.0")).statusCode());
    assertEquals(200, put("/statefulsets/orders", statefulSetYaml("orders", "1.1.0")).statusCode());

    List<Map<String, Object>> values =
        Json.asObjectList(
            Json.asObject(Json.parse(getRevisions("/statefulsets/orders").body()))
                .get("revisions"));
    assertEquals(2, values.size());
    assertEquals(2L, values.get(0).get("revision"));
  }

  @Test
  void rolling_back_a_statefulset_restores_the_previous_module_version() throws Exception {
    put("/statefulsets/orders", statefulSetYaml("orders", "1.0.0"));
    put("/statefulsets/orders", statefulSetYaml("orders", "1.1.0"));

    HttpResponse<String> rolledBack = rollback("/statefulsets/orders", "");
    assertEquals(200, rolledBack.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(rolledBack.body()));
    assertEquals(3L, body.get("revision"));
    assertEquals(1L, body.get("rollbackOfRevision"));

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/statefulsets/orders")).GET().build());
    Map<String, Object> spec = Json.asObject(Json.asObject(Json.parse(get.body())).get("spec"));
    assertEquals("1.0.0", Json.asObject(spec.get("moduleId")).get("version"));
  }

  @Test
  void rollback_of_an_unknown_statefulset_is_404() throws Exception {
    assertEquals(404, rollback("/statefulsets/never-deployed", "").statusCode());
  }

  @Test
  void a_daemonset_module_version_change_mints_a_new_revision() throws Exception {
    assertEquals(
        200,
        put("/daemonsets/node-exporter", daemonSetYaml("node-exporter", "1.0.0")).statusCode());
    assertEquals(
        200,
        put("/daemonsets/node-exporter", daemonSetYaml("node-exporter", "1.1.0")).statusCode());

    List<Map<String, Object>> values =
        Json.asObjectList(
            Json.asObject(Json.parse(getRevisions("/daemonsets/node-exporter").body()))
                .get("revisions"));
    assertEquals(2, values.size());
    assertEquals(2L, values.get(0).get("revision"));
  }

  @Test
  void rolling_back_a_daemonset_restores_the_previous_module_version() throws Exception {
    put("/daemonsets/node-exporter", daemonSetYaml("node-exporter", "1.0.0"));
    put("/daemonsets/node-exporter", daemonSetYaml("node-exporter", "1.1.0"));

    HttpResponse<String> rolledBack = rollback("/daemonsets/node-exporter", "");
    assertEquals(200, rolledBack.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(rolledBack.body()));
    assertEquals(3L, body.get("revision"));
    assertEquals(1L, body.get("rollbackOfRevision"));

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets/node-exporter"))
                .GET()
                .build());
    Map<String, Object> spec = Json.asObject(Json.asObject(Json.parse(get.body())).get("spec"));
    assertEquals("1.0.0", Json.asObject(spec.get("moduleId")).get("version"));
  }

  @Test
  void rollback_of_an_unknown_daemonset_is_404() throws Exception {
    assertEquals(404, rollback("/daemonsets/never-deployed", "").statusCode());
  }
}
