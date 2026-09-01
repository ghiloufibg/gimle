import { describe, expect, it } from "vitest";
import { describeAuditCoverage } from "./-audit";
import type { AuditPageStatus, AuditTrailStatus } from "@/types";

const COMPLETE_TRAIL: AuditTrailStatus = {
  retainedCount: 12,
  evictedTotal: 0,
  truncated: false,
};

const CAPPED_TRAIL: AuditTrailStatus = {
  retainedCount: 50_000,
  evictedTotal: 137,
  oldestRetainedAtEpochMilli: 1_755_000_000_000,
  truncated: true,
};

const firstOfMore: AuditPageStatus = { matchedCount: 412, nextCursor: "abc", cursorExpired: false };
const lastPage: AuditPageStatus = { matchedCount: 412, cursorExpired: false };

describe("describeAuditCoverage", () => {
  it("says how many rows are shown out of how many match when a page was cut short", () => {
    const coverage = describeAuditCoverage(100, firstOfMore, COMPLETE_TRAIL);

    expect(coverage.summary).toBe("showing 100 of 412 matching");
    expect(coverage.canLoadMore).toBe(true);
  });

  it("calls the result complete once every matching event is on screen", () => {
    const coverage = describeAuditCoverage(412, lastPage, COMPLETE_TRAIL);

    expect(coverage.summary).toBe("412 of 412 matching — complete");
    expect(coverage.canLoadMore).toBe(false);
  });

  it("falls back to the rows in hand before any query has reported a match count", () => {
    const coverage = describeAuditCoverage(0, null, null);

    expect(coverage.summary).toBe("0 of 0 matching — complete");
    expect(coverage.canLoadMore).toBe(false);
    expect(coverage.pagingGapNotice).toBeNull();
    expect(coverage.retentionNotice).toBeNull();
  });

  /**
   * The two ways the rows can be incomplete are independent and must not be conflated: a capped
   * trail is about the cluster's whole record, an expired cursor is about this operator's own walk
   * through it.
   */
  it("reports a trail past its retention cap separately from an intact walk through it", () => {
    const coverage = describeAuditCoverage(100, firstOfMore, CAPPED_TRAIL);

    expect(coverage.pagingGapNotice).toBeNull();
    expect(coverage.retentionNotice).toContain("137 older event(s) have been discarded");
    expect(coverage.retentionNotice).toContain("retaining 50,000");
    expect(coverage.retentionNotice).toContain("2025-08-12T12:00:00.000Z");
  });

  it("reports a cursor evicted mid-walk as its own, non-recoverable gap", () => {
    const coverage = describeAuditCoverage(
      100,
      { matchedCount: 100, cursorExpired: true },
      CAPPED_TRAIL,
    );

    expect(coverage.pagingGapNotice).toContain("discarded by the audit trail's retention cap");
    expect(coverage.pagingGapNotice).toContain("gone from the cluster");
    expect(coverage.canLoadMore).toBe(false);
    expect(coverage.retentionNotice).not.toBeNull();
  });
});
