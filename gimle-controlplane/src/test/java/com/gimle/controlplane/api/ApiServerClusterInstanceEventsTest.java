package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.InstanceEventKind;
import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code GET /events}'s cluster-wide mode (neither {@code deployment} nor {@code instance} given)
 * against a real store, following the exact same cursor-pagination shape {@link
 * ApiServerAuditPaginationTest} already pins for {@code GET /audit} -- events are appended through
 * {@link InProcessStore#store()} directly so each test controls the merged timeline's exact
 * contents and ordering without depending on when a relayed lifecycle event lands. RBAC gating for
 * this mode is covered separately in {@code ApiServerAuthzTest} (this class runs in plaintext,
 * which is fully open); the single-instance mode's own unchanged behavior is covered in {@code
 * ApiServerTest}.
 */
class ApiServerClusterInstanceEventsTest {

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

  private void appendInstanceEvent(
      Optional<String> tenantId, String id, String deploymentName, long occurredAtEpochMilli) {
    inProcessStore
        .store()
        .putInstanceEvent(
            tenantId,
            new InstanceEvent(
                id,
                deploymentName,
                0,
                InstanceEventKind.ACTIVE,
                "module active",
                occurredAtEpochMilli));
  }

  private Map<String, Object> getClusterEvents(String query) throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/events" + query)).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, response.statusCode(), response.body());
    return Json.asObject(Json.parse(response.body()));
  }

  private int statusOfClusterEvents(String query) throws Exception {
    return client
        .send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/events" + query)).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        .statusCode();
  }

  private static List<String> idsOf(Map<String, Object> body) {
    List<String> ids = new ArrayList<>();
    for (Map<String, Object> event : Json.asObjectList(body.get("events"))) {
      ids.add((String) event.get("id"));
    }
    return ids;
  }

  @Test
  void with_no_events_at_all_the_response_is_an_empty_page() throws Exception {
    Map<String, Object> body = getClusterEvents("");

    assertTrue(idsOf(body).isEmpty());
    assertEquals(0, ((Number) body.get("matchedCount")).intValue());
    assertNull(body.get("nextCursor"));
    assertEquals(Boolean.FALSE, body.get("cursorExpired"));
  }

  @Test
  void a_limitless_query_merges_every_deployments_own_timeline_newest_first() throws Exception {
    appendInstanceEvent(Optional.empty(), "evt-orders", "orders-service", 1_000L);
    appendInstanceEvent(Optional.empty(), "evt-ledger", "ledger", 2_000L);
    appendInstanceEvent(Optional.empty(), "evt-billing", "billing", 3_000L);

    Map<String, Object> body = getClusterEvents("");

    assertEquals(List.of("evt-billing", "evt-ledger", "evt-orders"), idsOf(body));
    assertEquals(3, ((Number) body.get("matchedCount")).intValue());
    assertNull(body.get("nextCursor"));
  }

  @Test
  void following_next_cursor_walks_every_matching_event_exactly_once() throws Exception {
    for (int i = 0; i < 5; i++) {
      appendInstanceEvent(Optional.empty(), "evt-" + i, "orders-service", 1_000L + i);
    }

    List<String> walked = new ArrayList<>();
    String query = "?limit=2";
    int pages = 0;
    while (true) {
      Map<String, Object> body = getClusterEvents(query);
      assertEquals(
          5, ((Number) body.get("matchedCount")).intValue(), "matchedCount is filter-wide");
      walked.addAll(idsOf(body));
      pages++;
      Object next = body.get("nextCursor");
      if (next == null) {
        break;
      }
      query = "?limit=2&cursor=" + next;
    }

    assertEquals(3, pages, "2 + 2 + 1");
    assertEquals(List.of("evt-4", "evt-3", "evt-2", "evt-1", "evt-0"), walked);
  }

  @Test
  void a_tenant_filter_narrows_the_merged_timeline_to_that_tenant_alone() throws Exception {
    appendInstanceEvent(Optional.of("acme"), "evt-acme", "orders-service", 1_000L);
    appendInstanceEvent(Optional.of("globex"), "evt-globex", "orders-service", 2_000L);
    appendInstanceEvent(Optional.empty(), "evt-untenanted", "orders-service", 3_000L);

    Map<String, Object> body = getClusterEvents("?tenant=acme");

    assertEquals(List.of("evt-acme"), idsOf(body));
    assertEquals(1, ((Number) body.get("matchedCount")).intValue());
  }

  @Test
  void with_no_tenant_filter_events_from_every_tenant_are_merged_together() throws Exception {
    appendInstanceEvent(Optional.of("acme"), "evt-acme", "orders-service", 1_000L);
    appendInstanceEvent(Optional.of("globex"), "evt-globex", "orders-service", 2_000L);

    Map<String, Object> body = getClusterEvents("");

    assertEquals(List.of("evt-globex", "evt-acme"), idsOf(body));
  }

  @Test
  void a_since_filter_is_an_inclusive_lower_bound() throws Exception {
    appendInstanceEvent(Optional.empty(), "evt-old", "orders-service", 1_000L);
    appendInstanceEvent(Optional.empty(), "evt-boundary", "orders-service", 2_000L);
    appendInstanceEvent(Optional.empty(), "evt-new", "orders-service", 3_000L);

    Map<String, Object> body = getClusterEvents("?since=2000");

    assertEquals(List.of("evt-new", "evt-boundary"), idsOf(body));
  }

  @Test
  void a_cursor_minted_under_different_filters_is_rejected_rather_than_silently_reinterpreted()
      throws Exception {
    appendInstanceEvent(Optional.of("acme"), "evt-0", "orders-service", 1_000L);
    appendInstanceEvent(Optional.of("acme"), "evt-1", "orders-service", 1_001L);
    Map<String, Object> first = getClusterEvents("?tenant=acme&limit=1");
    String cursor = (String) first.get("nextCursor");

    assertEquals(400, statusOfClusterEvents("?limit=1&cursor=" + cursor));
    assertEquals(400, statusOfClusterEvents("?tenant=globex&limit=1&cursor=" + cursor));
    assertEquals(200, statusOfClusterEvents("?tenant=acme&limit=1&cursor=" + cursor));
  }

  @Test
  void an_unreadable_cursor_and_a_non_positive_limit_are_both_rejected() throws Exception {
    appendInstanceEvent(Optional.empty(), "evt-0", "orders-service", 1_000L);

    assertEquals(400, statusOfClusterEvents("?cursor=not-a-real-cursor"));
    assertEquals(400, statusOfClusterEvents("?limit=0"));
    assertEquals(400, statusOfClusterEvents("?limit=-3"));
    assertEquals(400, statusOfClusterEvents("?limit=abc"));
    assertEquals(400, statusOfClusterEvents("?since=abc"));
  }

  @Test
  void a_blank_cursor_parameter_is_treated_as_a_first_page() throws Exception {
    appendInstanceEvent(Optional.empty(), "evt-0", "orders-service", 1_000L);

    Map<String, Object> body = getClusterEvents("?cursor=");

    assertEquals(List.of("evt-0"), idsOf(body));
    assertFalse((Boolean) body.get("cursorExpired"));
  }

  /**
   * The single-instance mode this endpoint already served is untouched by adding the cluster-wide
   * branch ahead of it: supplying only one of {@code deployment}/{@code instance} is still a 400,
   * never silently reinterpreted as a cluster-wide query.
   */
  @Test
  void supplying_only_one_of_deployment_and_instance_is_still_rejected() throws Exception {
    assertEquals(400, statusOfClusterEvents("?deployment=orders-service"));
    assertEquals(400, statusOfClusterEvents("?instance=0"));
  }
}
