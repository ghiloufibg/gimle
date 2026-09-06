import type { HistoryEnvelope, ProcessKind, ProcessTarget, TraceSpanLine } from "@/types";

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
  /** See MetricsHistoryRepository.fetchProcessKinds -- the same question asked of spans instead. */
  fetchProcessKinds(): Promise<ProcessKind[]>;
  fetchPage(args: TracesPageArgs): Promise<HistoryEnvelope<TraceSpanLine>>;
  fetchSince(args: TracesSinceArgs): Promise<HistoryEnvelope<TraceSpanLine>>;
  /** GET /traces-history/* rejects follow=true -- this polls fetchSince on an interval. */
  openPoll(
    target: ProcessTarget,
    onLines: (lines: TraceSpanLine[]) => void,
    intervalMs?: number,
  ): () => void;
}
