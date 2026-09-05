package com.gimle.ivaldi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import com.gimle.ivaldi.blueprint.BlueprintStore;
import com.gimle.ivaldi.cluster.ClusterStore;
import com.gimle.ivaldi.run.RunController;
import java.io.IOException;
import java.net.InetAddress;
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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real inbound HTTP traffic against a real {@link IvaldiServer} on an ephemeral loopback port:
 * blueprint CRUD, the tier-2 validate endpoint against real rendered YAML, and shutdown -- the same
 * "spin up the real server, hit it over HTTP" posture {@code SagaServerTest} already establishes.
 */
class IvaldiServerTest {

  @TempDir Path tempDir;

  private IvaldiServer server;
  private final HttpClient client = HttpClient.newHttpClient();
  private String baseUrl;

  @BeforeEach
  void setUp() throws Exception {
    BlueprintStore store = new BlueprintStore(tempDir.resolve("blueprints"));
    ClusterStore clusters = new ClusterStore(tempDir.resolve("clusters"));
    RunController runs = new RunController(clusters, tempDir);
    server = new IvaldiServer(store, clusters, runs, InetAddress.getLoopbackAddress(), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.port();
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> put(String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> delete(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path)).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @Timeout(10)
  void health_answers_ok() throws Exception {
    HttpResponse<String> response = get("/api/health");

    assertEquals(200, response.statusCode());
    assertEquals("ok", Json.asObject(Json.parse(response.body())).get("status"));
  }

  @Test
  @Timeout(10)
  void creates_lists_reads_and_deletes_a_blueprint() throws Exception {
    HttpResponse<String> created =
        post("/api/blueprints", "{\"name\":\"orders-platform-local\",\"nodes\":[],\"edges\":[]}");
    assertEquals(201, created.statusCode());
    String id = String.valueOf(Json.asObject(Json.parse(created.body())).get("id"));
    assertEquals("orders-platform-local", id);

    HttpResponse<String> listed = get("/api/blueprints");
    assertEquals(200, listed.statusCode());
    List<Object> summaries = Json.asArray(Json.parse(listed.body()));
    assertEquals(1, summaries.size());

    HttpResponse<String> fetched = get("/api/blueprints/" + id);
    assertEquals(200, fetched.statusCode());
    assertEquals(
        "{\"name\":\"orders-platform-local\",\"nodes\":[],\"edges\":[],\"id\":\"orders-platform-local\"}",
        fetched.body());

    HttpResponse<String> deleted = delete("/api/blueprints/" + id);
    assertEquals(200, deleted.statusCode());
    assertEquals(404, get("/api/blueprints/" + id).statusCode());
  }

  @Test
  @Timeout(10)
  void put_upserts_a_blueprint_at_an_explicit_id() throws Exception {
    HttpResponse<String> response =
        put("/api/blueprints/my-cluster", "{\"name\":\"first cut\",\"nodes\":[],\"edges\":[]}");

    assertEquals(200, response.statusCode());
    assertEquals("first cut", Json.asObject(Json.parse(response.body())).get("name"));
    assertEquals(
        "{\"name\":\"first cut\",\"nodes\":[],\"edges\":[],\"id\":\"my-cluster\"}",
        get("/api/blueprints/my-cluster").body());
  }

  @Test
  @Timeout(10)
  void get_of_an_unknown_blueprint_is_404() throws Exception {
    assertEquals(404, get("/api/blueprints/no-such-id").statusCode());
  }

  @Test
  @Timeout(10)
  void create_rejects_a_body_that_is_not_a_json_object() throws Exception {
    HttpResponse<String> response = post("/api/blueprints", "[1,2,3]");

    assertEquals(400, response.statusCode());
  }

  @Test
  @Timeout(10)
  void validate_runs_the_real_topology_validator_against_rendered_yaml() throws Exception {
    String topology =
        """
        name: local
        machines:
          - {name: local, host: 127.0.0.1}
        store:
          replicas:
            - {machine: local}
        controlPlane:
          replicas:
            - {machine: local}
        fafnir:
          keyFile: /tmp/fafnir.key
          replicas:
            - {machine: local}
        """;
    String body =
        Json.write(Map.of("files", List.of(Map.of("path", "topology.yaml", "content", topology))));

    HttpResponse<String> response = post("/api/validate", body);

    assertEquals(200, response.statusCode());
    Map<String, Object> result = Json.asObject(Json.parse(response.body()));
    List<Object> findings = Json.asArray(result.get("findings"));
    assertTrue(
        findings.stream().anyMatch(f -> "NO_AGENTS".equals(Json.asObject(f).get("code"))),
        "expected a NO_AGENTS finding, got: " + findings);
  }

  @Test
  @Timeout(10)
  void validate_rejects_a_request_body_shaped_wrong() throws Exception {
    HttpResponse<String> response = post("/api/validate", "{\"notFiles\":[]}");

    assertEquals(400, response.statusCode());
  }

  @Test
  @Timeout(10)
  void creates_lists_reads_and_deletes_a_cluster() throws Exception {
    HttpResponse<String> created =
        post(
            "/api/clusters",
            "{\"name\":\"local-dev\",\"controlPlaneUrl\":\"http://127.0.0.1:8080\"}");
    assertEquals(201, created.statusCode());
    String id = String.valueOf(Json.asObject(Json.parse(created.body())).get("id"));
    assertEquals("local-dev", id);

    HttpResponse<String> listed = get("/api/clusters");
    assertEquals(200, listed.statusCode());
    assertEquals(1, Json.asArray(Json.parse(listed.body())).size());

    HttpResponse<String> fetched = get("/api/clusters/" + id);
    assertEquals(200, fetched.statusCode());
    assertEquals(
        "http://127.0.0.1:8080", Json.asObject(Json.parse(fetched.body())).get("controlPlaneUrl"));

    HttpResponse<String> deleted = delete("/api/clusters/" + id);
    assertEquals(200, deleted.statusCode());
    assertEquals(404, get("/api/clusters/" + id).statusCode());
  }

  @Test
  @Timeout(10)
  void cluster_topology_is_null_until_a_run_records_one() throws Exception {
    HttpResponse<String> created =
        post("/api/clusters", "{\"name\":\"local\",\"controlPlaneUrl\":\"127.0.0.1:8080\"}");
    String id = String.valueOf(Json.asObject(Json.parse(created.body())).get("id"));

    HttpResponse<String> topology = get("/api/clusters/" + id + "/topology");

    assertEquals(200, topology.statusCode());
    assertNull(Json.asObject(Json.parse(topology.body())).get("topology"));
  }

  @Test
  @Timeout(10)
  void cluster_topology_of_an_unknown_cluster_is_404() throws Exception {
    assertEquals(404, get("/api/clusters/no-such-cluster/topology").statusCode());
  }

  /**
   * The health probe reports on the cluster's own control plane, not on Ivaldi. Answering from
   * Ivaldi's own health -- which is all the browser can reach, a control plane sending no CORS
   * headers -- told every operator their cluster was up, including the ones that were not.
   */
  @Test
  @Timeout(10)
  void cluster_health_reports_a_control_plane_that_is_not_answering() throws Exception {
    // Port 1 on loopback: privileged and unbound, so nothing can be listening there.
    HttpResponse<String> created =
        post("/api/clusters", "{\"name\":\"gone\",\"controlPlaneUrl\":\"127.0.0.1:1\"}");
    String id = String.valueOf(Json.asObject(Json.parse(created.body())).get("id"));

    HttpResponse<String> health = get("/api/clusters/" + id + "/health");

    assertEquals(200, health.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(health.body()));
    assertEquals(Boolean.FALSE, body.get("ok"));
    assertEquals("127.0.0.1:1", body.get("address"));
    assertNotNull(body.get("message"));
  }

  @Test
  @Timeout(10)
  void cluster_health_of_an_unknown_cluster_is_404() throws Exception {
    assertEquals(404, get("/api/clusters/no-such-cluster/health").statusCode());
  }

  @Test
  @Timeout(10)
  void runs_current_is_idle_when_nothing_has_ever_run() throws Exception {
    HttpResponse<String> response = get("/api/runs/current");

    assertEquals(200, response.statusCode());
    assertEquals("idle", Json.asObject(Json.parse(response.body())).get("status"));
  }

  @Test
  @Timeout(10)
  void stopping_with_no_run_at_all_is_404() throws Exception {
    assertEquals(404, delete("/api/runs/current").statusCode());
  }

  @Test
  @Timeout(10)
  void log_of_an_unknown_run_is_404() throws Exception {
    assertEquals(404, get("/api/runs/no-such-run/log").statusCode());
  }

  @Test
  @Timeout(10)
  void starting_a_run_against_an_unknown_cluster_is_404() throws Exception {
    String body =
        Json.write(
            Map.of(
                "clusterId",
                "no-such-cluster",
                "files",
                List.of(Map.of("path", "topology.yaml", "content", "name: t"))));

    HttpResponse<String> response = post("/api/runs", body);

    assertEquals(404, response.statusCode());
  }

  @Test
  @Timeout(10)
  void starting_a_run_rejects_a_body_shaped_wrong() throws Exception {
    assertEquals(400, post("/api/runs", "{\"files\":[]}").statusCode());
    assertEquals(400, post("/api/runs", "{\"clusterId\":\"x\"}").statusCode());
  }

  @Test
  @Timeout(10)
  void shutdown_acknowledges_the_request_and_then_stops_the_server() throws Exception {
    HttpResponse<String> response = post("/api/shutdown", "");
    assertEquals(200, response.statusCode());

    boolean stopped = false;
    while (!stopped) {
      try {
        get("/api/health");
        Thread.sleep(20);
      } catch (IOException expected) {
        stopped = true;
      }
    }
  }
}
