package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.protocol.AuditEvent;
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
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code GET /audit}'s cursor pagination against a real store, including the case the whole design
 * turns on: the trail is a ring buffer, so the event a cursor anchors on can itself be discarded
 * while an operator is still paging towards it. Events are appended through {@link
 * InProcessStore#store()} directly rather than by driving audited writes through the API, so each
 * test controls the trail's exact contents and ordering with no dependency on when a proposed
 * mutation lands.
 */
class ApiServerAuditPaginationTest {

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

  private void appendAuditEvent(String id, String principal, long occurredAtEpochMilli) {
    inProcessStore
        .store()
        .putAuditEvent(
            new AuditEvent(
                id,
                principal,
                Set.of(),
                "DEPLOYMENT",
                "WRITE",
                Optional.of("acme"),
                Optional.of("orders"),
                true,
                occurredAtEpochMilli));
  }

  private Map<String, Object> getAudit(String query) throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/audit" + query)).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, response.statusCode(), response.body());
    return Json.asObject(Json.parse(response.body()));
  }

  private int statusOfAudit(String query) throws Exception {
    return client
        .send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/audit" + query)).GET().build(),
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
  void a_limitless_query_returns_every_matching_event_with_no_next_cursor() throws Exception {
    for (int i = 0; i < 5; i++) {
      appendAuditEvent("alice-" + i, "alice", 1_000L + i);
    }

    Map<String, Object> body = getAudit("?principal=alice");

    assertEquals(
        List.of("alice-4", "alice-3", "alice-2", "alice-1", "alice-0"),
        idsOf(body),
        "newest-first, unpaged");
    assertEquals(5, ((Number) body.get("matchedCount")).intValue());
    assertNull(body.get("nextCursor"));
    assertEquals(Boolean.FALSE, body.get("cursorExpired"));
  }

  @Test
  void following_next_cursor_walks_every_matching_event_exactly_once() throws Exception {
    for (int i = 0; i < 5; i++) {
      appendAuditEvent("alice-" + i, "alice", 1_000L + i);
    }

    List<String> walked = new ArrayList<>();
    String query = "?principal=alice&limit=2";
    int pages = 0;
    while (true) {
      Map<String, Object> body = getAudit(query);
      assertEquals(
          5, ((Number) body.get("matchedCount")).intValue(), "matchedCount is filter-wide");
      walked.addAll(idsOf(body));
      pages++;
      Object next = body.get("nextCursor");
      if (next == null) {
        break;
      }
      query = "?principal=alice&limit=2&cursor=" + next;
    }

    assertEquals(3, pages, "2 + 2 + 1");
    assertEquals(List.of("alice-4", "alice-3", "alice-2", "alice-1", "alice-0"), walked);
  }

  /**
   * The append side of the ring: an offset cursor would shift by one for every decision recorded
   * mid-walk and skip that many rows. Anchoring on an event's own identity cannot.
   */
  @Test
  void events_appended_between_pages_never_displace_the_next_page() throws Exception {
    for (int i = 0; i < 4; i++) {
      appendAuditEvent("alice-" + i, "alice", 1_000L + i);
    }

    Map<String, Object> first = getAudit("?principal=alice&limit=2");
    assertEquals(List.of("alice-3", "alice-2"), idsOf(first));

    appendAuditEvent("alice-newer-a", "alice", 9_000L);
    appendAuditEvent("alice-newer-b", "alice", 9_001L);

    Map<String, Object> second =
        getAudit("?principal=alice&limit=2&cursor=" + first.get("nextCursor"));

    assertEquals(List.of("alice-1", "alice-0"), idsOf(second));
    assertEquals(6, ((Number) second.get("matchedCount")).intValue());
    assertNull(second.get("nextCursor"));
  }

  /**
   * The eviction side of the ring, driven for real: the trail is flooded until its retention cap
   * has genuinely discarded the five oldest events between two pages, taking with it the very event
   * the first page's cursor anchored on. Because eviction only ever discards from the oldest end,
   * everything older than that anchor is gone with it -- the page really is empty, and {@code
   * cursorExpired} says why, rather than the caller being handed a plausible-looking wrong page.
   */
  @Test
  void a_cursor_whose_anchor_was_evicted_reports_expiry_instead_of_a_wrong_page() throws Exception {
    for (int i = 0; i < 5; i++) {
      appendAuditEvent("alice-" + i, "alice", 1_000L + i);
    }
    Map<String, Object> first = getAudit("?principal=alice&limit=2");
    assertEquals(List.of("alice-4", "alice-3"), idsOf(first));
    assertEquals(Boolean.FALSE, first.get("truncated"));
    String cursor = (String) first.get("nextCursor");

    // Flooding until the cap has evicted five events evicts exactly the five alice ones: they are
    // the oldest in the trail, and eviction is strictly oldest-first.
    for (long i = 0; inProcessStore.store().auditTrailStatus().evictedTotal() < 5; i++) {
      appendAuditEvent("flood-" + i, "flooder", 2_000L + i);
    }

    Map<String, Object> second = getAudit("?principal=alice&limit=2&cursor=" + cursor);

    assertEquals(List.of(), idsOf(second), "everything older than the evicted anchor is gone too");
    assertEquals(Boolean.TRUE, second.get("cursorExpired"));
    assertEquals(0, ((Number) second.get("matchedCount")).intValue());
    assertEquals(
        Boolean.TRUE, second.get("truncated"), "the trail's own retention state, distinct");
    assertTrue(((Number) second.get("evictedTotal")).longValue() >= 5);
  }

  @Test
  void a_cursor_minted_under_different_filters_is_rejected_rather_than_silently_reinterpreted()
      throws Exception {
    appendAuditEvent("alice-0", "alice", 1_000L);
    appendAuditEvent("alice-1", "alice", 1_001L);
    Map<String, Object> first = getAudit("?principal=alice&limit=1");
    String cursor = (String) first.get("nextCursor");

    assertEquals(400, statusOfAudit("?limit=1&cursor=" + cursor));
    assertEquals(400, statusOfAudit("?principal=alice&tenant=acme&limit=1&cursor=" + cursor));
    assertEquals(200, statusOfAudit("?principal=alice&limit=1&cursor=" + cursor));
  }

  @Test
  void an_unreadable_cursor_and_a_non_positive_limit_are_both_rejected() throws Exception {
    appendAuditEvent("alice-0", "alice", 1_000L);

    assertEquals(400, statusOfAudit("?cursor=not-a-real-cursor"));
    assertEquals(400, statusOfAudit("?limit=0"));
    assertEquals(400, statusOfAudit("?limit=-3"));
    assertEquals(400, statusOfAudit("?limit=abc"));
  }

  @Test
  void a_blank_cursor_parameter_is_treated_as_a_first_page() throws Exception {
    appendAuditEvent("alice-0", "alice", 1_000L);

    Map<String, Object> body = getAudit("?cursor=");

    assertEquals(List.of("alice-0"), idsOf(body));
    assertFalse((Boolean) body.get("cursorExpired"));
  }
}
