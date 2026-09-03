package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.InstanceEventKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The cursor arithmetic the cluster-wide {@code GET /events} mode pages the merged timeline with.
 */
class InstanceEventPageTest {

  private static final String FINGERPRINT =
      InstanceEventCursor.fingerprintOf(Optional.of("acme"), Optional.empty());

  private static InstanceEvent event(String id, long occurredAtEpochMilli) {
    return new InstanceEvent(
        id, "orders-service", 0, InstanceEventKind.ACTIVE, "module active", occurredAtEpochMilli);
  }

  /**
   * Newest-first, matching {@code StateStore#listInstanceEvents(Optional, Optional)}'s own order.
   */
  private static List<InstanceEvent> timeline(int count) {
    List<InstanceEvent> events = new ArrayList<>();
    for (int i = count - 1; i >= 0; i--) {
      events.add(event("e-" + i, 1_000L + i));
    }
    return List.copyOf(events);
  }

  private static List<String> idsOf(InstanceEventPage page) {
    return page.events().stream().map(InstanceEvent::id).toList();
  }

  @Test
  void a_first_page_smaller_than_the_match_set_reports_the_full_match_count_and_a_next_cursor() {
    InstanceEventPage page = InstanceEventPage.of(timeline(5), Optional.empty(), 2, FINGERPRINT);

    assertEquals(List.of("e-4", "e-3"), idsOf(page));
    assertEquals(5, page.matchedCount());
    assertTrue(page.nextCursor().isPresent());
    assertFalse(page.cursorExpired());
  }

  @Test
  void the_last_page_carries_no_next_cursor_even_when_it_is_exactly_full() {
    InstanceEventPage first = InstanceEventPage.of(timeline(4), Optional.empty(), 2, FINGERPRINT);
    InstanceEventPage second =
        InstanceEventPage.of(timeline(4), first.nextCursor(), 2, FINGERPRINT);

    assertEquals(List.of("e-1", "e-0"), idsOf(second));
    assertTrue(second.nextCursor().isEmpty());
  }

  @Test
  void an_unlimited_page_returns_everything_without_overflowing_its_own_bounds() {
    InstanceEventPage page =
        InstanceEventPage.of(timeline(3), Optional.empty(), Integer.MAX_VALUE, FINGERPRINT);

    assertEquals(List.of("e-2", "e-1", "e-0"), idsOf(page));
    assertTrue(page.nextCursor().isEmpty());
  }

  /**
   * The anchor's own eviction, in the only form the pagination code can observe it: a cursor naming
   * an event the current match set no longer contains, because the per-instance timeline it
   * belonged to pruned it. Eviction is strictly oldest-first per instance, so within this merged,
   * filtered view everything older than the anchor went with it -- an empty page is the honest
   * answer, and {@code cursorExpired} is what separates it from having simply reached the end.
   */
  @Test
  void a_cursor_anchored_on_an_evicted_event_yields_an_empty_page_flagged_as_expired() {
    InstanceEventPage first = InstanceEventPage.of(timeline(5), Optional.empty(), 2, FINGERPRINT);
    List<InstanceEvent> afterEviction =
        timeline(5).subList(0, 2); // e-4, e-3 -- anchor e-3 included
    List<InstanceEvent> anchorAlsoEvicted = timeline(5).subList(0, 1);

    InstanceEventPage stillAnchored =
        InstanceEventPage.of(afterEviction, first.nextCursor(), 2, FINGERPRINT);
    InstanceEventPage expired =
        InstanceEventPage.of(anchorAlsoEvicted, first.nextCursor(), 2, FINGERPRINT);

    assertEquals(
        List.of(), idsOf(stillAnchored), "anchor still present: a genuine end-of-timeline");
    assertFalse(stillAnchored.cursorExpired());
    assertEquals(List.of(), idsOf(expired));
    assertTrue(expired.cursorExpired());
    assertEquals(1, expired.matchedCount());
  }

  @Test
  void a_cursor_minted_under_a_different_filter_set_is_refused() {
    InstanceEventPage first = InstanceEventPage.of(timeline(3), Optional.empty(), 1, FINGERPRINT);
    String otherFilters =
        InstanceEventCursor.fingerprintOf(Optional.of("globex"), Optional.empty());

    assertThrows(
        IllegalArgumentException.class,
        () -> InstanceEventPage.of(timeline(3), first.nextCursor(), 1, otherFilters));
  }

  @Test
  void an_unreadable_cursor_or_a_non_positive_limit_is_refused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> InstanceEventPage.of(timeline(3), Optional.of("!!not-base64!!"), 1, FINGERPRINT));
    assertThrows(
        IllegalArgumentException.class,
        () -> InstanceEventPage.of(timeline(3), Optional.empty(), 0, FINGERPRINT));
  }

  @Test
  void a_cursor_round_trips_its_event_id_and_fingerprint_through_encoding() {
    InstanceEventCursor cursor = new InstanceEventCursor("id with|pipe and %25", FINGERPRINT);

    InstanceEventCursor decoded = InstanceEventCursor.decode(cursor.encode());

    assertEquals(cursor, decoded);
  }

  /**
   * Filter values are escaped individually, so no single value can spell out the fingerprint of a
   * different combination by containing the separator itself.
   */
  @Test
  void filter_values_containing_the_separator_do_not_collide_across_combinations() {
    String first = InstanceEventCursor.fingerprintOf(Optional.of("acme|1000"), Optional.empty());
    String second = InstanceEventCursor.fingerprintOf(Optional.of("acme"), Optional.of(1000L));

    assertNotEquals(first, second);
  }
}
