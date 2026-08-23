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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * {@code GET}/{@code PUT}/{@code DELETE /secretmaps/*} over a real loopback HTTP connection,
 * proxied all the way through to a real {@link InProcessFafnir} replica -- the same style {@code
 * ApiServerConfigMapTest} established for its own kind. RBAC coverage for {@link
 * com.gimle.core.authz.ResourceKind#SECRETMAP} lives separately in {@code
 * ApiServerSecretMapAuthzTest}, since plaintext requests skip authorization entirely.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerSecretMapTest {

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

  private HttpResponse<String> get(String path) throws Exception {
    return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build());
  }

  private HttpResponse<String> delete(String path) throws Exception {
    return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).DELETE().build());
  }

  private static String encode(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String decode(String base64) {
    return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
  }

  @Test
  @Timeout(10)
  void put_then_get_metadata_round_trips_through_the_proxy_to_a_real_fafnir() throws Exception {
    HttpResponse<String> putResponse =
        put(
            "/secretmaps/acme/db-creds",
            Json.write(Map.of("data", Map.of("username", encode("admin")))));
    assertEquals(200, putResponse.statusCode());
    List<Object> results =
        Json.asArray(Json.asObject(Json.parse(putResponse.body())).get("results"));
    assertEquals(1, results.size());

    HttpResponse<String> getResponse = get("/secretmaps/acme/db-creds");
    assertEquals(200, getResponse.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(getResponse.body()));
    assertEquals("db-creds", body.get("name"));
    List<Object> keys = Json.asArray(body.get("keys"));
    assertEquals(1, keys.size());
    assertEquals("username", Json.asObject(keys.get(0)).get("key"));
  }

  @Test
  @Timeout(10)
  void names_query_returns_decrypted_values_through_the_proxy() throws Exception {
    put(
        "/secretmaps/acme/db-creds",
        Json.write(Map.of("data", Map.of("username", encode("admin")))));

    HttpResponse<String> response = get("/secretmaps/acme?names=db-creds");

    assertEquals(200, response.statusCode());
    Map<String, Object> secretMaps =
        Json.asObject(Json.asObject(Json.parse(response.body())).get("secretMaps"));
    Map<String, Object> data = Json.asObject(Json.asObject(secretMaps.get("db-creds")).get("data"));
    assertEquals("admin", decode((String) data.get("username")));
  }

  @Test
  @Timeout(10)
  void get_of_an_unknown_secretmap_returns_empty_metadata_not_an_error() throws Exception {
    HttpResponse<String> response = get("/secretmaps/acme/nope");
    assertEquals(200, response.statusCode());
    assertTrue(Json.asArray(Json.asObject(Json.parse(response.body())).get("keys")).isEmpty());
  }

  @Test
  @Timeout(10)
  void delete_removes_the_secretmap_through_the_proxy() throws Exception {
    put(
        "/secretmaps/acme/db-creds",
        Json.write(Map.of("data", Map.of("username", encode("admin")))));

    HttpResponse<String> deleteResponse = delete("/secretmaps/acme/db-creds");
    assertEquals(200, deleteResponse.statusCode());

    Map<String, Object> body = Json.asObject(Json.parse(get("/secretmaps/acme/db-creds").body()));
    List<Object> keys = Json.asArray(body.get("keys"));
    assertTrue(keys.stream().allMatch(raw -> (Boolean) Json.asObject(raw).get("deleted")));
  }

  @Test
  @Timeout(10)
  void a_flat_secrets_write_against_a_reserved_secretmap_key_is_rejected_through_the_proxy_too()
      throws Exception {
    HttpResponse<String> response =
        put("/secrets/acme/secretmap:db-creds:username", Json.write(Map.of("value", encode("x"))));

    assertEquals(400, response.statusCode());
  }
}
