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
 * {@code POST}/{@code GET}/{@code DELETE /alertrules*} over a real loopback HTTP connection, the
 * same style {@code ApiServerNetworkPoliciesTest} already established for the sibling network-model
 * resource. Plaintext requests skip authorization entirely (see {@code
 * ApiServer#requireAuthorized}), so no dedicated RBAC test file exists here for the same reason
 * {@code ApiServerNetworkPoliciesAuthzTest}'s own javadoc gives.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerAlertRulesTest {

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

  private static String alertRuleJson(String name, String tenantId, double threshold) {
    return """
        {"name": "%s", "tenantId": "%s", "deploymentName": "checkout-service",
         "metric": "ERROR_RATE_PER_SECOND", "comparator": "GREATER_THAN", "threshold": %s,
         "webhookUrl": "https://hooks.example.com/alerts"}
        """
        .formatted(name, tenantId, threshold);
  }

  @Test
  @Timeout(10)
  void post_then_get_an_alert_rule_round_trips() throws Exception {
    HttpResponse<String> post =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules"))
                .POST(
                    HttpRequest.BodyPublishers.ofString(alertRuleJson("high-errors", "acme", 5.0)))
                .build());
    assertEquals(200, post.statusCode());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules/high-errors?tenant=acme"))
                .GET()
                .build());
    assertEquals(200, get.statusCode());
    Map<String, Object> spec = Json.asObject(Json.parse(get.body()));
    assertEquals("high-errors", spec.get("name"));
    assertEquals("acme", spec.get("tenantId"));
    assertEquals("checkout-service", spec.get("deploymentName"));
    assertEquals("ERROR_RATE_PER_SECOND", spec.get("metric"));
    assertEquals("GREATER_THAN", spec.get("comparator"));
    assertEquals(5.0, spec.get("threshold"));
    assertEquals("https://hooks.example.com/alerts", spec.get("webhookUrl"));
    assertEquals(true, spec.get("enabled"));
  }

  @Test
  @Timeout(10)
  void a_disabled_rule_round_trips_enabled_false() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"name": "silenced", "tenantId": "acme", "deploymentName": "checkout-service",
                     "metric": "QUEUE_DEPTH", "comparator": "GREATER_THAN", "threshold": 10,
                     "webhookUrl": "https://hooks.example.com/alerts", "enabled": false}
                    """))
            .build());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules/silenced?tenant=acme"))
                .GET()
                .build());
    Map<String, Object> spec = Json.asObject(Json.parse(get.body()));
    assertEquals(false, spec.get("enabled"));
  }

  @Test
  @Timeout(10)
  void get_of_an_unknown_alert_rule_is_404() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules/nope?tenant=acme"))
                .GET()
                .build());
    assertEquals(404, response.statusCode());
  }

  @Test
  @Timeout(10)
  void delete_removes_an_alert_rule() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules"))
            .POST(HttpRequest.BodyPublishers.ofString(alertRuleJson("high-errors", "acme", 5.0)))
            .build());

    HttpResponse<String> delete =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules/high-errors?tenant=acme"))
                .DELETE()
                .build());
    assertEquals(200, delete.statusCode());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules/high-errors?tenant=acme"))
                .GET()
                .build());
    assertEquals(404, get.statusCode());
  }

  @Test
  @Timeout(10)
  void alert_rules_list_endpoint_returns_every_rule() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules"))
            .POST(HttpRequest.BodyPublishers.ofString(alertRuleJson("rule-a", "acme", 5.0)))
            .build());
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules"))
            .POST(HttpRequest.BodyPublishers.ofString(alertRuleJson("rule-b", "globex", 2.5)))
            .build());

    HttpResponse<String> response =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules")).GET().build());
    assertEquals(200, response.statusCode());
    assertEquals(2, Json.asArray(Json.parse(response.body())).size());
  }

  @Test
  @Timeout(10)
  void alert_rules_list_endpoint_is_empty_with_none_submitted() throws Exception {
    HttpResponse<String> response =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules")).GET().build());
    assertEquals(200, response.statusCode());
    assertTrue(Json.asArray(Json.parse(response.body())).isEmpty());
  }

  @Test
  @Timeout(10)
  void a_missing_alert_rule_name_on_post_is_a_400() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules"))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {"tenantId": "acme", "deploymentName": "checkout-service",
                         "metric": "ERROR_RATE_PER_SECOND", "comparator": "GREATER_THAN",
                         "threshold": 5, "webhookUrl": "https://hooks.example.com/alerts"}
                        """))
                .build());
    assertEquals(400, response.statusCode());
  }

  @Test
  @Timeout(10)
  void a_blank_webhook_url_on_post_is_a_400() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules"))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {"name": "bad", "tenantId": "acme", "deploymentName": "checkout-service",
                         "metric": "ERROR_RATE_PER_SECOND", "comparator": "GREATER_THAN",
                         "threshold": 5, "webhookUrl": ""}
                        """))
                .build());
    assertEquals(400, response.statusCode());
  }

  @Test
  @Timeout(10)
  void posting_the_same_name_again_replaces_the_prior_spec() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules"))
            .POST(HttpRequest.BodyPublishers.ofString(alertRuleJson("high-errors", "acme", 5.0)))
            .build());
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules"))
            .POST(HttpRequest.BodyPublishers.ofString(alertRuleJson("high-errors", "acme", 9.0)))
            .build());

    HttpResponse<String> get =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules/high-errors?tenant=acme"))
                .GET()
                .build());
    Map<String, Object> spec = Json.asObject(Json.parse(get.body()));
    assertEquals(9.0, spec.get("threshold"));
  }

  /**
   * The same cross-replica visibility proof {@code ApiServerNetworkPoliciesTest}'s own equivalent
   * test makes: two {@code ApiServer} instances share one {@link InProcessStore} through two
   * independent {@code StoreClient} connections, proving a rule POSTed to one is visible via {@code
   * GET /alertrules} on the other.
   */
  @Test
  @Timeout(10)
  void an_alert_rule_posted_to_one_replica_is_visible_on_a_second_replica() throws Exception {
    try (StoreClient secondReplicaClient = inProcessStore.newClient();
        ApiServer secondReplica = new ApiServer(secondReplicaClient, 0, inProcessFafnir.client())) {
      secondReplica.start();
      String secondBaseUrl = "http://localhost:" + secondReplica.port();

      HttpResponse<String> post =
          send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/alertrules"))
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          alertRuleJson("high-errors", "acme", 5.0)))
                  .build());
      assertEquals(200, post.statusCode());

      HttpResponse<String> getFromSecondReplica =
          send(
              HttpRequest.newBuilder(
                      URI.create(secondBaseUrl + "/alertrules/high-errors?tenant=acme"))
                  .GET()
                  .build());
      assertEquals(200, getFromSecondReplica.statusCode());
      Map<String, Object> spec = Json.asObject(Json.parse(getFromSecondReplica.body()));
      assertEquals("high-errors", spec.get("name"));

      HttpResponse<String> listFromSecondReplica =
          send(HttpRequest.newBuilder(URI.create(secondBaseUrl + "/alertrules")).GET().build());
      assertEquals(1, Json.asArray(Json.parse(listFromSecondReplica.body())).size());
    }
  }
}
