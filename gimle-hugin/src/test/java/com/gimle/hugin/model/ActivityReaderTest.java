package com.gimle.hugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.cli.CliException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Turning each of the three activity feeds into rows, including the shapes that arrive broken. */
class ActivityReaderTest {

  // ---- audit ----

  @Test
  void audit_decisions_are_read_newest_first_whatever_order_they_arrive_in() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                "/audit?limit=200",
                Map.of("events", List.of(auditEvent("alice", 1000L), auditEvent("bob", 5000L))));

    ActivitySnapshot snapshot = new ActivityReader(reader, FeedMode.AUDIT).read();

    assertEquals(List.of("bob", "alice"), snapshot.events().stream().map(FeedRow::actor).toList());
    assertEquals(FeedMode.AUDIT, snapshot.mode());
    assertTrue(snapshot.permitted());
  }

  @Test
  void a_decision_refused_for_permission_reads_differently_from_one_refused_on_its_merits() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                "/audit?limit=200",
                Map.of(
                    "events",
                    List.of(
                        Map.of(
                            "principal", "mallory",
                            "resourceKind", "SECRET",
                            "verb", "DELETE",
                            "allowed", false,
                            "outcome", "REJECTED",
                            "occurredAtEpochMilli", 1000L),
                        Map.of(
                            "principal", "bob",
                            "resourceKind", "TENANT",
                            "verb", "UPDATE",
                            "allowed", true,
                            "outcome", "REJECTED",
                            "occurredAtEpochMilli", 900L))));

    List<FeedRow> events = new ActivityReader(reader, FeedMode.AUDIT).read().events();

    assertEquals("DENIED", events.getFirst().verdict());
    assertEquals("REJECTED", events.getLast().verdict());
  }

  @Test
  void an_audit_event_with_no_principal_is_dropped_rather_than_taking_the_page_with_it() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                "/audit?limit=200",
                Map.of("events", List.of(Map.of("verb", "CREATE"), auditEvent("alice", 1000L))));

    assertEquals(1, new ActivityReader(reader, FeedMode.AUDIT).read().events().size());
  }

  @Test
  void a_response_with_no_events_key_reads_as_an_empty_trail_not_a_failure() {
    FakeClusterReader reader = new FakeClusterReader().withObject("/audit?limit=200", Map.of());

    ActivitySnapshot snapshot = new ActivityReader(reader, FeedMode.AUDIT).read();

    assertTrue(snapshot.events().isEmpty());
    assertTrue(snapshot.connected());
  }

  @Test
  void a_refusal_of_permission_is_a_state_to_report_rather_than_an_error_to_throw() {
    // Every feed is gated on a permission of its own, and a caller lacking one must be told that
    // rather than shown a feed that failed to load or, worse, an empty one.
    for (FeedMode mode : FeedMode.values()) {
      FakeClusterReader reader = new FakeClusterReader();
      reader.failWith(CliException.forbidden("forbidden"));

      ActivitySnapshot snapshot = new ActivityReader(reader, mode).read();

      assertFalse(snapshot.permitted(), mode.toString());
      assertTrue(snapshot.events().isEmpty(), mode.toString());
    }
  }

  @Test
  void any_other_failure_is_left_to_the_poller_to_report_as_a_stale_reading() {
    FakeClusterReader reader = new FakeClusterReader();
    reader.failWith(CliException.unavailable("connection refused"));

    assertThrows(
        CliException.class, () -> new ActivityReader(reader, FeedMode.AUDIT).read());
  }

  // ---- paging, shared by the two history feeds ----

  @Test
  void a_history_with_more_pages_says_so_and_load_more_asks_for_the_next_one() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                "/events?limit=200",
                Map.of("events", List.of(lifecycleEvent("checkout-api", 5000L)), "nextCursor", "c1"))
            .withObject(
                "/events?limit=200&cursor=c1",
                Map.of("events", List.of(lifecycleEvent("billing-api", 1000L))));

    ActivityReader activityReader = new ActivityReader(reader, FeedMode.LIFECYCLE);
    ActivitySnapshot firstPage = activityReader.read();
    assertTrue(firstPage.hasMore());
    assertEquals(1, firstPage.events().size());

    activityReader.loadMore();
    ActivitySnapshot bothPages = activityReader.read();
    assertEquals(
        List.of("checkout-api/0", "billing-api/0"),
        bothPages.events().stream().map(FeedRow::actor).toList());
    assertFalse(bothPages.hasMore());

    // A refresh re-reads everything already on screen rather than shrinking back to one page.
    assertEquals(
        List.of("checkout-api/0", "billing-api/0"),
        activityReader.read().events().stream().map(FeedRow::actor).toList());
  }

  @Test
  void a_single_page_history_reports_nothing_more_to_load() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject("/audit?limit=200", Map.of("events", List.of(auditEvent("alice", 1000L))));

    assertFalse(new ActivityReader(reader, FeedMode.AUDIT).read().hasMore());
  }

  // ---- lifecycle ----

  @Test
  void lifecycle_transitions_are_read_from_the_cluster_wide_events_route() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                "/events?limit=200",
                Map.of(
                    "events",
                    List.of(
                        Map.of(
                            "deploymentName", "checkout-api",
                            "instanceIndex", 2,
                            "kind", "TRANSITION_FAILED",
                            "message", "probe timed out",
                            "causeSummary", "liveness",
                            "occurredAtEpochMilli", 2000L))));

    FeedRow row = new ActivityReader(reader, FeedMode.LIFECYCLE).read().events().getFirst();

    assertEquals("checkout-api/2", row.actor());
    assertEquals("TRANSITION_FAILED", row.verdict());
    assertTrue(row.subject().contains("probe timed out"), row.subject());
    assertTrue(row.subject().contains("liveness"), row.subject());
  }

  @Test
  void a_lifecycle_event_naming_no_deployment_is_dropped() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject("/events?limit=200", Map.of("events", List.of(Map.of("kind", "ACTIVE"))));

    assertTrue(new ActivityReader(reader, FeedMode.LIFECYCLE).read().events().isEmpty());
  }

  // ---- alerts ----

  @Test
  void an_enabled_rule_is_asked_whether_it_is_firing_and_firing_ones_sort_first() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList("/alertrules", List.of(rule("quiet-rule"), rule("loud-rule")))
            .withObject("/alertrules/quiet-rule/firing", Map.of("known", true, "firing", false))
            .withObject("/alertrules/loud-rule/firing", Map.of("known", true, "firing", true));

    List<FeedRow> rows = new ActivityReader(reader, FeedMode.ALERTS).read().events();

    assertEquals(List.of("loud-rule", "quiet-rule"), rows.stream().map(FeedRow::actor).toList());
    assertEquals("FIRING", rows.getFirst().verdict());
    assertEquals("OK", rows.getLast().verdict());
  }

  @Test
  void a_disabled_rule_says_so_without_being_asked_at_all() {
    // A disabled rule never fires and never resolves, so asking would only invite a wrong answer.
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList(
                "/alertrules",
                List.of(
                    Map.of(
                        "name", "off-rule",
                        "deploymentName", "checkout-api",
                        "metric", "ERROR_RATE_PER_SECOND",
                        "enabled", false)));

    ActivitySnapshot snapshot = new ActivityReader(reader, FeedMode.ALERTS).read();

    assertEquals("DISABLED", snapshot.events().getFirst().verdict());
    assertFalse(reader.requestedPaths().contains("/alertrules/off-rule/firing"));
  }

  @Test
  void a_rule_the_control_plane_has_no_reading_for_is_unknown_rather_than_quiet() {
    // Reporting "not firing" for a state nobody knows would be the one wrong answer here.
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList("/alertrules", List.of(rule("new-rule")))
            .withObject("/alertrules/new-rule/firing", Map.of("known", false));

    assertEquals(
        "UNKNOWN",
        new ActivityReader(reader, FeedMode.ALERTS).read().events().getFirst().verdict());
  }

  @Test
  void the_alert_feed_never_pages_because_it_is_a_list_of_rules_not_a_history() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList("/alertrules", List.of(rule("a-rule")))
            .withObject("/alertrules/a-rule/firing", Map.of("known", true, "firing", false));

    assertFalse(new ActivityReader(reader, FeedMode.ALERTS).read().hasMore());
  }

  private static Map<String, Object> auditEvent(final String principal, final long atEpochMilli) {
    return Map.of(
        "principal", principal,
        "resourceKind", "DEPLOYMENT",
        "verb", "CREATE",
        "targetId", "checkout-api",
        "allowed", true,
        "outcome", "APPLIED",
        "occurredAtEpochMilli", atEpochMilli);
  }

  private static Map<String, Object> lifecycleEvent(
      final String deployment, final long atEpochMilli) {
    return Map.of(
        "deploymentName", deployment,
        "instanceIndex", 0,
        "kind", "ACTIVE",
        "message", "started",
        "occurredAtEpochMilli", atEpochMilli);
  }

  private static Map<String, Object> rule(final String name) {
    return Map.of(
        "name",
        name,
        "deploymentName",
        "checkout-api",
        "metric",
        "ERROR_RATE_PER_SECOND",
        "comparator",
        "GREATER_THAN",
        "threshold",
        5.0,
        "enabled",
        true);
  }
}
