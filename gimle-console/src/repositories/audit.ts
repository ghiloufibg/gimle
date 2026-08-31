import type { AuditFilter, AuditQueryResult } from "@/types";

export interface AuditRepository {
  /** GET /audit -- an envelope of {@code events} plus the trail's own retention status
   * ({@code AuditTrailStatus}), no pagination cursor; every filter is optional and independently
   * combinable. */
  query(filter: AuditFilter): Promise<AuditQueryResult>;
}
