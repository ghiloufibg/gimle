import type { AuditFilter, AuditQueryResult } from "@/types";

export interface AuditRepository {
  /** GET /audit -- an envelope of {@code events} plus this query's own paging state
   * ({@code AuditPageStatus}) and the trail's retention status ({@code AuditTrailStatus}); every
   * filter is optional and independently combinable. {@code cursor} continues from a previous
   * result's {@code nextCursor} and must be paired with the identical filter set it was issued
   * under -- the control plane refuses a cursor whose filters have changed rather than silently
   * answering a different question. */
  query(filter: AuditFilter, cursor?: string): Promise<AuditQueryResult>;
}
