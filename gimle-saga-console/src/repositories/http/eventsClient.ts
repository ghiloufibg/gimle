import { requestJson } from "./apiClient";
import type { RawEvent } from "./mapping";

interface RawEventsPage {
  events: RawEvent[];
  nextCursor: number;
}

/** The full stored event log for one run -- `SagaStore#readEvents` has no page-size limit, so a
 * single request from cursor 0 already returns everything. */
export async function fetchAllEvents(runId: string): Promise<RawEvent[]> {
  const page = await requestJson<RawEventsPage>(
    `/api/runs/${encodeURIComponent(runId)}/events?cursor=0`,
  );
  return page.events;
}
