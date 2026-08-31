package com.gimle.core.protocol;

import java.util.Optional;

/**
 * A snapshot of the cluster-wide audit trail's own retention state, riding alongside every {@code
 * GET /audit} response envelope so an operator reviewing the trail during an incident can tell
 * "this is the complete record" from "this cluster crossed the retention cap; earlier decisions are
 * gone" without cross-referencing a log line. {@code retainedCount} is the trail's total size right
 * now (independent of whatever filter a given query applied), {@code evictedTotal} is how many
 * events the ring-buffer cap has discarded over this store's lifetime, and {@code
 * oldestRetainedAtEpochMilli} is the timestamp of the single oldest event still in the trail --
 * absent only when the trail is empty.
 */
public record AuditTrailStatus(
    int retainedCount, long evictedTotal, Optional<Long> oldestRetainedAtEpochMilli) {

  public boolean truncated() {
    return evictedTotal > 0;
  }
}
