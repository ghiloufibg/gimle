package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.protocol.Json;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.rpc.StoreClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    // Both a policy's own owning tenant and every tenant its allow list names are validated against
    // the real tenant registry, so every tenant these scenarios name has to exist first.
    for (String tenantId :
        List.of(
            "acme",
            "globex",
            "partner-tenant",
            "partner-a",
            "partner-b",
            "partner-c",
            "closed-tenant",
            "odd-tenant")) {
      inProcessStore.client().propose(new StateMutation.PutTenant(new Tenant(tenantId, QUOTA)));
    }
  }

  private static final ResourceQuota QUOTA = new ResourceQuota(1024L * 1024, 1000, 5);

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
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/networkpolicies/deny-by-default?tenant=acme"))
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
                    {"name": "deny-all", "tenantId": "acme", "allowedCallerTenantIds": []}
                    """))
            .build());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies/deny-all?tenant=acme"))
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
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies/nope?tenant=acme"))
                .GET()
                .build());
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
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/networkpolicies/deny-by-default?tenant=acme"))
                .DELETE()
                .build());
    assertEquals(200, delete.statusCode());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/networkpolicies/deny-by-default?tenant=acme"))
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
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/networkpolicies/deny-by-default?tenant=acme"))
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
              HttpRequest.newBuilder(
                      URI.create(secondBaseUrl + "/networkpolicies/deny-by-default?tenant=acme"))
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
                    {"name": "scoped", "tenantId": "acme", "deploymentNames": ["orders-service"], "allowedCallerTenantIds": []}
                    """))
            .build());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/networkpolicies/scoped?tenant=acme"))
                .GET()
                .build());
    Map<String, Object> spec = Json.asObject(Json.parse(get.body()));
    assertEquals(List.of("orders-service"), spec.get("deploymentNames"));
  }

  @Test
  @Timeout(10)
  void a_policy_naming_a_tenant_that_does_not_exist_is_a_400() throws Exception {
    HttpResponse<String> response =
        post("/networkpolicies", tenantWidePolicyJson("typo", "acme", "no-such-tenant"));

    assertEquals(400, response.statusCode());
    assertTrue(response.body().contains("no-such-tenant"), response.body());
    assertEquals(404, get("/networkpolicies/typo?tenant=acme").statusCode());
  }

  @Test
  @Timeout(10)
  void a_policy_whose_own_owning_tenant_does_not_exist_is_a_400() throws Exception {
    // The owning tenant used to be the one tenant a policy could name without it being checked --
    // referencedTenantIds excludes it by design -- so a policy could be stored against a tenant
    // nobody ever created: deny-by-default, enforcing nothing, and absent from every per-tenant
    // view of the cluster.
    HttpResponse<String> response =
        post("/networkpolicies", tenantWidePolicyJson("orphan", "no-such-tenant", "partner-a"));

    assertEquals(400, response.statusCode());
    assertTrue(response.body().contains("no-such-tenant"), response.body());
    assertEquals(404, get("/networkpolicies/orphan?tenant=no-such-tenant").statusCode());
  }

  /**
   * A tenant deleted after a valid policy was written leaves a dangling reference. The stored
   * policy is deliberately left exactly as written -- rewriting it would change what it allows at
   * the moment a tenant disappeared -- so the condition surfaces as an advisory on reads instead.
   */
  @Test
  @Timeout(10)
  void a_tenant_deleted_after_a_policy_named_it_shows_up_as_a_dangling_reference()
      throws Exception {
    post("/networkpolicies", tenantWidePolicyJson("policy", "acme", "partner-a", "partner-b"));

    assertEquals(
        200,
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/partner-a")).DELETE().build())
            .statusCode());

    Map<String, Object> spec =
        Json.asObject(Json.parse(get("/networkpolicies/policy?tenant=acme").body()));
    assertEquals(List.of("partner-a"), spec.get("danglingTenantIds"));
    assertEquals(
        Set.of("partner-a", "partner-b"),
        Set.copyOf(Json.asArray(spec.get("allowedCallerTenantIds"))));
  }

  @Test
  @Timeout(10)
  void a_post_guarded_by_a_stale_expected_version_is_a_409() throws Exception {
    post("/networkpolicies", tenantWidePolicyJson("policy", "acme", "partner-a"));
    post("/networkpolicies", tenantWidePolicyJson("policy", "acme", "partner-b"));

    HttpResponse<String> stale =
        post(
            "/networkpolicies",
            "{\"name\": \"policy\", \"tenantId\": \"acme\","
                + " \"allowedCallerTenantIds\": [\"partner-c\"], \"expectedVersion\": 1}");

    assertEquals(409, stale.statusCode());
    Map<String, Object> conflict = Json.asObject(Json.parse(stale.body()));
    assertEquals(2, ((Number) conflict.get("currentVersion")).intValue());
    Map<String, Object> spec =
        Json.asObject(Json.parse(get("/networkpolicies/policy?tenant=acme").body()));
    assertEquals(List.of("partner-b"), spec.get("allowedCallerTenantIds"));
  }

  @Test
  @Timeout(10)
  void a_patch_adds_one_caller_tenant_without_resending_the_whole_policy() throws Exception {
    post("/networkpolicies", tenantWidePolicyJson("policy", "acme", "partner-a"));

    HttpResponse<String> patched =
        patch("/networkpolicies/policy?tenant=acme", addCallerPatch(1, "partner-b"));

    assertEquals(200, patched.statusCode());
    Map<String, Object> spec =
        Json.asObject(Json.parse(get("/networkpolicies/policy?tenant=acme").body()));
    assertEquals(
        Set.of("partner-a", "partner-b"),
        Set.copyOf(Json.asArray(spec.get("allowedCallerTenantIds"))));
    assertEquals(2, ((Number) spec.get("version")).intValue());
  }

  /**
   * The lost update, at the API surface: the second operator read version 1, the first operator's
   * edit landed in between, and the second write is refused rather than replacing the policy with
   * one that has never heard of the first operator's addition.
   */
  @Test
  @Timeout(10)
  void a_patch_against_a_stale_version_is_a_409_and_the_other_edit_survives() throws Exception {
    post("/networkpolicies", tenantWidePolicyJson("policy", "acme", "partner-a"));
    assertEquals(
        200,
        patch("/networkpolicies/policy?tenant=acme", addCallerPatch(1, "partner-b")).statusCode());

    HttpResponse<String> stale =
        patch("/networkpolicies/policy?tenant=acme", addCallerPatch(1, "partner-c"));

    assertEquals(409, stale.statusCode());
    Map<String, Object> spec =
        Json.asObject(Json.parse(get("/networkpolicies/policy?tenant=acme").body()));
    assertEquals(
        Set.of("partner-a", "partner-b"),
        Set.copyOf(Json.asArray(spec.get("allowedCallerTenantIds"))));
  }

  @Test
  @Timeout(10)
  void a_patch_without_an_expected_version_is_a_400() throws Exception {
    post("/networkpolicies", tenantWidePolicyJson("policy", "acme", "partner-a"));

    assertEquals(
        400,
        patch(
                "/networkpolicies/policy?tenant=acme",
                "{\"addAllowedCallerTenantIds\": [\"partner-b\"]}")
            .statusCode());
  }

  @Test
  @Timeout(10)
  void a_patch_naming_a_tenant_that_does_not_exist_is_a_400() throws Exception {
    post("/networkpolicies", tenantWidePolicyJson("policy", "acme", "partner-a"));

    assertEquals(
        400,
        patch("/networkpolicies/policy?tenant=acme", addCallerPatch(1, "no-such-tenant"))
            .statusCode());
  }

  @Test
  @Timeout(10)
  void a_patch_of_a_policy_that_does_not_exist_is_a_404() throws Exception {
    assertEquals(
        404,
        patch("/networkpolicies/nope?tenant=acme", addCallerPatch(0, "partner-a")).statusCode());
  }

  @Test
  @Timeout(10)
  void network_postures_reports_each_tenants_declared_isolation_posture() throws Exception {
    assertEquals(200, putTenant("closed-tenant", ", \"isolationPosture\": \"DENY_BY_DEFAULT\""));

    Map<String, Object> postures = posturesByTenant();

    assertEquals("DENY_BY_DEFAULT", postures.get("closed-tenant"));
    assertEquals("OPEN", postures.get("partner-a"));
  }

  /**
   * A quota edit that says nothing about the posture must not reopen a tenant an operator closed --
   * the field is preserved, not defaulted, on an update.
   */
  @Test
  @Timeout(10)
  void a_later_quota_edit_that_omits_the_posture_keeps_the_tenant_closed() throws Exception {
    putTenant("closed-tenant", ", \"isolationPosture\": \"DENY_BY_DEFAULT\"");

    putTenant("closed-tenant", "");

    assertEquals("DENY_BY_DEFAULT", posturesByTenant().get("closed-tenant"));
  }

  @Test
  @Timeout(10)
  void an_unrecognized_isolation_posture_is_a_400() throws Exception {
    assertEquals(400, putTenant("odd-tenant", ", \"isolationPosture\": \"SOMEWHAT_CLOSED\""));
  }

  private int putTenant(String tenantId, String extraFields) throws Exception {
    String body =
        "{\"quota\": {\"maxMemoryBytes\": 1048576, \"maxCpuMillicores\": 1000,"
            + " \"maxInstances\": 5}"
            + extraFields
            + "}";
    return send(HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/" + tenantId))
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build())
        .statusCode();
  }

  private static String addCallerPatch(int expectedVersion, String tenantId) {
    return "{\"expectedVersion\": "
        + expectedVersion
        + ", \"addAllowedCallerTenantIds\": [\""
        + tenantId
        + "\"]}";
  }

  private Map<String, Object> posturesByTenant() throws Exception {
    Map<String, Object> byTenant = new LinkedHashMap<>();
    for (Object entry : Json.asArray(Json.parse(get("/networkpostures").body()))) {
      Map<String, Object> posture = Json.asObject(entry);
      byTenant.put((String) posture.get("tenantId"), posture.get("isolationPosture"));
    }
    return byTenant;
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build());
  }

  private HttpResponse<String> patch(String path, String body) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
            .build());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build());
  }
}
