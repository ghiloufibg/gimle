package com.gimle.controlplane.api;

import com.gimle.core.protocol.AuditEvent;
import java.util.List;
import java.util.Optional;

/**
 * One page of the cross-resource audit trail, cut out of a single newest-first snapshot of every
 * retained event that matched the caller's filters.
 *
 * <p>{@code matchedCount} counts that whole snapshot, not this page -- it is what lets a caller say
 * "showing 100 of 412 matching decisions" instead of only ever "100 rows", which is the difference
 * between a trail an operator can trust during an incident and one that quietly stops at whatever
 * limit happened to be set.
 *
 * <p>{@code cursorExpired} is a distinct condition from an empty last page: it means the event the
 * caller's cursor anchored on is no longer in the trail. Eviction only ever discards from the
 * oldest end, so an anchor that is gone implies everything older than it is gone too -- the page is
 * genuinely and permanently empty, and the caller is told the gap is the retention cap's doing
 * rather than the end of the record.
 */
record AuditPage(
    List<AuditEvent> events, int matchedCount, Optional<String> nextCursor, boolean cursorExpired) {

  AuditPage {
    events = List.copyOf(events);
  }

  /**
   * @param matching every retained event matching the caller's filters, newest-first
   * @param rawCursor the {@code cursor} query parameter, absent for a first page
   * @param limit the maximum number of events this page may carry, at least 1
   * @param filterFingerprint {@link AuditCursor#fingerprintOf} over this request's own filters
   * @throws IllegalArgumentException if the cursor is unreadable or was minted under different
   *     filters -- paging on it would silently answer a different question than the caller asked
   */
  static AuditPage of(
      List<AuditEvent> matching, Optional<String> rawCursor, int limit, String filterFingerprint) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be at least 1");
    }
    int start = 0;
    boolean cursorExpired = false;
    if (rawCursor.isPresent()) {
      AuditCursor cursor = AuditCursor.decode(rawCursor.get());
      if (!filterFingerprint.equals(cursor.filterFingerprint())) {
        throw new IllegalArgumentException(
            "cursor was issued for a different filter set; start a new query");
      }
      int anchor = indexOfEvent(matching, cursor.eventId());
      if (anchor < 0) {
        cursorExpired = true;
        start = matching.size();
      } else {
        start = anchor + 1;
      }
    }
    // A limit of Integer.MAX_VALUE ("no limit", the default) would overflow a plain int addition.
    int end = (int) Math.min(matching.size(), (long) start + limit);
    List<AuditEvent> events = List.copyOf(matching.subList(start, end));
    Optional<String> nextCursor =
        events.isEmpty() || end >= matching.size()
            ? Optional.empty()
            : Optional.of(
                new AuditCursor(events.get(events.size() - 1).id(), filterFingerprint).encode());
    return new AuditPage(events, matching.size(), nextCursor, cursorExpired);
  }

  private static int indexOfEvent(List<AuditEvent> events, String eventId) {
    for (int i = 0; i < events.size(); i++) {
      if (eventId.equals(events.get(i).id())) {
        return i;
      }
    }
    return -1;
  }
}
