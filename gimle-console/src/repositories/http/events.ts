import type { InstanceEvent } from "@/types";
import type { EventsRepository } from "@/repositories/events";
import { requestJson } from "./apiClient";

/**
 * `GET /events?deployment=&instance=[&tenant=]` -- an instance's whole lifecycle timeline,
 * newest-first. The route carries no `limit` parameter the way `/audit` does, so a caller wanting
 * fewer entries truncates the already-newest-first response itself rather than sending one the
 * server would silently ignore.
 */
export class HttpEventsRepository implements EventsRepository {
  async fetchForInstance(
    deploymentName: string,
    instanceIndex: number,
    tenantId?: string | null,
  ): Promise<InstanceEvent[]> {
    const query = new URLSearchParams({
      deployment: deploymentName,
      instance: String(instanceIndex),
    });
    if (tenantId) query.set("tenant", tenantId);
    return requestJson<InstanceEvent[]>("GET", `/events?${query.toString()}`);
  }
}
