import type { AuditEvent, AuditFilter } from "@/types";

export interface AuditRepository {
  /** GET /audit -- flat array response, no pagination cursor; every filter is optional and
   * independently combinable. */
  query(filter: AuditFilter): Promise<AuditEvent[]>;
}
