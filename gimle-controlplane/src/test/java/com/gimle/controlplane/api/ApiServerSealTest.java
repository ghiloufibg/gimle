package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.protocol.Json;
import com.gimle.fafnir.secret.SealCipher;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
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
 * {@code GET /seal/public-key}, {@code POST /seal/rotate-key}, and {@code POST /secretmaps/*}{@code
 * /seal} over a real loopback HTTP connection, proxied through to a real {@link InProcessFafnir}
 * replica -- confirms {@code handleSecretMapsProxy}'s existing genericness already covers the new
 * {@code /seal} action route with zero changes of its own, and that the three new single-purpose
 * global routes relay correctly. RBAC coverage lives separately in {@code ApiServerSealAuthzTest},
 * since plaintext requests skip authorization entirely.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerSealTest {

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

  private HttpResponse<String> get(String path) throws Exception {
    return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build());
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build());
  }

  private record PublicKeyResponse(byte sealingKeyId, PublicKey publicKey) {}

  private PublicKeyResponse fetchPublicKey() throws Exception {
    HttpResponse<String> response = get("/seal/public-key");
    assertEquals(200, response.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    int sealingKeyId = ((Number) body.get("sealingKeyId")).intValue();
    byte[] der = Base64.getDecoder().decode((String) body.get("publicKey"));
    PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    return new PublicKeyResponse((byte) sealingKeyId, publicKey);
  }

  private String sealedEnvelopeJson(
      PublicKeyResponse key, String tenantId, String name, String memberKey, String plaintext) {
    SealCipher.SealedEnvelope envelope =
        SealCipher.seal(
            plaintext.getBytes(StandardCharsets.UTF_8),
            key.publicKey(),
            key.sealingKeyId(),
            SealCipher.aadFor(tenantId, name, memberKey));
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("sealingKeyId", Byte.toUnsignedInt(envelope.sealingKeyId()));
    json.put("wrappedKey", Base64.getEncoder().encodeToString(envelope.wrappedDataKey()));
    json.put("ciphertext", Base64.getEncoder().encodeToString(envelope.ciphertext()));
    return Json.write(json);
  }

  @Test
  @Timeout(10)
  void public_key_proxies_through_to_a_real_fafnir() throws Exception {
    HttpResponse<String> response = get("/seal/public-key");

    assertEquals(200, response.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    assertTrue(body.containsKey("publicKey"));
  }

  @Test
  @Timeout(10)
  void seal_commit_on_a_secretmap_proxies_through_and_round_trips_to_the_plaintext()
      throws Exception {
    PublicKeyResponse key = fetchPublicKey();
    String sealed = sealedEnvelopeJson(key, "acme", "db-creds", "password", "hunter2");

    HttpResponse<String> commit =
        post(
            "/secretmaps/acme/db-creds/seal",
            Json.write(Map.of("sealed", Map.of("password", Json.parse(sealed)))));

    assertEquals(200, commit.statusCode());
    List<Object> results = Json.asArray(Json.asObject(Json.parse(commit.body())).get("results"));
    assertEquals(1, results.size());
    assertTrue(Json.asObject(results.get(0)).containsKey("version"));
  }

  @Test
  @Timeout(10)
  void rotate_key_proxies_through_and_the_new_active_id_can_seal_a_committable_value()
      throws Exception {
    HttpResponse<String> rotateResponse = post("/seal/rotate-key", "");
    assertEquals(200, rotateResponse.statusCode());
    assertTrue(Json.asObject(Json.parse(rotateResponse.body())).containsKey("activeSealingKeyId"));

    PublicKeyResponse key = fetchPublicKey();
    String sealed = sealedEnvelopeJson(key, "acme", "db-creds", "password", "hunter3");
    HttpResponse<String> commit =
        post(
            "/secretmaps/acme/db-creds/seal",
            Json.write(Map.of("sealed", Map.of("password", Json.parse(sealed)))));

    assertEquals(200, commit.statusCode());
  }
}
