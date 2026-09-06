package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * {@code PUT}/{@code GET}/{@code DELETE /limitranges/{tenantId}} plus {@code GET /limitranges} over
 * a real loopback HTTP connection, the same PUT-by-identity shape {@code ApiServerTest}'s own
 * {@code tenant_put_get_list_delete_round_trips} exercises for {@code Tenant} -- not {@code
 * ApiServerNetworkPoliciesTest}'s POST-to-collection shape, since a LimitRange is one-per-tenant,
 * keyed by {@code tenantId} directly. RBAC coverage for {@link
 * com.gimle.core.authz.ResourceKind#LIMIT_RANGE} lives separately in {@code
 * ApiServerLimitRangesAuthzTest} -- plaintext requests skip authorization entirely (see {@code
 * ApiServer#requireAuthorized}), so RBAC only means anything once a real mTLS connection is in
 * play.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerLimitRangesTest {

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

  private static String limitRangeJson(
      String minRequestMemory, String minRequestCpu, String maxLimitMemory, String maxLimitCpu) {
    return """
        {
          "minRequest": {"memory": "%s", "cpu": "%s"},
          "maxLimit": {"memory": "%s", "cpu": "%s"}
        }
        """
        .formatted(minRequestMemory, minRequestCpu, maxLimitMemory, maxLimitCpu);
  }

  @Test
  @Timeout(10)
  void put_then_get_a_limit_range_round_trips() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges/acme"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        limitRangeJson("64Mi", "50m", "512Mi", "500m")))
                .build());
    assertEquals(200, put.statusCode());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges/acme")).GET().build());
    assertEquals(200, get.statusCode());
    Map<String, Object> spec = Json.asObject(Json.parse(get.body()));
    assertEquals("acme", spec.get("tenantId"));
    Map<String, Object> minRequest = Json.asObject(spec.get("minRequest"));
    assertEquals("64Mi", minRequest.get("memory"));
    Map<String, Object> maxLimit = Json.asObject(spec.get("maxLimit"));
    assertEquals("512Mi", maxLimit.get("memory"));
  }

  @Test
  @Timeout(10)
  void a_limit_range_with_only_some_bounds_omits_the_rest_from_the_response() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges/acme"))
            .PUT(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"minRequest": {"memory": "1Mi", "cpu": "1m"}}
                    """))
            .build());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges/acme")).GET().build());
    Map<String, Object> spec = Json.asObject(Json.parse(get.body()));
    assertTrue(spec.containsKey("minRequest"));
    assertEquals(false, spec.containsKey("maxRequest"));
    assertEquals(false, spec.containsKey("minLimit"));
    assertEquals(false, spec.containsKey("maxLimit"));
  }

  @Test
  @Timeout(10)
  void get_of_an_unknown_limit_range_is_404() throws Exception {
    HttpResponse<String> response =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges/nope")).GET().build());
    assertEquals(404, response.statusCode());
  }

  @Test
  @Timeout(10)
  void delete_removes_a_limit_range() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges/acme"))
            .PUT(HttpRequest.BodyPublishers.ofString(limitRangeJson("1Mi", "1m", "512Mi", "500m")))
            .build());

    HttpResponse<String> delete =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges/acme")).DELETE().build());
    assertEquals(200, delete.statusCode());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges/acme")).GET().build());
    assertEquals(404, get.statusCode());
  }

  @Test
  @Timeout(10)
  void limit_ranges_list_endpoint_returns_every_range() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges/acme"))
            .PUT(HttpRequest.BodyPublishers.ofString(limitRangeJson("1Mi", "1m", "512Mi", "500m")))
            .build());
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges/globex"))
            .PUT(HttpRequest.BodyPublishers.ofString(limitRangeJson("2Mi", "2m", "256Mi", "250m")))
            .build());

    HttpResponse<String> response =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges")).GET().build());
    assertEquals(200, response.statusCode());
    assertEquals(2, Json.asArray(Json.parse(response.body())).size());
  }

  @Test
  @Timeout(10)
  void limit_ranges_list_endpoint_is_empty_with_none_submitted() throws Exception {
    HttpResponse<String> response =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges")).GET().build());
    assertEquals(200, response.statusCode());
    assertTrue(Json.asArray(Json.parse(response.body())).isEmpty());
  }

  @Test
  @Timeout(10)
  void a_min_above_max_bound_is_a_400() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges/acme"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {
                          "minRequest": {"memory": "512Mi", "cpu": "500m"},
                          "maxRequest": {"memory": "1Mi", "cpu": "1m"}
                        }
                        """))
                .build());
    assertEquals(400, response.statusCode());
  }

  @Test
  @Timeout(10)
  void a_bound_missing_half_its_pair_is_a_400() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges/acme"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {"minRequest": {"memory": "64Mi"}}
                        """))
                .build());
    assertEquals(400, response.statusCode());
  }

  /**
   * The flat spelling mirrors {@code gimle set limitrange}'s own flag names, which is exactly why
   * an operator writes it into a manifest by mistake. Silently storing a boundless range under a
   * success response is the one outcome that must never happen: the operator is told their floor is
   * in force while nothing bounds the tenant at all.
   */
  @Test
  @Timeout(10)
  void a_body_using_flat_flag_shaped_field_names_is_rejected_rather_than_silently_dropped()
      throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges/acme"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {"minRequestMemory": "24Mi", "minRequestCpu": "15m"}
                        """))
                .build());
    assertEquals(400, put.statusCode(), put.body());
    assertTrue(put.body().contains("minRequestCpu"), put.body());
    assertTrue(put.body().contains("minRequestMemory"), put.body());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges/acme")).GET().build());
    assertEquals(404, get.statusCode(), "a rejected PUT must store nothing at all");
  }

  @Test
  @Timeout(10)
  void a_body_declaring_no_bound_at_all_is_rejected() throws Exception {
    HttpResponse<String> put =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges/acme"))
                .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                .build());
    assertEquals(400, put.statusCode(), put.body());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges/acme")).GET().build());
    assertEquals(404, get.statusCode());
  }

  /** A GET response handed straight back as a PUT body must round-trip, tenantId echo and all. */
  @Test
  @Timeout(10)
  void a_body_echoing_the_path_tenant_id_is_accepted_but_a_disagreeing_one_is_not()
      throws Exception {
    HttpResponse<String> echoed =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges/acme"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {"tenantId": "acme", "minRequest": {"memory": "64Mi", "cpu": "50m"}}
                        """))
                .build());
    assertEquals(200, echoed.statusCode(), echoed.body());

    HttpResponse<String> mismatched =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges/acme"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {"tenantId": "globex", "minRequest": {"memory": "64Mi", "cpu": "50m"}}
                        """))
                .build());
    assertEquals(400, mismatched.statusCode(), mismatched.body());
  }

  @Test
  @Timeout(10)
  void putting_the_same_tenant_again_replaces_the_prior_spec() throws Exception {
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges/acme"))
            .PUT(HttpRequest.BodyPublishers.ofString(limitRangeJson("1Mi", "1m", "512Mi", "500m")))
            .build());
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges/acme"))
            .PUT(HttpRequest.BodyPublishers.ofString(limitRangeJson("2Mi", "2m", "256Mi", "250m")))
            .build());

    HttpResponse<String> get =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/limitranges/acme")).GET().build());
    Map<String, Object> spec = Json.asObject(Json.parse(get.body()));
    Map<String, Object> minRequest = Json.asObject(spec.get("minRequest"));
    assertEquals("2Mi", minRequest.get("memory"));
  }
}
