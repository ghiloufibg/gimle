import type { HistoryEnvelope, ProcessTarget, TraceSpanLine } from "@/types";
import type { TracesHistoryRepository, TracesPageArgs, TracesSinceArgs } from "../tracesHistory";
import { ApiError } from "./apiClient";

function pathFor(target: ProcessTarget): string {
  return `/traces-history/${encodeURIComponent(target.processKind)}/${encodeURIComponent(target.processId)}`;
}

/**
 * Structurally identical to HttpMetricsHistoryRepository (design doc Part B/O-13): same envelope,
 * same since XOR cursor+limit rule, same "no live stream, poll instead" posture.
 */
export class HttpTracesHistoryRepository implements TracesHistoryRepository {
  async fetchPage({
    target,
    cursor,
    limit,
  }: TracesPageArgs): Promise<HistoryEnvelope<TraceSpanLine>> {
    const params = new URLSearchParams({ limit: String(limit) });
    if (cursor !== null) params.set("cursor", cursor);
    const res = await fetch(`${pathFor(target)}?${params.toString()}`);
    if (!res.ok) throw new ApiError(res.status, await res.text());
    return (await res.json()) as HistoryEnvelope<TraceSpanLine>;
  }

  async fetchSince({ target, since }: TracesSinceArgs): Promise<HistoryEnvelope<TraceSpanLine>> {
    const params = new URLSearchParams({ since });
    const res = await fetch(`${pathFor(target)}?${params.toString()}`);
    if (!res.ok) throw new ApiError(res.status, await res.text());
    return (await res.json()) as HistoryEnvelope<TraceSpanLine>;
  }

  openPoll(
    target: ProcessTarget,
    onLines: (lines: TraceSpanLine[]) => void,
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
