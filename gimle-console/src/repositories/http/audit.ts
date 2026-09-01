import type { AuditFilter, AuditQueryResult } from "@/types";
import type { AuditRepository } from "../audit";
import { requestJson } from "./apiClient";

/** GET /audit?principal=&resource=&tenant=&since=&limit=&cursor= -- {events, matchedCount,
 * nextCursor?, cursorExpired, retainedCount, evictedTotal, oldestRetainedAtEpochMilli?, truncated}
 * envelope response. */
export class HttpAuditRepository implements AuditRepository {
  async query(filter: AuditFilter, cursor?: string): Promise<AuditQueryResult> {
    const params = new URLSearchParams();
    if (filter.principal) params.set("principal", filter.principal);
    if (filter.resource) params.set("resource", filter.resource);
    if (filter.tenant) params.set("tenant", filter.tenant);
    // The screen holds `since` as an ISO-8601 instant for its datetime input; the API takes epoch
    // millis, so the conversion belongs here rather than leaking the wire's units into the filter.
    if (filter.since) {
      const sinceMillis = new Date(filter.since).getTime();
      if (!Number.isNaN(sinceMillis)) params.set("since", String(sinceMillis));
    }
    if (filter.limit) params.set("limit", String(filter.limit));
    if (cursor) params.set("cursor", cursor);
    const qs = params.toString();
    return requestJson<AuditQueryResult>("GET", qs ? `/audit?${qs}` : "/audit");
  }
}
