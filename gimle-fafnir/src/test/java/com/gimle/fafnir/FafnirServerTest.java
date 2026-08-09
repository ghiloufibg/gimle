package com.gimle.fafnir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.protocol.Json;
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
 * Real inbound HTTP traffic against a real {@link FafnirServer} -- the internal encrypt/decrypt/
 * rotate-key surface {@code gimle-controlplane}'s own {@code FafnirClient} calls, per the design
 * doc's Phase A scope. Same {@link ResourceLock} pair as {@code FafnirServerTlsTest} -- both
 * classes drive real HTTP servers over real loopback sockets, and running them concurrently
 * corrupts each other's traffic (the same JDK HttpClient parser-state hazard {@code ApiServerTest}
 * documents for {@code gimle-controlplane}).
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-fafnir-server-http")
class FafnirServerTest {

  @TempDir Path tempDir;

  private InProcessStore store;
  private FafnirServer server;
  private final HttpClient client = HttpClient.newHttpClient();
  private String baseUrl;

  @BeforeEach
  void setUp() throws Exception {
    store = InProcessStore.start(tempDir.resolve("store"));
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

  private static String encode(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static byte[] decode(String base64) {
    return Base64.getDecoder().decode(base64);
  }

  @Test
  @Timeout(10)
  void a_real_encrypt_request_returns_ciphertext_a_real_decrypt_request_can_reverse()
      throws Exception {
    byte[] plaintext = "s3cr3t-value".getBytes(StandardCharsets.UTF_8);

    HttpResponse<String> encryptResponse =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/internal/secrets/encrypt"))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        Json.write(Map.of("value", encode(plaintext)))))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, encryptResponse.statusCode());
    String ciphertextBase64 =
        (String) Json.asObject(Json.parse(encryptResponse.body())).get("ciphertext");

    HttpResponse<String> decryptResponse =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/internal/secrets/decrypt"))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        Json.write(Map.of("values", List.of(ciphertextBase64)))))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, decryptResponse.statusCode());
    List<Object> values =
        Json.asArray(Json.asObject(Json.parse(decryptResponse.body())).get("values"));
    assertEquals(
        "s3cr3t-value", new String(decode((String) values.get(0)), StandardCharsets.UTF_8));
  }

  @Test
  @Timeout(10)
  void a_decrypt_request_decrypts_multiple_values_in_the_order_they_were_sent() throws Exception {
    HttpResponse<String> encryptOne =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/internal/secrets/encrypt"))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        Json.write(
                            Map.of("value", encode("first".getBytes(StandardCharsets.UTF_8))))))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    HttpResponse<String> encryptTwo =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/internal/secrets/encrypt"))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        Json.write(
                            Map.of("value", encode("second".getBytes(StandardCharsets.UTF_8))))))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    String cipherOne = (String) Json.asObject(Json.parse(encryptOne.body())).get("ciphertext");
    String cipherTwo = (String) Json.asObject(Json.parse(encryptTwo.body())).get("ciphertext");

    HttpResponse<String> decryptResponse =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/internal/secrets/decrypt"))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        Json.write(Map.of("values", List.of(cipherOne, cipherTwo)))))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    List<Object> values =
        Json.asArray(Json.asObject(Json.parse(decryptResponse.body())).get("values"));
    assertEquals("first", new String(decode((String) values.get(0)), StandardCharsets.UTF_8));
    assertEquals("second", new String(decode((String) values.get(1)), StandardCharsets.UTF_8));
  }

  @Test
  @Timeout(10)
  void a_rotate_key_request_returns_the_new_active_key_id_and_it_increments_each_call()
      throws Exception {
    HttpResponse<String> first =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/rotate-key"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, first.statusCode());
    Object firstKeyId = Json.asObject(Json.parse(first.body())).get("activeKeyId");
    assertEquals(1L, firstKeyId);

    HttpResponse<String> second =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/rotate-key"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    Object secondKeyId = Json.asObject(Json.parse(second.body())).get("activeKeyId");
    assertEquals(2L, secondKeyId);
  }

  @Test
  @Timeout(10)
  void a_non_post_request_to_encrypt_is_rejected() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/internal/secrets/encrypt")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(405, response.statusCode());
  }

  // ---- /secrets/{tenantId}/... (design doc §6e/§7) ----

  @Test
  @Timeout(10)
  void a_put_then_get_round_trips_the_secret_value_and_reports_version_1() throws Exception {
    HttpResponse<String> putResponse = putSecret("acme", "db-password", "hunter2");
    assertEquals(200, putResponse.statusCode());
    assertEquals(1L, Json.asObject(Json.parse(putResponse.body())).get("version"));

    HttpResponse<String> getResponse =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, getResponse.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(getResponse.body()));
    assertEquals(1L, body.get("version"));
    assertEquals("hunter2", new String(decode((String) body.get("value")), StandardCharsets.UTF_8));
  }

  @Test
  @Timeout(10)
  void getting_an_unknown_secret_returns_404() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/no-such-key")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(404, response.statusCode());
  }

  @Test
  @Timeout(10)
  void listing_a_tenants_secrets_returns_metadata_without_any_value_field() throws Exception {
    putSecret("acme", "db-password", "hunter2");

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode());
    List<Object> secrets = Json.asArray(Json.asObject(Json.parse(response.body())).get("secrets"));
    assertEquals(1, secrets.size());
    Map<String, Object> entry = Json.asObject(secrets.get(0));
    assertEquals("db-password", entry.get("key"));
    assertEquals(1L, entry.get("latestVersion"));
    assertEquals(false, entry.get("deleted"));
    assertEquals(java.util.Set.of("key", "latestVersion", "deleted"), entry.keySet());
  }

  @Test
  @Timeout(10)
  void versions_lists_every_claimed_version_number() throws Exception {
    putSecret("acme", "db-password", "v1");
    putSecret("acme", "db-password", "v2");

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password/versions"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode());
    assertEquals(
        List.of(1L, 2L), Json.asArray(Json.asObject(Json.parse(response.body())).get("versions")));
  }

  @Test
  @Timeout(10)
  void an_explicit_version_query_parameter_reads_that_historical_value() throws Exception {
    putSecret("acme", "db-password", "v1");
    putSecret("acme", "db-password", "v2");

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password?version=1"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    assertEquals(1L, body.get("version"));
    assertEquals("v1", new String(decode((String) body.get("value")), StandardCharsets.UTF_8));
  }

  @Test
  @Timeout(10)
  void soft_deleting_a_secret_hides_it_from_a_default_get_but_versions_remain_readable()
      throws Exception {
    putSecret("acme", "db-password", "hunter2");

    HttpResponse<String> deleteResponse =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password"))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, deleteResponse.statusCode());

    HttpResponse<String> getResponse =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(404, getResponse.statusCode());

    HttpResponse<String> historicalResponse =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password?version=1"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, historicalResponse.statusCode());
  }

  @Test
  @Timeout(10)
  void hard_deleting_a_secret_removes_every_version() throws Exception {
    putSecret("acme", "db-password", "hunter2");

    HttpResponse<String> deleteResponse =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password?destroy=true"))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, deleteResponse.statusCode());

    HttpResponse<String> historicalResponse =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password?version=1"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(404, historicalResponse.statusCode());
  }

  @Test
  @Timeout(10)
  void deleting_an_unknown_secret_returns_404() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/no-such-key"))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(404, response.statusCode());
  }

  private HttpResponse<String> putSecret(String tenantId, String key, String value)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/" + tenantId + "/" + key))
            .PUT(
                HttpRequest.BodyPublishers.ofString(
                    Json.write(Map.of("value", encode(value.getBytes(StandardCharsets.UTF_8))))))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }
}
