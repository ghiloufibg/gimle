package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.config.ConfigEntry;
import com.gimle.core.protocol.Json;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
 * Payload ceilings on the two write paths that accept caller-supplied values: {@code /config/*} and
 * the {@code /secrets/*} proxy. Both the value itself and the raw request body are capped -- an
 * oversized entry would otherwise be encrypted and replicated through Raft consensus exactly like a
 * small one, held in every store replica's memory and written into every snapshot.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerPayloadLimitTest {

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
    inProcessStore
        .store()
        .putTenant(new Tenant("acme", new ResourceQuota(1_000_000_000L, 4000, 10)));
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

  @Test
  @Timeout(30)
  void a_config_value_just_under_the_cap_is_accepted() throws Exception {
    // The JSON envelope around it stays comfortably inside the request-body cap at this size.
    String value = "x".repeat(ConfigEntry.MAX_VALUE_BYTES / 2 - 1024);

    HttpResponse<String> response = putConfig("acme", "big", value);

    assertEquals(200, response.statusCode());
  }

  @Test
  @Timeout(30)
  void a_config_value_past_the_cap_is_rejected_with_413_and_never_stored() throws Exception {
    String value = "x".repeat(ConfigEntry.MAX_VALUE_BYTES / 2 + 1);

    HttpResponse<String> response = putConfig("acme", "too-big", value);

    assertEquals(413, response.statusCode());
    assertTrue(response.body().contains("exceeding the maximum"));
    assertTrue(
        inProcessStore.client().listConfigEntriesFor("acme").stream()
            .noneMatch(entry -> entry.key().equals("too-big")));
  }

  @Test
  @Timeout(30)
  void an_encrypted_config_value_past_the_cap_is_rejected_before_any_call_to_fafnir()
      throws Exception {
    String value = "x".repeat(ConfigEntry.MAX_VALUE_BYTES / 2 + 1);

    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/config/acme/too-big"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        Json.write(Map.of("value", value, "encrypted", true))))
                .build());

    assertEquals(413, response.statusCode());
  }

  @Test
  @Timeout(30)
  void a_config_request_body_past_the_cap_is_rejected_with_413_rather_than_buffered_whole()
      throws Exception {
    // Not valid JSON and not even a "value" field: the body cap has to bite while the bytes are
    // still streaming in, before anything tries to parse or interpret them.
    String oversized = "x".repeat(6 * 1024 * 1024);

    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/config/acme/k"))
                .PUT(HttpRequest.BodyPublishers.ofString(oversized))
                .build());

    assertEquals(413, response.statusCode());
  }

  @Test
  @Timeout(30)
  void a_secrets_proxy_request_body_past_the_cap_is_rejected_with_413() throws Exception {
    String oversized = "x".repeat(6 * 1024 * 1024);

    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/acme/db-password"))
                .PUT(HttpRequest.BodyPublishers.ofString(oversized))
                .build());

    assertEquals(413, response.statusCode());
  }

  @Test
  @Timeout(30)
  void a_secretmaps_proxy_request_body_past_the_cap_is_rejected_with_413() throws Exception {
    String oversized = "x".repeat(6 * 1024 * 1024);

    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/secretmaps/acme/db-creds"))
                .PUT(HttpRequest.BodyPublishers.ofString(oversized))
                .build());

    assertEquals(413, response.statusCode());
  }

  private HttpResponse<String> putConfig(String tenantId, String key, String value)
      throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/config/" + tenantId + "/" + key))
            .PUT(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("value", value))))
            .build());
  }

  private HttpResponse<String> send(HttpRequest request) throws Exception {
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }
}
