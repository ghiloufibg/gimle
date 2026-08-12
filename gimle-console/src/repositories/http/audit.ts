import type { AuditEvent, AuditFilter } from "@/types";
import type { AuditRepository } from "../audit";
import { requestJson } from "./apiClient";

/** GET /audit?principal=&resource=&tenant=&since=&limit= -- flat array response. */
export class HttpAuditRepository implements AuditRepository {
  async query(filter: AuditFilter): Promise<AuditEvent[]> {
    const params = new URLSearchParams();
    if (filter.principal) params.set("principal", filter.principal);
    if (filter.resource) params.set("resource", filter.resource);
    if (filter.tenant) params.set("tenant", filter.tenant);
    if (filter.since) params.set("since", filter.since);
    if (filter.limit) params.set("limit", String(filter.limit));
    const qs = params.toString();
    return requestJson<AuditEvent[]>("GET", qs ? `/audit?${qs}` : "/audit");
  }
}
