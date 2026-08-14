import type { HistoryEnvelope, MetricsHistoryLine, ProcessTarget } from "@/types";
import type {
  HistoryPageArgs,
  HistorySinceArgs,
  MetricsHistoryRepository,
} from "../metricsHistory";
import { ApiError } from "./apiClient";

function pathFor(target: ProcessTarget): string {
  return `/metrics-history/${encodeURIComponent(target.processKind)}/${encodeURIComponent(target.processId)}`;
}

/**
 * fetchPage()/fetchSince() are plain GETs against /metrics-history/*.
 * openPoll() polls fetchSince on an interval rather than a live stream: the backend explicitly
 * rejects follow=true (Muninn only ever serves shipped history), the same reasoning
 * HttpLogsRepository.openFollow documents for /logs/*, which this class otherwise mirrors closely.
 */
export class HttpMetricsHistoryRepository implements MetricsHistoryRepository {
  async fetchPage({
    target,
    cursor,
    limit,
  }: HistoryPageArgs): Promise<HistoryEnvelope<MetricsHistoryLine>> {
    const params = new URLSearchParams({ limit: String(limit) });
    if (cursor !== null) params.set("cursor", cursor);
    const res = await fetch(`${pathFor(target)}?${params.toString()}`);
    if (!res.ok) throw new ApiError(res.status, await res.text());
    return (await res.json()) as HistoryEnvelope<MetricsHistoryLine>;
  }

  async fetchSince({
    target,
    since,
  }: HistorySinceArgs): Promise<HistoryEnvelope<MetricsHistoryLine>> {
    const params = new URLSearchParams({ since });
    const res = await fetch(`${pathFor(target)}?${params.toString()}`);
    if (!res.ok) throw new ApiError(res.status, await res.text());
    return (await res.json()) as HistoryEnvelope<MetricsHistoryLine>;
  }

  openPoll(
    target: ProcessTarget,
    onLines: (lines: MetricsHistoryLine[]) => void,
    intervalMs = 5000,
  ): () => void {
    let stopped = false;
    let since = new Date(Date.now() - intervalMs).toISOString();
    let inFlight = false;
    const tick = async () => {
      if (stopped || inFlight) return;
      inFlight = true;
      try {
        const env = await this.fetchSince({ target, since });
        if (!stopped && env.lines.length > 0) {
          since = env.lines[env.lines.length - 1].timestamp;
          onLines(env.lines);
        }
      } catch {
        // transient poll failure -- just retry on the next tick
      } finally {
        inFlight = false;
      }
    };
    const timer = setInterval(tick, intervalMs);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }
}
