import type { AuditPageStatus, AuditTrailStatus } from "@/types";

/**
 * What the Audit screen must say about how complete the rows on it are. The trail is a fixed-size
 * ring behind a paged read, so "incomplete" has two entirely different causes and they need
 * different words: the operator has simply not paged to the end yet (recoverable -- keep clicking),
 * or the retention cap discarded events (not recoverable -- they are gone from the cluster). Rows
 * that stop without either being said is the failure this screen exists to avoid.
 */
export interface AuditCoverage {
  /** Always shown: how many rows are on screen out of how many match the filters. */
  summary: string;
  canLoadMore: boolean;
  /** The page the operator asked for was evicted out from under their own cursor. */
  pagingGapNotice: string | null;
  /** The trail as a whole has crossed its retention cap, whatever this query asked for. */
  retentionNotice: string | null;
}

export function describeAuditCoverage(
  shown: number,
  page: AuditPageStatus | null,
  status: AuditTrailStatus | null,
): AuditCoverage {
  const matched = page?.matchedCount ?? shown;
  return {
    summary:
      shown === matched
        ? `${shown.toLocaleString()} of ${matched.toLocaleString()} matching — complete`
        : `showing ${shown.toLocaleString()} of ${matched.toLocaleString()} matching`,
    canLoadMore: Boolean(page?.nextCursor),
    pagingGapNotice: page?.cursorExpired
      ? "The next page was discarded by the audit trail's retention cap while you were reading." +
        " Every event older than the last row below is gone from the cluster — re-run the query to" +
        " see what is still retained."
      : null,
    retentionNotice: retentionNotice(status),
  };
}

function retentionNotice(status: AuditTrailStatus | null): string | null {
  if (!status?.truncated) return null;
  const oldest = status.oldestRetainedAtEpochMilli;
  const from =
    oldest === undefined ? "" : ` The trail now starts at ${new Date(oldest).toISOString()}.`;
  return (
    `The audit trail has exceeded its retention cap — ${status.evictedTotal.toLocaleString()}` +
    ` older event(s) have been discarded, retaining ${status.retainedCount.toLocaleString()}.` +
    from +
    " This is the trail's own state, independent of the filters below."
  );
}
