import type { HistoryEnvelope, ProcessTarget, TraceSpanLine } from "@/types";

export interface TracesPageArgs {
  target: ProcessTarget;
  cursor: string | null;
  limit: number;
}

export interface TracesSinceArgs {
  target: ProcessTarget;
  since: string;
}

export interface TracesHistoryRepository {
  fetchPage(args: TracesPageArgs): Promise<HistoryEnvelope<TraceSpanLine>>;
  fetchSince(args: TracesSinceArgs): Promise<HistoryEnvelope<TraceSpanLine>>;
  /** GET /traces-history/* rejects follow=true -- this polls fetchSince on an interval. */
  openPoll(
    target: ProcessTarget,
    onLines: (lines: TraceSpanLine[]) => void,
    intervalMs?: number,
  ): () => void;
}
