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

/**
 * Real inbound HTTP traffic against a real {@link FafnirServer} -- the internal encrypt/decrypt/
 * rotate-key surface {@code gimle-controlplane}'s own {@code FafnirClient} calls, per the design
 * doc's Phase A scope.
 */
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
}
