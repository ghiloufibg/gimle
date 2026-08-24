import type { HistoryEnvelope, ProcessTarget, TraceSpanLine } from "@/types";
import type { TracesHistoryRepository, TracesPageArgs, TracesSinceArgs } from "../tracesHistory";
import { fetchHistoryEnvelope } from "./historyAvailability";

function pathFor(target: ProcessTarget): string {
  return `/traces-history/${encodeURIComponent(target.processKind)}/${encodeURIComponent(target.processId)}`;
}

/**
 * Structurally identical to HttpMetricsHistoryRepository: same envelope,
 * same since XOR cursor+limit rule, same "no live stream, poll instead" posture, and the same
 * fetchHistoryEnvelope() routing so a 404 discovered on either screen is remembered session-wide
 * -- see historyAvailability.ts.
 */
export class HttpTracesHistoryRepository implements TracesHistoryRepository {
  async fetchPage({
    target,
    cursor,
    limit,
  }: TracesPageArgs): Promise<HistoryEnvelope<TraceSpanLine>> {
    const params = new URLSearchParams({ limit: String(limit) });
    if (cursor !== null) params.set("cursor", cursor);
    return fetchHistoryEnvelope(`${pathFor(target)}?${params.toString()}`);
  }

  async fetchSince({ target, since }: TracesSinceArgs): Promise<HistoryEnvelope<TraceSpanLine>> {
    const params = new URLSearchParams({ since });
    return fetchHistoryEnvelope(`${pathFor(target)}?${params.toString()}`);
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
