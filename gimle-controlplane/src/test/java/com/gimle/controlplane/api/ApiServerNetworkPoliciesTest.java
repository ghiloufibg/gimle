package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.protocol.Json;
import com.gimle.mimir.rpc.StoreClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
 * {@code POST}/{@code GET}/{@code DELETE /networkpolicies*} over a real loopback HTTP connection,
 * the same style {@code ApiServerServicesTest} already established for the sibling network-model
 * resource. RBAC coverage for {@link com.gimle.core.authz.ResourceKind#NETWORK_POLICY} lives
 * separately in {@code ApiServerNetworkPoliciesAuthzTest}, the same split {@code
 * ApiServerEndpointsTest}/{@code ApiServerEndpointsAuthzTest} already use -- plaintext requests
 * skip authorization entirely (see {@code ApiServer#requireAuthorized}), so RBAC only means
 * anything once a real mTLS connection is in play.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerNetworkPoliciesTest {

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

  private static String tenantWidePolicyJson(String name, String tenantId, String... allowed) {
    String allowedJson =
        String.join(", ", java.util.Arrays.stream(allowed).map(t -> "\"" + t + "\"").toList());
    return """
        {"name": "%s", "tenantId": "%s", "allowedCallerTenantIds": [%s]}
        """
        .formatted(name, tenantId, allowedJson);
  }

  @Test
  @Timeout(10)
  void post_then_get_a_network_policy_round_trips() throws Exception {
    HttpResponse<String> post =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies"))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        tenantWidePolicyJson("deny-by-default", "acme", "partner-tenant")))
                .build());
    assertEquals(200, post.statusCode());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies/deny-by-default"))
                .GET()
                .build());
    assertEquals(200, get.statusCode());
    Map<String, Object> spec = Json.asObject(Json.parse(get.body()));
    assertEquals("deny-by-default", spec.get("name"));
    assertEquals("acme", spec.get("tenantId"));
    assertEquals(List.of(), spec.get("deploymentNames"));
    assertEquals(List.of("partner-tenant"), spec.get("allowedCallerTenantIds"));
  }

  @Test
  @Timeout(10)
  void a_policy_with_no_allow_list_round_trips_as_an_empty_array() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"name": "deny-all", "tenantId": "acme"}
                    """))
            .build());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies/deny-all"))
                .GET()
                .build());
    assertEquals(200, get.statusCode());
    Map<String, Object> spec = Json.asObject(Json.parse(get.body()));
    assertTrue(((List<?>) spec.get("allowedCallerTenantIds")).isEmpty());
  }

  @Test
  @Timeout(10)
  void get_of_an_unknown_network_policy_is_404() throws Exception {
    HttpResponse<String> response =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies/nope")).GET().build());
    assertEquals(404, response.statusCode());
  }

  @Test
  @Timeout(10)
  void delete_removes_a_network_policy() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    tenantWidePolicyJson("deny-by-default", "acme")))
            .build());

    HttpResponse<String> delete =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies/deny-by-default"))
                .DELETE()
                .build());
    assertEquals(200, delete.statusCode());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies/deny-by-default"))
                .GET()
                .build());
    assertEquals(404, get.statusCode());
  }

  @Test
  @Timeout(10)
  void network_policies_list_endpoint_returns_every_policy() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies"))
            .POST(HttpRequest.BodyPublishers.ofString(tenantWidePolicyJson("policy-a", "acme")))
            .build());
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies"))
            .POST(HttpRequest.BodyPublishers.ofString(tenantWidePolicyJson("policy-b", "globex")))
            .build());

    HttpResponse<String> response =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies")).GET().build());
    assertEquals(200, response.statusCode());
    assertEquals(2, Json.asArray(Json.parse(response.body())).size());
  }

  @Test
  @Timeout(10)
  void network_policies_list_endpoint_is_empty_with_none_submitted() throws Exception {
    HttpResponse<String> response =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies")).GET().build());
    assertEquals(200, response.statusCode());
    assertTrue(Json.asArray(Json.parse(response.body())).isEmpty());
  }

  @Test
  @Timeout(10)
  void a_missing_network_policy_name_on_post_is_a_400() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies"))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {"tenantId": "acme"}
                        """))
                .build());
    assertEquals(400, response.statusCode());
  }

  @Test
  @Timeout(10)
  void a_missing_tenant_id_on_post_is_a_400() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies"))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {"name": "deny-by-default"}
                        """))
                .build());
    assertEquals(400, response.statusCode());
  }

  @Test
  @Timeout(10)
  void posting_the_same_name_again_replaces_the_prior_spec() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    tenantWidePolicyJson("deny-by-default", "acme", "partner-a")))
            .build());
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    tenantWidePolicyJson("deny-by-default", "acme", "partner-b")))
            .build());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies/deny-by-default"))
                .GET()
                .build());
    Map<String, Object> spec = Json.asObject(Json.parse(get.body()));
    assertEquals(List.of("partner-b"), spec.get("allowedCallerTenantIds"));
  }

  /**
   * The actual bug this persistence swap fixes: {@code NetworkPolicyRegistry} used to hold an
   * in-memory map per {@code ApiServer} replica, so a policy created against one replica was
   * invisible to another. Two {@code ApiServer} instances here share one {@link InProcessStore}
   * through two independent {@code StoreClient} connections -- the same "one store cluster, N
   * stateless control-plane replicas" shape production runs, just over loopback instead of a real
   * network -- proving a policy POSTed to one is now visible via {@code GET /networkpolicies} on
   * the other.
   */
  @Test
  @Timeout(10)
  void a_network_policy_posted_to_one_replica_is_visible_on_a_second_replica() throws Exception {
    try (StoreClient secondReplicaClient = inProcessStore.newClient();
        ApiServer secondReplica = new ApiServer(secondReplicaClient, 0, inProcessFafnir.client())) {
      secondReplica.start();
      String secondBaseUrl = "http://localhost:" + secondReplica.port();

      HttpResponse<String> post =
          send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies"))
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          tenantWidePolicyJson("deny-by-default", "acme", "partner-tenant")))
                  .build());
      assertEquals(200, post.statusCode());

      HttpResponse<String> getFromSecondReplica =
          send(
              HttpRequest.newBuilder(URI.create(secondBaseUrl + "/networkpolicies/deny-by-default"))
                  .GET()
                  .build());
      assertEquals(200, getFromSecondReplica.statusCode());
      Map<String, Object> spec = Json.asObject(Json.parse(getFromSecondReplica.body()));
      assertEquals("deny-by-default", spec.get("name"));
      assertEquals("acme", spec.get("tenantId"));

      HttpResponse<String> listFromSecondReplica =
          send(
              HttpRequest.newBuilder(URI.create(secondBaseUrl + "/networkpolicies")).GET().build());
      assertEquals(1, Json.asArray(Json.parse(listFromSecondReplica.body())).size());
    }
  }

  @Test
  @Timeout(10)
  void a_deployment_scoped_policy_round_trips_its_deployment_names() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"name": "scoped", "tenantId": "acme", "deploymentNames": ["orders-service"]}
                    """))
            .build());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies/scoped")).GET().build());
    Map<String, Object> spec = Json.asObject(Json.parse(get.body()));
    assertEquals(List.of("orders-service"), spec.get("deploymentNames"));
  }
}
