package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * {@code GET}/{@code PUT}/{@code PATCH}/{@code DELETE /configmaps/*} (plus the batch {@code
 * ?names=} shape) over a real loopback HTTP connection, the same style {@code
 * ApiServerNetworkPoliciesTest} established. RBAC coverage for {@link
 * com.gimle.core.authz.ResourceKind#CONFIGMAP} lives separately in {@code
 * ApiServerConfigMapAuthzTest}, since plaintext requests skip authorization entirely.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerConfigMapTest {

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

  private HttpResponse<String> put(String path, String body) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build());
  }

  private HttpResponse<String> patch(String path, String body) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
            .build());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build());
  }

  @Test
  @Timeout(10)
  void put_then_get_a_configmap_round_trips() throws Exception {
    HttpResponse<String> putResponse =
        put(
            "/configmaps/acme/app-config",
            """
            {"data": {"log.level": "INFO"}}
            """);
    assertEquals(200, putResponse.statusCode());
    assertEquals(1L, Json.asObject(Json.parse(putResponse.body())).get("version"));

    HttpResponse<String> getResponse = get("/configmaps/acme/app-config");
    assertEquals(200, getResponse.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(getResponse.body()));
    assertEquals("app-config", body.get("name"));
    assertEquals(1L, body.get("version"));
    assertEquals(Map.of("log.level", "INFO"), body.get("data"));
  }

  @Test
  @Timeout(10)
  void get_of_an_unknown_configmap_is_404() throws Exception {
    assertEquals(404, get("/configmaps/acme/nope").statusCode());
  }

  @Test
  @Timeout(10)
  void an_unconditional_put_replaces_the_full_data_set() throws Exception {
    put(
        "/configmaps/acme/app-config",
        """
        {"data": {"a": "1", "b": "2"}}
        """);

    put(
        "/configmaps/acme/app-config",
        """
        {"data": {"c": "3"}}
        """);

    Map<String, Object> body = Json.asObject(Json.parse(get("/configmaps/acme/app-config").body()));
    assertEquals(Map.of("c", "3"), body.get("data"));
  }

  @Test
  @Timeout(10)
  void patch_merges_only_the_sent_keys() throws Exception {
    put(
        "/configmaps/acme/app-config",
        """
        {"data": {"a": "1", "b": "2"}}
        """);

    HttpResponse<String> patchResponse =
        patch(
            "/configmaps/acme/app-config",
            """
            {"data": {"b": "20"}, "expectedVersion": 1}
            """);
    assertEquals(200, patchResponse.statusCode());

    Map<String, Object> body = Json.asObject(Json.parse(get("/configmaps/acme/app-config").body()));
    assertEquals(Map.of("a", "1", "b", "20"), body.get("data"));
  }

  @Test
  @Timeout(10)
  void patch_without_expected_version_is_a_400() throws Exception {
    HttpResponse<String> response =
        patch(
            "/configmaps/acme/app-config",
            """
            {"data": {"a": "1"}}
            """);
    assertEquals(400, response.statusCode());
  }

  @Test
  @Timeout(10)
  void a_stale_expected_version_on_put_is_a_409_carrying_the_current_snapshot() throws Exception {
    put(
        "/configmaps/acme/app-config",
        """
        {"data": {"a": "1"}}
        """);

    HttpResponse<String> response =
        put(
            "/configmaps/acme/app-config",
            """
            {"data": {"a": "2"}, "expectedVersion": 99}
            """);

    assertEquals(409, response.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    assertEquals(1L, body.get("currentVersion"));
    assertEquals(Map.of("a", "1"), body.get("currentData"));
  }

  @Test
  @Timeout(10)
  void delete_removes_a_configmap() throws Exception {
    put(
        "/configmaps/acme/app-config",
        """
        {"data": {"a": "1"}}
        """);

    HttpResponse<String> deleteResponse =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/configmaps/acme/app-config"))
                .DELETE()
                .build());
    assertEquals(200, deleteResponse.statusCode());

    assertEquals(404, get("/configmaps/acme/app-config").statusCode());
  }

  @Test
  @Timeout(10)
  void list_returns_every_name_for_the_tenant() throws Exception {
    put(
        "/configmaps/acme/a",
        """
        {"data": {"k": "1"}}
        """);
    put(
        "/configmaps/acme/b",
        """
        {"data": {"k": "2"}}
        """);

    HttpResponse<String> response = get("/configmaps/acme");
    assertEquals(200, response.statusCode());
    // Order isn't guaranteed -- the underlying ConfigEntry store is a plain map, same as every
    // other /config/*-backed list endpoint.
    assertEquals(Set.of("a", "b"), Set.copyOf(Json.asArray(Json.parse(response.body()))));
  }

  @Test
  @Timeout(10)
  void batch_get_via_names_query_returns_full_bodies_for_only_the_requested_names()
      throws Exception {
    put(
        "/configmaps/acme/a",
        """
        {"data": {"k": "1"}}
        """);
    put(
        "/configmaps/acme/b",
        """
        {"data": {"k": "2"}}
        """);
    put(
        "/configmaps/acme/c",
        """
        {"data": {"k": "3"}}
        """);

    HttpResponse<String> response = get("/configmaps/acme?names=a,c");

    assertEquals(200, response.statusCode());
    List<Object> body = Json.asArray(Json.parse(response.body()));
    assertEquals(2, body.size());
    assertTrue(
        body.stream()
            .map(Json::asObject)
            .map(m -> m.get("name"))
            .toList()
            .containsAll(List.of("a", "c")));
  }

  @Test
  @Timeout(10)
  void writing_a_configmap_prefixed_key_through_plain_config_is_rejected() throws Exception {
    HttpResponse<String> response =
        put(
            "/config/acme/configmap:app-config",
            """
            {"value": "sneaky", "encrypted": false}
            """);
    assertEquals(400, response.statusCode());
  }

  @Test
  @Timeout(10)
  void a_configmap_row_never_leaks_into_a_plain_config_listing() throws Exception {
    put(
        "/configmaps/acme/app-config",
        """
        {"data": {"a": "1"}}
        """);

    HttpResponse<String> response = get("/config/acme");
    assertEquals(200, response.statusCode());
    assertTrue(Json.asArray(Json.parse(response.body())).isEmpty());
  }
}
