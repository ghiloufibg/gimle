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
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Real inbound HTTP traffic against a real {@link FafnirServer} -- the internal encrypt/decrypt/
 * rotate-key surface {@code gimle-controlplane}'s own {@code FafnirClient} calls. Same {@link
 * ResourceLock} pair as {@code FafnirServerTlsTest} -- both classes drive real HTTP servers over
 * real loopback sockets, and running them concurrently corrupts each other's traffic (the same JDK
 * HttpClient parser-state hazard {@code ApiServerTest} documents for {@code gimle-controlplane}).
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-fafnir-server-http")
class FafnirServerTest {

  private static final String CERTIFICATE_PEM =
      "-----BEGIN CERTIFICATE-----\nMIIBkTCB+wIJAKl\n-----END CERTIFICATE-----\n";

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

  // ---- /secrets/{tenantId}/... ----

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
    assertEquals(Set.of("key", "latestVersion", "deleted"), entry.keySet());
  }

  @Test
  @Timeout(10)
  void listing_a_tenants_secrets_omits_a_soft_deleted_one() throws Exception {
    putSecret("acme", "db-password", "hunter2");
    putSecret("acme", "api-key", "v1");
    client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password")).DELETE().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode());
    List<Object> secrets = Json.asArray(Json.asObject(Json.parse(response.body())).get("secrets"));
    assertEquals(1, secrets.size());
    assertEquals("api-key", Json.asObject(secrets.get(0)).get("key"));
  }

  @Test
  @Timeout(10)
  void versions_lists_every_claimed_version_with_its_author_timestamp_and_type() throws Exception {
    putSecret("acme", "db-password", "v1");
    putSecret("acme", "db-password", "v2");

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password/versions"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode());
    List<Map<String, Object>> versions =
        Json.asObjectList(Json.asObject(Json.parse(response.body())).get("versions"));
    assertEquals(List.of(1L, 2L), versions.stream().map(v -> v.get("version")).toList());
    // Plaintext mode has no client identity, so every write is attributed to the same synthetic
    // principal every other unauthenticated audit entry already uses.
    assertEquals("anonymous", versions.get(0).get("author"));
    assertEquals("opaque", versions.get(0).get("type"));
    assertTrue(((Number) versions.get(0).get("writtenAtEpochMilli")).longValue() > 0);
  }

  @Test
  @Timeout(10)
  void a_get_reports_the_type_and_author_of_the_version_it_returned() throws Exception {
    putTypedSecret("acme", "tls-cert", CERTIFICATE_PEM, "pem-certificate");

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/tls-cert")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    assertEquals("pem-certificate", body.get("type"));
    assertEquals("anonymous", body.get("author"));
  }

  @Test
  @Timeout(10)
  void a_declared_type_whose_value_is_malformed_is_rejected_with_400_and_never_stored()
      throws Exception {
    HttpResponse<String> write =
        putTypedSecret("acme", "tls-cert", "-----BEGIN CERTIFICATE-----", "pem-certificate");

    assertEquals(400, write.statusCode());
    HttpResponse<String> read =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/tls-cert")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(404, read.statusCode());
  }

  @Test
  @Timeout(10)
  void an_unknown_declared_type_is_rejected_with_400() throws Exception {
    assertEquals(
        400, putTypedSecret("acme", "tls-cert", "anything", "kubernetes.io/tls").statusCode());
  }

  @Test
  @Timeout(10)
  void a_request_body_past_the_cap_is_rejected_with_413_rather_than_buffered_whole()
      throws Exception {
    // Chunked (no Content-Length) on purpose: the cap has to hold on the streamed bytes, not on a
    // header a caller controls.
    String oversized = "x".repeat(6 * 1024 * 1024);

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password"))
                .PUT(HttpRequest.BodyPublishers.ofString(oversized))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(413, response.statusCode());
  }

  @Test
  @Timeout(10)
  void a_value_past_the_per_secret_cap_is_rejected_with_400() throws Exception {
    HttpResponse<String> response =
        putSecret("acme", "db-password", "x".repeat(SecretStore.MAX_VALUE_BYTES + 1));

    assertEquals(400, response.statusCode());
  }

  @Test
  @Timeout(10)
  void the_names_query_parameter_returns_values_for_exactly_the_keys_it_named() throws Exception {
    putSecret("acme", "db-password", "hunter2");
    putSecret("acme", "api-key", "abc123");
    putSecret("acme", "unrelated", "nope");

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme?names=db-password,api-key"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode());
    Map<String, Object> secrets =
        Json.asObject(Json.asObject(Json.parse(response.body())).get("secrets"));
    assertEquals(Set.of("db-password", "api-key"), secrets.keySet());
    Map<String, Object> dbPassword = Json.asObject(secrets.get("db-password"));
    assertEquals(
        "hunter2",
        new String(decode((String) dbPassword.get("value")), StandardCharsets.UTF_8));
    assertEquals(1L, dbPassword.get("version"));
    assertEquals("opaque", dbPassword.get("type"));
  }

  @Test
  @Timeout(10)
  void the_plain_list_still_returns_metadata_only_when_no_names_are_given() throws Exception {
    putSecret("acme", "db-password", "hunter2");

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    assertTrue(body.containsKey("secrets"));
    assertFalse(Json.asObjectList(body.get("secrets")).get(0).containsKey("value"));
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
  void undelete_restores_a_soft_deleted_secret_at_the_same_version() throws Exception {
    putSecret("acme", "db-password", "hunter2");
    client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password")).DELETE().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    HttpResponse<String> undeleteResponse =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password/undelete"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, undeleteResponse.statusCode());
    assertEquals(1L, Json.asObject(Json.parse(undeleteResponse.body())).get("version"));

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
  void undelete_with_a_version_query_parameter_restores_that_specific_older_version()
      throws Exception {
    putSecret("acme", "db-password", "v1");
    putSecret("acme", "db-password", "v2");
    client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password")).DELETE().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    HttpResponse<String> undeleteResponse =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/secrets/acme/db-password/undelete?version=1"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, undeleteResponse.statusCode());
    assertEquals(1L, Json.asObject(Json.parse(undeleteResponse.body())).get("version"));

    HttpResponse<String> getResponse =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    Map<String, Object> body = Json.asObject(Json.parse(getResponse.body()));
    assertEquals(1L, body.get("version"));
    assertEquals("v1", new String(decode((String) body.get("value")), StandardCharsets.UTF_8));

    // Version 2's own data was never touched, still explicitly readable by number.
    HttpResponse<String> historicalResponse =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password?version=2"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, historicalResponse.statusCode());
    assertEquals(
        "v2",
        new String(
            decode((String) Json.asObject(Json.parse(historicalResponse.body())).get("value")),
            StandardCharsets.UTF_8));
  }

  @Test
  @Timeout(10)
  void undeleting_a_never_written_secret_returns_404() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/no-such-key/undelete"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(404, response.statusCode());
  }

  @Test
  @Timeout(10)
  void undeleting_a_hard_deleted_secret_returns_404_not_a_500() throws Exception {
    putSecret("acme", "db-password", "hunter2");
    client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password?destroy=true"))
            .DELETE()
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password/undelete"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(404, response.statusCode());
  }

  @Test
  @Timeout(10)
  void undeleting_a_version_that_was_never_written_returns_400_not_a_500() throws Exception {
    putSecret("acme", "db-password", "hunter2");
    client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password")).DELETE().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/secrets/acme/db-password/undelete?version=99"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(400, response.statusCode());
  }

  @Test
  @Timeout(10)
  void a_get_request_to_undelete_is_rejected_with_405() throws Exception {
    putSecret("acme", "db-password", "hunter2");

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password/undelete"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(405, response.statusCode());
  }

  @Test
  @Timeout(10)
  void deleting_an_unknown_secret_is_idempotent() throws Exception {
    // Matches every other resource kind's own delete-of-a-never-existed-name convention.
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/no-such-key"))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode());
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

  private HttpResponse<String> putTypedSecret(
      String tenantId, String key, String value, String type) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/" + tenantId + "/" + key))
            .PUT(
                HttpRequest.BodyPublishers.ofString(
                    Json.write(
                        Map.of(
                            "value",
                            encode(value.getBytes(StandardCharsets.UTF_8)),
                            "type",
                            type))))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }
}
