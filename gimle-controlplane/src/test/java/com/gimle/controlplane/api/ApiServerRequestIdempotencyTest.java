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
 * A real {@link ApiServer} over loopback, driving the keyed writes end to end: a repeat of a
 * request that already completed is answered from its recorded outcome instead of executing a
 * second time, a request carrying no id behaves exactly as it always did, and an unusable id is
 * refused before anything is written.
 */
class ApiServerRequestIdempotencyTest {

  private static final String REQUEST_ID_HEADER = "X-Gimle-Request-Id";
  private static final String REPLAYED_HEADER = "X-Gimle-Replayed";

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

  private static String deploymentYaml(String name, String version) {
    return """
        kind: Deployment
        name: %s
        module:
          name: com.gimle.example.orders
          version: %s
        artifactPath: /var/gimle/artifacts/orders-%s.jar
        replicas: 1
        """
        .formatted(name, version, version);
  }

  private HttpResponse<String> putDeployment(String name, String version, String requestId)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name))
            .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml(name, version)));
    if (requestId != null) {
      builder.header(REQUEST_ID_HEADER, requestId);
    }
    return send(builder.build());
  }

  private HttpResponse<String> rollback(String name, String requestId) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name + "/rollback"))
            .POST(HttpRequest.BodyPublishers.ofString(""));
    if (requestId != null) {
      builder.header(REQUEST_ID_HEADER, requestId);
    }
    return send(builder.build());
  }

  private List<Map<String, Object>> revisions(String name) throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name + "/revisions"))
                .GET()
                .build());
    assertEquals(200, response.statusCode());
    return Json.asObjectList(Json.asObject(Json.parse(response.body())).get("revisions"));
  }

  private String deployedVersion(String name) throws Exception {
    HttpResponse<String> response =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name)).GET().build());
    assertEquals(200, response.statusCode());
    Map<String, Object> spec =
        Json.asObject(Json.asObject(Json.parse(response.body())).get("spec"));
    return String.valueOf(Json.asObject(spec.get("moduleId")).get("version"));
  }

  @Test
  void a_replayed_rollback_creates_exactly_one_revision_not_two() throws Exception {
    assertEquals(200, putDeployment("orders", "1.0.0", null).statusCode());
    assertEquals(200, putDeployment("orders", "2.0.0", null).statusCode());
    assertEquals(2, revisions("orders").size());

    HttpResponse<String> first = rollback("orders", "req-rollback-01");
    assertEquals(200, first.statusCode());
    assertTrue(first.headers().firstValue(REPLAYED_HEADER).isEmpty());
    assertEquals(3, revisions("orders").size());

    HttpResponse<String> replay = rollback("orders", "req-rollback-01");

    assertEquals(200, replay.statusCode());
    assertEquals(3, revisions("orders").size(), "the retry must not mint a second revision");
    assertEquals("true", replay.headers().firstValue(REPLAYED_HEADER).orElse(null));
    assertEquals(first.body(), replay.body());
  }

  /**
   * The same rollback sent twice with no request id at all: unchanged behaviour, two revisions --
   * proof that nothing is forced to adopt the mechanism and that the test above is measuring the
   * receipt rather than some accidental deduplication elsewhere.
   */
  @Test
  void an_absent_request_id_header_leaves_the_repeated_rollback_creating_two_revisions()
      throws Exception {
    assertEquals(200, putDeployment("orders", "1.0.0", null).statusCode());
    assertEquals(200, putDeployment("orders", "2.0.0", null).statusCode());

    assertEquals(200, rollback("orders", null).statusCode());
    assertEquals(200, rollback("orders", null).statusCode());

    assertEquals(4, revisions("orders").size());
  }

  /**
   * The generation-guarded apply. The write really did land the first time, so the retry must be
   * answered with that same 200 and must not run again -- here made observable by changing the
   * deployment in between: a re-executed retry would put its own older content back, and a false
   * conflict would report a failure for a write that succeeded.
   */
  @Test
  void a_replayed_generation_guarded_put_returns_the_original_outcome_and_does_not_re_apply()
      throws Exception {
    HttpResponse<String> first = putDeployment("orders", "1.0.0", "req-apply-0001");
    assertEquals(200, first.statusCode());
    assertEquals("1.0.0", deployedVersion("orders"));

    assertEquals(200, putDeployment("orders", "2.0.0", null).statusCode());
    assertEquals("2.0.0", deployedVersion("orders"));

    HttpResponse<String> replay = putDeployment("orders", "1.0.0", "req-apply-0001");

    assertEquals(200, replay.statusCode());
    assertEquals("2.0.0", deployedVersion("orders"), "the retry must not re-apply its own content");
    assertEquals("true", replay.headers().firstValue(REPLAYED_HEADER).orElse(null));
    assertEquals(first.body(), replay.body());
  }

  @Test
  void a_malformed_request_id_is_rejected_and_the_write_never_runs() throws Exception {
    assertEquals(200, putDeployment("orders", "1.0.0", null).statusCode());
    assertEquals(200, putDeployment("orders", "2.0.0", null).statusCode());
    int before = revisions("orders").size();

    HttpResponse<String> tooShort = rollback("orders", "abc");
    assertEquals(400, tooShort.statusCode());
    assertTrue(tooShort.body().contains(REQUEST_ID_HEADER), tooShort.body());

    HttpResponse<String> illegalCharacter = rollback("orders", "req rollback/01");
    assertEquals(400, illegalCharacter.statusCode());

    assertEquals(before, revisions("orders").size());
  }

  /** A DaemonSet rollback is the same forward-only shape, keyed the same way. */
  @Test
  void a_replayed_daemonset_rollback_creates_exactly_one_revision_not_two() throws Exception {
    assertEquals(200, putDaemonSet("agents", "1.0.0").statusCode());
    assertEquals(200, putDaemonSet("agents", "2.0.0").statusCode());

    HttpResponse<String> first = rollbackDaemonSet("agents", "req-ds-rollback1");
    assertEquals(200, first.statusCode());
    assertEquals(3, daemonSetRevisions("agents").size());

    HttpResponse<String> replay = rollbackDaemonSet("agents", "req-ds-rollback1");

    assertEquals(200, replay.statusCode());
    assertEquals(
        3, daemonSetRevisions("agents").size(), "the retry must not mint a second revision");
    assertEquals("true", replay.headers().firstValue(REPLAYED_HEADER).orElse(null));
    assertEquals(first.body(), replay.body());
  }

  @Test
  void a_replayed_config_rollback_mints_exactly_one_new_version() throws Exception {
    assertEquals(200, putConfig("acme", "log-level", "info").statusCode());
    assertEquals(200, putConfig("acme", "log-level", "debug").statusCode());

    HttpResponse<String> first = rollbackConfig("acme", "log-level", 1, "req-config-roll1");
    assertEquals(200, first.statusCode());
    assertEquals(3, configVersions("acme", "log-level").size());

    HttpResponse<String> replay = rollbackConfig("acme", "log-level", 1, "req-config-roll1");

    assertEquals(200, replay.statusCode());
    assertEquals(
        3, configVersions("acme", "log-level").size(), "the retry must not mint a second version");
    assertEquals("true", replay.headers().firstValue(REPLAYED_HEADER).orElse(null));
    assertEquals(first.body(), replay.body());
  }

  /**
   * A receipt is filed under the principal that produced it and re-checked on replay. Here both
   * requests are anonymous (plaintext transport carries no identity), so the replay must hit -- the
   * mismatch case needs real client identities and lives in {@link
   * ApiServerRequestIdempotencyAuthzTest}.
   */
  @Test
  void a_replay_by_the_same_principal_hits_the_receipt() throws Exception {
    assertEquals(200, putDeployment("orders", "1.0.0", null).statusCode());
    assertEquals(200, putDeployment("orders", "2.0.0", null).statusCode());
    assertEquals(200, rollback("orders", "req-samecaller1").statusCode());

    assertFalse(
        rollback("orders", "req-samecaller1").headers().firstValue(REPLAYED_HEADER).isEmpty());
  }

  private static String daemonSetYaml(String name, String version) {
    return """
        kind: DaemonSet
        name: %s
        module:
          name: com.gimle.example.agents
          version: %s
        artifactPath: /var/gimle/artifacts/agents-%s.jar
        """
        .formatted(name, version, version);
  }

  private HttpResponse<String> putDaemonSet(String name, String version) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets/" + name))
            .PUT(HttpRequest.BodyPublishers.ofString(daemonSetYaml(name, version)))
            .build());
  }

  private HttpResponse<String> rollbackDaemonSet(String name, String requestId) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets/" + name + "/rollback"))
            .header(REQUEST_ID_HEADER, requestId)
            .POST(HttpRequest.BodyPublishers.ofString(""))
            .build());
  }

  private List<Map<String, Object>> daemonSetRevisions(String name) throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/daemonsets/" + name + "/revisions"))
                .GET()
                .build());
    assertEquals(200, response.statusCode());
    return Json.asObjectList(Json.asObject(Json.parse(response.body())).get("revisions"));
  }

  private HttpResponse<String> putConfig(String tenantId, String key, String value)
      throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/config/" + tenantId + "/" + key))
            .PUT(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("value", value))))
            .build());
  }

  private HttpResponse<String> rollbackConfig(
      String tenantId, String key, int version, String requestId) throws Exception {
    return send(
        HttpRequest.newBuilder(
                URI.create(baseUrl + "/config/" + tenantId + "/" + key + "/rollback"))
            .header(REQUEST_ID_HEADER, requestId)
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("version", version))))
            .build());
  }

  private List<Map<String, Object>> configVersions(String tenantId, String key) throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/config/" + tenantId + "/" + key + "/versions"))
                .GET()
                .build());
    assertEquals(200, response.statusCode());
    return Json.asObjectList(Json.asObject(Json.parse(response.body())).get("versions"));
  }
}
