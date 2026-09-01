package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.AuditEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The cursor arithmetic {@code GET /audit} pages the ring-buffered audit trail with. */
class AuditPageTest {

  private static final String FINGERPRINT =
      AuditCursor.fingerprintOf(
          Optional.of("alice"), Optional.empty(), Optional.empty(), Optional.empty());

  private static AuditEvent event(String id, long occurredAtEpochMilli) {
    return new AuditEvent(
        id,
        "alice",
        Set.of(),
        "DEPLOYMENT",
        "WRITE",
        Optional.empty(),
        Optional.empty(),
        true,
        occurredAtEpochMilli);
  }

  /** Newest-first, matching {@code StoreReader#listAuditEvents}' own order. */
  private static List<AuditEvent> trail(int count) {
    List<AuditEvent> events = new ArrayList<>();
    for (int i = count - 1; i >= 0; i--) {
      events.add(event("e-" + i, 1_000L + i));
    }
    return List.copyOf(events);
  }

  private static List<String> idsOf(AuditPage page) {
    return page.events().stream().map(AuditEvent::id).toList();
  }

  @Test
  void a_first_page_smaller_than_the_match_set_reports_the_full_match_count_and_a_next_cursor() {
    AuditPage page = AuditPage.of(trail(5), Optional.empty(), 2, FINGERPRINT);

    assertEquals(List.of("e-4", "e-3"), idsOf(page));
    assertEquals(5, page.matchedCount());
    assertTrue(page.nextCursor().isPresent());
    assertFalse(page.cursorExpired());
  }

  @Test
  void the_last_page_carries_no_next_cursor_even_when_it_is_exactly_full() {
    AuditPage first = AuditPage.of(trail(4), Optional.empty(), 2, FINGERPRINT);
    AuditPage second = AuditPage.of(trail(4), first.nextCursor(), 2, FINGERPRINT);

    assertEquals(List.of("e-1", "e-0"), idsOf(second));
    assertTrue(second.nextCursor().isEmpty());
  }

  @Test
  void an_unlimited_page_returns_everything_without_overflowing_its_own_bounds() {
    AuditPage page = AuditPage.of(trail(3), Optional.empty(), Integer.MAX_VALUE, FINGERPRINT);

    assertEquals(List.of("e-2", "e-1", "e-0"), idsOf(page));
    assertTrue(page.nextCursor().isEmpty());
  }

  /**
   * The anchor's own eviction, in the only form the pagination code can observe it: a cursor naming
   * an event the current match set no longer contains. Eviction is strictly oldest-first, so
   * everything the page would have held went with the anchor -- an empty page is the honest answer,
   * and {@code cursorExpired} is what separates it from having simply reached the end.
   */
  @Test
  void a_cursor_anchored_on_an_evicted_event_yields_an_empty_page_flagged_as_expired() {
    AuditPage first = AuditPage.of(trail(5), Optional.empty(), 2, FINGERPRINT);
    List<AuditEvent> afterEviction = trail(5).subList(0, 2); // e-4, e-3 -- the anchor e-3 included
    List<AuditEvent> anchorAlsoEvicted = trail(5).subList(0, 1);

    AuditPage stillAnchored = AuditPage.of(afterEviction, first.nextCursor(), 2, FINGERPRINT);
    AuditPage expired = AuditPage.of(anchorAlsoEvicted, first.nextCursor(), 2, FINGERPRINT);

    assertEquals(List.of(), idsOf(stillAnchored), "anchor still present: a genuine end-of-trail");
    assertFalse(stillAnchored.cursorExpired());
    assertEquals(List.of(), idsOf(expired));
    assertTrue(expired.cursorExpired());
    assertEquals(1, expired.matchedCount());
  }

  @Test
  void a_cursor_minted_under_a_different_filter_set_is_refused() {
    AuditPage first = AuditPage.of(trail(3), Optional.empty(), 1, FINGERPRINT);
    String otherFilters =
        AuditCursor.fingerprintOf(
            Optional.of("bjorn"), Optional.empty(), Optional.empty(), Optional.empty());

    assertThrows(
        IllegalArgumentException.class,
        () -> AuditPage.of(trail(3), first.nextCursor(), 1, otherFilters));
  }

  @Test
  void an_unreadable_cursor_or_a_non_positive_limit_is_refused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AuditPage.of(trail(3), Optional.of("!!not-base64!!"), 1, FINGERPRINT));
    assertThrows(
        IllegalArgumentException.class,
        () -> AuditPage.of(trail(3), Optional.empty(), 0, FINGERPRINT));
  }

  @Test
  void a_cursor_round_trips_its_event_id_and_fingerprint_through_encoding() {
    AuditCursor cursor = new AuditCursor("id with|pipe and %25", FINGERPRINT);

    AuditCursor decoded = AuditCursor.decode(cursor.encode());

    assertEquals(cursor, decoded);
  }

  /**
   * Filter values are escaped individually, so no single value can spell out the fingerprint of a
   * different combination by containing the separator itself.
   */
  @Test
  void filter_values_containing_the_separator_do_not_collide_across_combinations() {
    String first =
        AuditCursor.fingerprintOf(
            Optional.of("alice|DEPLOYMENT"), Optional.empty(), Optional.empty(), Optional.empty());
    String second =
        AuditCursor.fingerprintOf(
            Optional.of("alice"), Optional.of("DEPLOYMENT"), Optional.empty(), Optional.empty());

    assertNotEquals(first, second);
  }
}
