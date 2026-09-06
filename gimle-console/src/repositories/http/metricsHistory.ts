import type { HistoryEnvelope, MetricsHistoryLine, ProcessKind, ProcessTarget } from "@/types";
import type {
  HistoryPageArgs,
  HistorySinceArgs,
  MetricsHistoryRepository,
} from "../metricsHistory";
import { createPoller } from "@/lib/polling";
import { fetchHistoryEnvelope } from "./historyAvailability";
import { requestJson } from "./apiClient";

function pathFor(target: ProcessTarget): string {
  return `/metrics-history/${encodeURIComponent(target.processKind)}/${encodeURIComponent(target.processId)}`;
}

/**
 * fetchPage()/fetchSince() are plain GETs against /metrics-history/*, routed through
 * fetchHistoryEnvelope() so a 404 (Muninn not configured on this cluster) is remembered
 * session-wide instead of re-fetched by every screen visit -- see historyAvailability.ts.
 * openPoll() polls fetchSince on an interval rather than a live stream: the backend explicitly
 * rejects follow=true (Muninn only ever serves shipped history), the same reasoning
 * HttpLogsRepository.openFollow documents for /logs/*, which this class otherwise mirrors closely.
 */
export class HttpMetricsHistoryRepository implements MetricsHistoryRepository {
  async fetchProcessKinds(): Promise<ProcessKind[]> {
    // Not routed through fetchHistoryEnvelope: this route answers from the control plane's own
    // knowledge of what ships what and never 404s for a cluster with no Muninn configured, so a
    // remembered per-process 404 must not short-circuit it.
    const body = await requestJson<{ processKinds: ProcessKind[] }>("GET", "/metrics-history");
    return body.processKinds ?? [];
  }

  async fetchPage({
    target,
    cursor,
    limit,
  }: HistoryPageArgs): Promise<HistoryEnvelope<MetricsHistoryLine>> {
    const params = new URLSearchParams({ limit: String(limit) });
    if (cursor !== null) params.set("cursor", cursor);
    return fetchHistoryEnvelope(`${pathFor(target)}?${params.toString()}`);
  }

  async fetchSince({
    target,
    since,
  }: HistorySinceArgs): Promise<HistoryEnvelope<MetricsHistoryLine>> {
    const params = new URLSearchParams({ since });
    return fetchHistoryEnvelope(`${pathFor(target)}?${params.toString()}`);
  }

  openPoll(
    target: ProcessTarget,
    onLines: (lines: MetricsHistoryLine[]) => void,
    intervalMs = 5000,
  ): () => void {
    let stopped = false;
    let since = new Date(Date.now() - intervalMs).toISOString();
    // The same poller the screens' own auto-refresh runs on: one place decides what "polling"
    // means here (no overlapping ticks, a failed read costs nothing but that tick).
    const poller = createPoller({
      intervalMs,
      tick: async () => {
        try {
          const env = await this.fetchSince({ target, since });
          if (!stopped && env.lines.length > 0) {
            since = env.lines[env.lines.length - 1].timestamp;
            onLines(env.lines);
          }
        } catch {
          // transient poll failure -- just retry on the next tick
        }
      },
    });
    poller.start();
    return () => {
      stopped = true;
      poller.stop();
    };
  }
}
