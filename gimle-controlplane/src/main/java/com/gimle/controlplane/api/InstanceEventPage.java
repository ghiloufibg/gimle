package com.gimle.controlplane.api;

import com.gimle.core.protocol.InstanceEvent;
import java.util.List;
import java.util.Optional;

/**
 * One page of the cluster-wide lifecycle-event read, cut out of a single newest-first snapshot of
 * every matching event -- the same cursor arithmetic {@link AuditPage} already established for
 * {@code GET /audit}, mirrored here rather than shared so a change to one endpoint's pagination can
 * never silently ripple into the other's.
 *
 * <p>{@code matchedCount} counts the whole snapshot, not this page, so a caller can say "showing 50
 * of 900 matching events" rather than only ever "50 rows". {@code cursorExpired} is distinct from
 * an empty last page: it means the event the caller's cursor anchored on has since been pruned from
 * its own instance's timeline -- since pruning is oldest-first per instance, everything older than
 * that anchor within this same filtered view is gone too, so the page is genuinely and permanently
 * empty rather than merely at the end.
 */
record InstanceEventPage(
    List<InstanceEvent> events,
    int matchedCount,
    Optional<String> nextCursor,
    boolean cursorExpired) {

  InstanceEventPage {
    events = List.copyOf(events);
  }

  /**
   * @param matching every matching event, newest-first
   * @param rawCursor the {@code cursor} query parameter, absent for a first page
   * @param limit the maximum number of events this page may carry, at least 1
   * @param filterFingerprint {@link InstanceEventCursor#fingerprintOf} over this request's own
   *     filters
   * @throws IllegalArgumentException if the cursor is unreadable or was minted under different
   *     filters -- paging on it would silently answer a different question than the caller asked
   */
  static InstanceEventPage of(
      List<InstanceEvent> matching,
      Optional<String> rawCursor,
      int limit,
      String filterFingerprint) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be at least 1");
    }
    int start = 0;
    boolean cursorExpired = false;
    if (rawCursor.isPresent()) {
      InstanceEventCursor cursor = InstanceEventCursor.decode(rawCursor.get());
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
    List<InstanceEvent> events = List.copyOf(matching.subList(start, end));
    Optional<String> nextCursor =
        events.isEmpty() || end >= matching.size()
            ? Optional.empty()
            : Optional.of(
                new InstanceEventCursor(events.get(events.size() - 1).id(), filterFingerprint)
                    .encode());
    return new InstanceEventPage(events, matching.size(), nextCursor, cursorExpired);
  }

  private static int indexOfEvent(List<InstanceEvent> events, String eventId) {
    for (int i = 0; i < events.size(); i++) {
      if (eventId.equals(events.get(i).id())) {
        return i;
      }
    }
    return -1;
  }
}
