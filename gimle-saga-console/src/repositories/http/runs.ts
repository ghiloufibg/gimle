import type { CompareResult, Run, RunDetail, TestResultEvent } from "@/domain/types";
import type { RunsRepository, Unsubscribe } from "@/repositories/contracts";
import { requestJson, streamNdjsonLines } from "./apiClient";
import { fetchAllEvents } from "./eventsClient";
import {
  collectAttachments,
  computeModuleProgress,
  diffRuns,
  mapRun,
  mapTestFinishedEvent,
  type RawEvent,
  type RawRunMeta,
} from "./mapping";

export class HttpRunsRepository implements RunsRepository {
  async listRuns(): Promise<Run[]> {
    const json = await requestJson<RawRunMeta[]>("/api/runs");
    return json.map(mapRun);
  }

  async getRun(runId: string): Promise<RunDetail> {
    const [meta, events] = await Promise.all([
      requestJson<RawRunMeta>(`/api/runs/${encodeURIComponent(runId)}`),
      fetchAllEvents(runId),
    ]);
    const run = mapRun(meta);
    return {
      run,
      moduleProgress: computeModuleProgress(events, run.modules),
      attachments: collectAttachments(events),
    };
  }

  async getRunEvents(runId: string): Promise<TestResultEvent[]> {
    const events = await fetchAllEvents(runId);
    const mapped: TestResultEvent[] = [];
    for (const event of events) {
      const finished = mapTestFinishedEvent(event);
      if (finished) mapped.push(finished);
    }
    return mapped;
  }

  /**
   * `cursor` counts already-known finished-test events (the length of what `getRunEvents` already
   * returned), not the server's own NDJSON line offset -- those are different units, since the raw
   * stream also carries test-started/run-started/attachment lines the console never turns into a
   * `TestResultEvent`. Always tailing from line 0 and skipping the first `cursor` finished-test
   * events client-side keeps the two cursors from ever needing to agree, at the cost of
   * re-transferring already-seen lines once per `follow()` call -- negligible for a dev tool.
   */
  followRunEvents(
    runId: string,
    cursor: number,
    onEvent: (event: TestResultEvent) => void,
  ): Unsubscribe {
    const controller = new AbortController();
    let seen = 0;
    const url = `/api/runs/${encodeURIComponent(runId)}/events?cursor=0&follow=true`;
    void streamNdjsonLines(
      url,
      (line) => {
        let raw: RawEvent;
        try {
          raw = JSON.parse(line) as RawEvent;
        } catch {
          return;
        }
        const event = mapTestFinishedEvent(raw);
        if (!event) return;
        if (seen < cursor) {
          seen++;
          return;
        }
        seen++;
        onEvent(event);
      },
      controller.signal,
    ).catch((e: unknown) => {
      if (!controller.signal.aborted) {
        console.error(`saga: event stream for run ${runId} failed`, e);
      }
    });
    return () => controller.abort();
  }

  async compareRuns(baseRunId: string, headRunId: string): Promise<CompareResult> {
    const [baseDetail, headDetail, baseEvents, headEvents] = await Promise.all([
      requestJson<RawRunMeta>(`/api/runs/${encodeURIComponent(baseRunId)}`),
      requestJson<RawRunMeta>(`/api/runs/${encodeURIComponent(headRunId)}`),
      this.getRunEvents(baseRunId),
      this.getRunEvents(headRunId),
    ]);
    return {
      base: mapRun(baseDetail),
      head: mapRun(headDetail),
      ...diffRuns(baseEvents, headEvents),
    };
  }
}
