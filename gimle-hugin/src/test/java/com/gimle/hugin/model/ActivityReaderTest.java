package com.gimle.hugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Turning the audit trail's own response into rows, including the shapes that arrive incomplete.
 */
class ActivityReaderTest {

  @Test
  void events_are_read_newest_first_whatever_order_they_arrive_in() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                "/audit?limit=200",
                Map.of("events", List.of(event("alice", 1000L), event("bob", 5000L))));

    ActivitySnapshot snapshot = new ActivityReader(reader).read();

    assertEquals(
        List.of("bob", "alice"), snapshot.events().stream().map(ActivityRow::principal).toList());
    assertTrue(snapshot.connected());
    assertTrue(snapshot.permitted());
  }

  @Test
  void an_event_with_no_principal_is_dropped_rather_than_taking_the_page_with_it() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                "/audit?limit=200",
                Map.of("events", List.of(Map.of("verb", "CREATE"), event("alice", 1000L))));

    assertEquals(1, new ActivityReader(reader).read().events().size());
  }

  @Test
  void a_response_with_no_events_key_reads_as_an_empty_trail_not_a_failure() {
    FakeClusterReader reader = new FakeClusterReader().withObject("/audit?limit=200", Map.of());

    ActivitySnapshot snapshot = new ActivityReader(reader).read();

    assertTrue(snapshot.events().isEmpty());
    assertTrue(snapshot.connected());
  }

  @Test
  void a_denied_decision_reads_as_denied_and_a_rejected_one_separately() {
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

    List<ActivityRow> events = new ActivityReader(reader).read().events();

    // Refused for want of permission, and refused on its merits, are different things to see.
    assertEquals("DENIED", events.getFirst().verdict());
    assertEquals("REJECTED", events.getLast().verdict());
  }

  private static Map<String, Object> event(final String principal, final long atEpochMilli) {
    return Map.of(
        "principal", principal,
        "resourceKind", "DEPLOYMENT",
        "verb", "CREATE",
        "targetId", "checkout-api",
        "allowed", true,
        "outcome", "APPLIED",
        "occurredAtEpochMilli", atEpochMilli);
  }
}
