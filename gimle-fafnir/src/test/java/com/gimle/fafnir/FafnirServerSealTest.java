package com.gimle.fafnir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.config.ConfigEntry;
import com.gimle.core.protocol.Json;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.fafnir.secret.SealCipher;
import com.gimle.fafnir.testsupport.InProcessStore;
import com.gimle.mimir.raft.StateMutation;
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
 * Real inbound HTTP traffic against a real {@link FafnirServer}'s {@code /seal/*} surface and the
 * SecretMap {@code /seal} commit route -- the seal, commit, apply round trip end to end, same
 * harness shape as {@link FafnirServerSecretMapTest}.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-fafnir-server-http")
class FafnirServerSealTest {

  @TempDir Path tempDir;

  private InProcessStore store;
  private FafnirCrypto crypto;
  private FafnirServer server;
  private final HttpClient client = HttpClient.newHttpClient();
  private String baseUrl;

  @BeforeEach
  void setUp() throws Exception {
    store = InProcessStore.start(tempDir.resolve("store"));
    store.store().putTenant(new Tenant("acme", new ResourceQuota(1, 1, 1)));
    crypto = new FafnirCrypto(store.client(), tempDir.resolve("keys/secret.key"));
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
          case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body));
          default -> throw new IllegalArgumentException(method);
        };
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> sendNoAuthHeader(String method, String path, String body)
      throws Exception {
    // Deliberately no Authorization/session header at all -- proves /seal/public-key really is
    // reachable by a caller with zero Fafnir credentials, the whole point of the endpoint.
    return send(method, path, body);
  }

  /** Fetches the active sealing public key and decodes it into a usable {@link PublicKey}. */
  private PublicKeyResponse fetchPublicKey() throws Exception {
    HttpResponse<String> response = sendNoAuthHeader("GET", "/seal/public-key", null);
    assertEquals(200, response.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    int sealingKeyId = ((Number) body.get("sealingKeyId")).intValue();
    byte[] der = Base64.getDecoder().decode((String) body.get("publicKey"));
    PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    return new PublicKeyResponse((byte) sealingKeyId, publicKey);
  }

  private record PublicKeyResponse(byte sealingKeyId, PublicKey publicKey) {}

  private String sealedEnvelopeJson(
      PublicKeyResponse key, String tenantId, String name, String memberKey, String plaintext) {
    SealCipher.SealedEnvelope envelope =
        SealCipher.seal(
            plaintext.getBytes(StandardCharsets.UTF_8),
            key.publicKey(),
            key.sealingKeyId(),
            SealCipher.aadFor(tenantId, name, memberKey));
    Map<String, Object> json = new java.util.LinkedHashMap<>();
    json.put("sealingKeyId", Byte.toUnsignedInt(envelope.sealingKeyId()));
    json.put("wrappedKey", Base64.getEncoder().encodeToString(envelope.wrappedDataKey()));
    json.put("ciphertext", Base64.getEncoder().encodeToString(envelope.ciphertext()));
    return Json.write(json);
  }

  @Test
  @Timeout(15)
  void public_key_is_reachable_with_no_auth_header_at_all() throws Exception {
    HttpResponse<String> response = sendNoAuthHeader("GET", "/seal/public-key", null);

    assertEquals(200, response.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    assertTrue(body.containsKey("sealingKeyId"));
    assertTrue(body.containsKey("publicKey"));
    assertEquals("RSA-OAEP-SHA256", body.get("algorithm"));
  }

  @Test
  @Timeout(15)
  void a_validly_sealed_value_round_trips_through_commit_to_the_plaintext() throws Exception {
    PublicKeyResponse key = fetchPublicKey();
    String sealed = sealedEnvelopeJson(key, "acme", "db-creds", "password", "hunter2");

    HttpResponse<String> commit =
        send(
            "POST",
            "/secretmaps/acme/db-creds/seal",
            Json.write(Map.of("sealed", Map.of("password", Json.parse(sealed)))));

    assertEquals(200, commit.statusCode());
    List<Object> results = Json.asArray(Json.asObject(Json.parse(commit.body())).get("results"));
    assertEquals(1, results.size());
    assertTrue(Json.asObject(results.get(0)).containsKey("version"));

    HttpResponse<String> values = send("GET", "/secretmaps/acme?names=db-creds", null);
    Map<String, Object> secretMaps =
        Json.asObject(Json.asObject(Json.parse(values.body())).get("secretMaps"));
    Map<String, Object> data = Json.asObject(Json.asObject(secretMaps.get("db-creds")).get("data"));
    String decoded =
        new String(
            Base64.getDecoder().decode((String) data.get("password")), StandardCharsets.UTF_8);
    assertEquals("hunter2", decoded);
  }

  @Test
  @Timeout(15)
  void a_blob_sealed_for_a_different_tenant_is_rejected_as_a_per_key_failure_not_a_500()
      throws Exception {
    PublicKeyResponse key = fetchPublicKey();
    // Sealed for tenant "globex", committed under tenant "acme" -- the AAD label won't match.
    String sealed = sealedEnvelopeJson(key, "globex", "db-creds", "password", "hunter2");

    HttpResponse<String> commit =
        send(
            "POST",
            "/secretmaps/acme/db-creds/seal",
            Json.write(Map.of("sealed", Map.of("password", Json.parse(sealed)))));

    assertEquals(200, commit.statusCode());
    List<Object> results = Json.asArray(Json.asObject(Json.parse(commit.body())).get("results"));
    assertEquals(1, results.size());
    assertTrue(Json.asObject(results.get(0)).containsKey("error"));
  }

  @Test
  @Timeout(15)
  void a_blob_sealed_for_a_different_name_is_rejected() throws Exception {
    PublicKeyResponse key = fetchPublicKey();
    String sealed = sealedEnvelopeJson(key, "acme", "other-map", "password", "hunter2");

    HttpResponse<String> commit =
        send(
            "POST",
            "/secretmaps/acme/db-creds/seal",
            Json.write(Map.of("sealed", Map.of("password", Json.parse(sealed)))));

    List<Object> results = Json.asArray(Json.asObject(Json.parse(commit.body())).get("results"));
    assertTrue(Json.asObject(results.get(0)).containsKey("error"));
  }

  @Test
  @Timeout(15)
  void an_unknown_sealing_key_id_is_a_per_key_failure() throws Exception {
    String sealed =
        Json.write(
            Map.of(
                "sealingKeyId", 99,
                "wrappedKey", Base64.getEncoder().encodeToString(new byte[384]),
                "ciphertext", Base64.getEncoder().encodeToString(new byte[32])));

    HttpResponse<String> commit =
        send(
            "POST",
            "/secretmaps/acme/db-creds/seal",
            Json.write(Map.of("sealed", Map.of("password", Json.parse(sealed)))));

    List<Object> results = Json.asArray(Json.asObject(Json.parse(commit.body())).get("results"));
    assertTrue(Json.asObject(results.get(0)).containsKey("error"));
  }

  /**
   * A {@code sealingKeyId} outside the valid byte range must be rejected on its own terms, not
   * silently truncated by a raw {@code (byte)} cast -- 999 cast that way becomes 231 ({@code 999 &
   * 0xFF}), which would otherwise report a confusing "no sealing key with id 231" for a caller who
   * sent 999. Caught via manual end-to-end testing, not anticipated by the original design.
   */
  @Test
  @Timeout(15)
  void an_out_of_range_sealing_key_id_is_rejected_without_silent_truncation() throws Exception {
    String sealed =
        Json.write(
            Map.of(
                "sealingKeyId", 999,
                "wrappedKey", Base64.getEncoder().encodeToString(new byte[384]),
                "ciphertext", Base64.getEncoder().encodeToString(new byte[32])));

    HttpResponse<String> commit =
        send(
            "POST",
            "/secretmaps/acme/db-creds/seal",
            Json.write(Map.of("sealed", Map.of("password", Json.parse(sealed)))));

    List<Object> results = Json.asArray(Json.asObject(Json.parse(commit.body())).get("results"));
    Object error = Json.asObject(results.get(0)).get("error");
    assertTrue(error instanceof String, "expected an error message, got: " + error);
    assertTrue(((String) error).contains("between 0 and 255"), (String) error);
  }

  @Test
  @Timeout(15)
  void a_key_retired_after_rotation_can_no_longer_apply_a_blob_sealed_under_it() throws Exception {
    // Key id 0 can never be retired (see KeyFileManager/SealingKeyFileManager's own retire
    // javadoc), so this needs one rotation before sealing (putting the seal under a later-
    // retireable non-zero id) and a second rotation before retiring (so that id is no longer
    // active -- retiring the active key is also rejected).
    HttpResponse<String> firstRotate = send("POST", "/seal/rotate-key", "");
    assertEquals(200, firstRotate.statusCode());
    PublicKeyResponse oldKey = fetchPublicKey();
    String sealed = sealedEnvelopeJson(oldKey, "acme", "db-creds", "password", "hunter2");

    HttpResponse<String> secondRotate = send("POST", "/seal/rotate-key", "");
    assertEquals(200, secondRotate.statusCode());

    HttpResponse<String> retireResponse =
        send(
            "POST",
            "/seal/retire-key",
            Json.write(Map.of("keyId", Byte.toUnsignedInt(oldKey.sealingKeyId()))));
    assertEquals(200, retireResponse.statusCode());

    HttpResponse<String> commit =
        send(
            "POST",
            "/secretmaps/acme/db-creds/seal",
            Json.write(Map.of("sealed", Map.of("password", Json.parse(sealed)))));

    List<Object> results = Json.asArray(Json.asObject(Json.parse(commit.body())).get("results"));
    assertTrue(Json.asObject(results.get(0)).containsKey("error"));
  }

  @Test
  @Timeout(15)
  void a_symmetric_key_retired_after_rotation_strands_a_value_still_encrypted_under_it()
      throws Exception {
    // The destructive-consequence case for the *other* key ring: unlike sealing-key retirement
    // (which only blocks not-yet-applied blobs), retiring a symmetric key can strand data already
    // at rest under it -- this is expected, not a bug, and is exactly why retiring the active key
    // (and id 0 specifically) is rejected outright. Rotate once so a value encrypted next lands
    // under a non-zero, later-retireable id; rotate again so that id is no longer active. Fafnir
    // recognizes its own retired id and reports it as a specific 400, not an opaque 500.
    HttpResponse<String> firstRotate = send("POST", "/secrets/rotate-key", "");
    assertEquals(200, firstRotate.statusCode());
    int encryptingKeyId =
        ((Number) Json.asObject(Json.parse(firstRotate.body())).get("activeKeyId")).intValue();
    byte[] ciphertext = crypto.encrypt("hunter2".getBytes(StandardCharsets.UTF_8));

    HttpResponse<String> secondRotate = send("POST", "/secrets/rotate-key", "");
    assertEquals(200, secondRotate.statusCode());

    // Written directly to the store as SecretStore's own key@meta/key@1 pair, rather than
    // through PUT /secrets/*, and only after the second rotate above: #rotate's own
    // re-encryption sweep would otherwise reach a value written the ordinary way and move it
    // onto the new active key before there's anything left for the retire below to strand -- the
    // same reason FafnirCryptoTest's own rotate tests construct their ciphertext this way rather
    // than through SecretStore.
    store
        .client()
        .propose(
            new StateMutation.PutConfigEntry(
                new ConfigEntry("acme", "plain-secret@1", ciphertext, true)));
    store
        .client()
        .propose(
            new StateMutation.PutConfigEntry(
                new ConfigEntry(
                    "acme",
                    "plain-secret@meta",
                    Json.write(Map.of("latestVersion", 1, "deleted", false))
                        .getBytes(StandardCharsets.UTF_8),
                    false)));

    HttpResponse<String> retireResponse =
        send("POST", "/secrets/retire-key", Json.write(Map.of("keyId", encryptingKeyId)));
    assertEquals(200, retireResponse.statusCode());

    HttpResponse<String> getResponse = send("GET", "/secrets/acme/plain-secret", null);
    assertEquals(400, getResponse.statusCode());
    assertTrue(getResponse.body().contains("retired"));
  }
}
