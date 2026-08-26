package com.gimle.fafnir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.fafnir.testsupport.InProcessStore;
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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Real inbound HTTP traffic against a real {@link FafnirServer}'s {@code /secretmaps/*} surface,
 * plus the reserved-prefix guard on the flat {@code /secrets/*} surface -- same harness shape as
 * {@link FafnirServerTest}, same {@link ResourceLock} pair for the identical reason its own class
 * javadoc gives.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-fafnir-server-http")
class FafnirServerSecretMapTest {

  @TempDir Path tempDir;

  private InProcessStore store;
  private FafnirServer server;
  private final HttpClient client = HttpClient.newHttpClient();
  private String baseUrl;

  @BeforeEach
  void setUp() throws Exception {
    store = InProcessStore.start(tempDir.resolve("store"));
    store.store().putTenant(new Tenant("acme", new ResourceQuota(1, 1, 1)));
    FafnirCrypto crypto = new FafnirCrypto(store.client(), tempDir.resolve("keys/secret.key"));
    server = new FafnirServer(crypto, 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.port();
  }

  @AfterEach
  void tearDown() {
    server.close();
    store.close();
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path));
    builder =
        switch (method) {
          case "GET" -> builder.GET();
          case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(body));
          case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body));
          case "DELETE" -> builder.DELETE();
          default -> throw new IllegalArgumentException(method);
        };
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> putSecretMap(String tenantId, String name, Map<String, String> data)
      throws Exception {
    Map<String, String> encoded = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, String> e : data.entrySet()) {
      encoded.put(e.getKey(), encode(e.getValue()));
    }
    return send("PUT", "/secretmaps/" + tenantId + "/" + name, Json.write(Map.of("data", encoded)));
  }

  private static String encode(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String decode(String base64) {
    return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
  }

  @Test
  @Timeout(10)
  void put_bulk_sets_every_key_and_reports_one_result_per_key() throws Exception {
    HttpResponse<String> response =
        putSecretMap("acme", "db-creds", Map.of("username", "admin", "password", "hunter2"));

    assertEquals(200, response.statusCode());
    List<Object> results = Json.asArray(Json.asObject(Json.parse(response.body())).get("results"));
    assertEquals(2, results.size());
    for (Object raw : results) {
      Map<String, Object> result = Json.asObject(raw);
      assertEquals(1.0, ((Number) result.get("version")).doubleValue());
    }
  }

  @Test
  @Timeout(10)
  void get_metadata_lists_every_member_key_without_ever_returning_a_value() throws Exception {
    putSecretMap("acme", "db-creds", Map.of("username", "admin"));

    HttpResponse<String> response = send("GET", "/secretmaps/acme/db-creds", null);

    assertEquals(200, response.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    List<Object> keys = Json.asArray(body.get("keys"));
    assertEquals(1, keys.size());
    Map<String, Object> key = Json.asObject(keys.get(0));
    assertEquals("username", key.get("key"));
    assertFalse(key.containsKey("value"));
  }

  @Test
  @Timeout(10)
  void get_names_without_a_names_query_lists_secret_map_names_only() throws Exception {
    putSecretMap("acme", "db-creds", Map.of("username", "admin"));
    putSecretMap("acme", "api-keys", Map.of("primary", "abc123"));

    HttpResponse<String> response = send("GET", "/secretmaps/acme", null);

    assertEquals(200, response.statusCode());
    List<Object> names = Json.asArray(Json.asObject(Json.parse(response.body())).get("names"));
    assertEquals(2, names.size());
  }

  @Test
  @Timeout(10)
  void get_with_names_query_returns_decrypted_values_for_exactly_those_names() throws Exception {
    putSecretMap("acme", "db-creds", Map.of("username", "admin"));
    putSecretMap("acme", "api-keys", Map.of("primary", "abc123"));

    HttpResponse<String> response = send("GET", "/secretmaps/acme?names=db-creds", null);

    assertEquals(200, response.statusCode());
    Map<String, Object> secretMaps =
        Json.asObject(Json.asObject(Json.parse(response.body())).get("secretMaps"));
    assertEquals(1, secretMaps.size());
    Map<String, Object> dbCreds = Json.asObject(secretMaps.get("db-creds"));
    Map<String, Object> data = Json.asObject(dbCreds.get("data"));
    assertEquals("admin", decode((String) data.get("username")));
  }

  @Test
  @Timeout(10)
  void delete_removes_every_key_under_the_name() throws Exception {
    putSecretMap("acme", "db-creds", Map.of("username", "admin", "password", "hunter2"));

    HttpResponse<String> deleteResponse = send("DELETE", "/secretmaps/acme/db-creds", null);
    assertEquals(200, deleteResponse.statusCode());

    HttpResponse<String> metaResponse = send("GET", "/secretmaps/acme/db-creds", null);
    List<Object> keys = Json.asArray(Json.asObject(Json.parse(metaResponse.body())).get("keys"));
    assertTrue(keys.stream().allMatch(raw -> (Boolean) Json.asObject(raw).get("deleted")));
  }

  @Test
  @Timeout(10)
  void hard_delete_of_a_single_key_leaves_its_siblings_untouched() throws Exception {
    putSecretMap("acme", "db-creds", Map.of("username", "admin", "password", "hunter2"));

    HttpResponse<String> deleteResponse =
        send("DELETE", "/secretmaps/acme/db-creds/password?destroy=true", null);
    assertEquals(200, deleteResponse.statusCode());

    HttpResponse<String> metaResponse = send("GET", "/secretmaps/acme/db-creds", null);
    List<Object> keys = Json.asArray(Json.asObject(Json.parse(metaResponse.body())).get("keys"));
    assertEquals(1, keys.size());
    assertEquals("username", Json.asObject(keys.get(0)).get("key"));
  }

  @Test
  @Timeout(10)
  void a_flat_secrets_put_against_a_reserved_secretmap_key_is_rejected() throws Exception {
    HttpResponse<String> response =
        send(
            "PUT",
            "/secrets/acme/secretmap:db-creds:username",
            Json.write(Map.of("value", encode("sneaky"))));

    assertEquals(400, response.statusCode());
  }

  @Test
  @Timeout(10)
  void a_flat_secrets_delete_against_a_reserved_secretmap_key_is_rejected() throws Exception {
    putSecretMap("acme", "db-creds", Map.of("username", "admin"));

    HttpResponse<String> response =
        send("DELETE", "/secrets/acme/secretmap:db-creds:username", null);

    assertEquals(400, response.statusCode());
    // The key must still be there -- the rejected delete never reached SecretStore.
    HttpResponse<String> metaResponse = send("GET", "/secretmaps/acme/db-creds", null);
    List<Object> keys = Json.asArray(Json.asObject(Json.parse(metaResponse.body())).get("keys"));
    assertEquals(1, keys.size());
  }

  @Test
  @Timeout(10)
  void versions_lists_every_stamped_group_version_in_ascending_order() throws Exception {
    putSecretMap("acme", "db-creds", Map.of("username", "admin"));
    putSecretMap("acme", "db-creds", Map.of("password", "hunter2"));

    HttpResponse<String> response = send("GET", "/secretmaps/acme/db-creds/versions", null);

    assertEquals(200, response.statusCode());
    List<Object> versions =
        Json.asArray(Json.asObject(Json.parse(response.body())).get("groupVersions"));
    assertEquals(2, versions.size());
    assertEquals(1.0, ((Number) Json.asObject(versions.get(0)).get("groupVersion")).doubleValue());
    assertEquals(2.0, ((Number) Json.asObject(versions.get(1)).get("groupVersion")).doubleValue());
    List<Object> secondKeys = Json.asArray(Json.asObject(versions.get(1)).get("keys"));
    assertEquals(2, secondKeys.size());
  }

  @Test
  @Timeout(10)
  void rollback_restores_a_changed_key_and_returns_a_brand_new_group_version() throws Exception {
    putSecretMap("acme", "db-creds", Map.of("password", "hunter2")); // group version 1
    putSecretMap("acme", "db-creds", Map.of("password", "hunter3")); // group version 2

    HttpResponse<String> response =
        send("POST", "/secretmaps/acme/db-creds/rollback", Json.write(Map.of("groupVersion", 1)));

    assertEquals(200, response.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    assertEquals(3.0, ((Number) body.get("groupVersion")).doubleValue());
    List<Object> results = Json.asArray(body.get("results"));
    assertEquals(1, results.size());

    HttpResponse<String> valuesResponse = send("GET", "/secretmaps/acme?names=db-creds", null);
    Map<String, Object> secretMaps =
        Json.asObject(Json.asObject(Json.parse(valuesResponse.body())).get("secretMaps"));
    Map<String, Object> data = Json.asObject(Json.asObject(secretMaps.get("db-creds")).get("data"));
    assertEquals("hunter2", decode((String) data.get("password")));
  }

  @Test
  @Timeout(10)
  void rollback_to_an_unknown_group_version_returns_404() throws Exception {
    putSecretMap("acme", "db-creds", Map.of("username", "admin"));

    HttpResponse<String> response =
        send("POST", "/secretmaps/acme/db-creds/rollback", Json.write(Map.of("groupVersion", 99)));

    assertEquals(404, response.statusCode());
  }

  @Test
  @Timeout(10)
  void rollback_with_a_non_integer_group_version_is_rejected() throws Exception {
    putSecretMap("acme", "db-creds", Map.of("username", "admin"));

    HttpResponse<String> response =
        send(
            "POST",
            "/secretmaps/acme/db-creds/rollback",
            Json.write(Map.of("groupVersion", "one")));

    assertEquals(400, response.statusCode());
  }

  @Test
  @Timeout(10)
  void a_secret_maps_own_keys_are_filtered_out_of_the_flat_secrets_listing() throws Exception {
    putSecretMap("acme", "db-creds", Map.of("username", "admin"));
    send("PUT", "/secrets/acme/plain-secret", Json.write(Map.of("value", encode("v"))));

    HttpResponse<String> response = send("GET", "/secrets/acme", null);

    List<Object> secrets = Json.asArray(Json.asObject(Json.parse(response.body())).get("secrets"));
    List<String> keys =
        secrets.stream().map(raw -> (String) Json.asObject(raw).get("key")).toList();
    assertEquals(List.of("plain-secret"), keys);
  }
}
