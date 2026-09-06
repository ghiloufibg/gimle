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
 * {@code POST}/{@code GET /ingresses*} over a real loopback HTTP connection, in the same style
 * {@code ApiServerNetworkPoliciesTest} established for the sibling network-model resource.
 *
 * <p>What is worth pinning here is that a route is refused at submission rather than stored and
 * then found unusable: an Ingress exists precisely so a route table stops being an opaque string
 * whose typos only surface wherever it is eventually parsed.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerIngressesTest {

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

  private HttpResponse<String> post(String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/ingresses"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static String fabricIngress(String name, String paramType) {
    return """
        {"name": "%s", "tenantId": "acme", "routes": [
          {"kind": "FABRIC", "path": "/greet", "interfaceName": "com.acme.Greeter",
           "majorVersion": 1, "methodName": "greet", "paramType": "%s"}]}
        """
        .formatted(name, paramType);
  }

  private static String serviceIngress(String name, String expectedVersion) {
    String guard = expectedVersion == null ? "" : "\"expectedVersion\": " + expectedVersion + ",";
    return """
        {"name": "%s", "tenantId": "acme", %s "routes": [
          {"kind": "SERVICE", "path": "/api", "prefix": true, "serviceName": "orders"}]}
        """
        .formatted(name, guard);
  }

  /**
   * The message an operator reads must name what they may write, not the internal type that failed
   * to parse it -- a fully-qualified enum class name says nothing about the manifest they wrote.
   */
  @Test
  void a_route_naming_an_unknown_kind_is_refused_without_leaking_an_internal_class_name()
      throws Exception {
    HttpResponse<String> response =
        post(
            """
            {"name":"edge","tenantId":"default","routes":[
              {"kind":"NOPE","path":"/x"}]}
            """);

    assertEquals(400, response.statusCode(), response.body());
    assertFalse(
        response.body().contains("com.gimle.core.ingress"),
        "the refusal must not name an internal class: " + response.body());
    assertTrue(response.body().contains("NOPE"), response.body());
    assertTrue(response.body().contains("FABRIC"), response.body());
  }

  @Test
  @Timeout(10)
  void a_fabric_route_naming_an_unknown_param_type_is_refused_at_admission() throws Exception {
    HttpResponse<String> response = post(fabricIngress("greeter", "STRINGG"));

    assertEquals(400, response.statusCode(), response.body());
    assertTrue(response.body().contains("STRINGG"), response.body());
    assertTrue(
        response.body().contains("NONE, STRING, INT, LONG, DOUBLE, BOOLEAN"), response.body());
    assertEquals("[]", get("/ingresses").body());
  }

  @Test
  @Timeout(10)
  void a_fabric_route_naming_a_supported_param_type_is_stored() throws Exception {
    HttpResponse<String> response = post(fabricIngress("greeter", "STRING"));

    assertEquals(200, response.statusCode(), response.body());
    assertTrue(get("/ingresses").body().contains("\"paramType\":\"STRING\""));
  }

  @Test
  @Timeout(10)
  void a_write_carrying_a_stale_expected_version_is_refused_and_changes_nothing() throws Exception {
    assertEquals(200, post(serviceIngress("public", null)).statusCode());
    assertEquals(200, post(serviceIngress("public", "1")).statusCode());

    HttpResponse<String> stale = post(serviceIngress("public", "1"));

    assertEquals(409, stale.statusCode(), stale.body());
    Map<String, Object> conflict = Json.asObject(Json.parse(stale.body()));
    assertEquals(2, ((Number) conflict.get("currentVersion")).intValue());
    Map<String, Object> stored =
        Json.asObject(Json.parse(get("/ingresses/public?tenant=acme").body()));
    assertEquals(2, ((Number) stored.get("version")).intValue());
  }

  @Test
  @Timeout(10)
  void the_collection_route_answers_with_an_array_and_the_by_name_route_with_an_object()
      throws Exception {
    assertEquals(200, post(serviceIngress("public", null)).statusCode());

    HttpResponse<String> list = get("/ingresses");
    assertEquals(200, list.statusCode(), list.body());
    assertTrue(list.body().startsWith("["), list.body());
    assertFalse(list.body().startsWith("{"), list.body());

    HttpResponse<String> named = get("/ingresses/public?tenant=acme");
    assertEquals(200, named.statusCode(), named.body());
    assertTrue(named.body().startsWith("{"), named.body());
  }
}
