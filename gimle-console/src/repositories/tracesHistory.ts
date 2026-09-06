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

/** One span of a searched trace, plus the process whose history held it. */
export interface FoundSpan {
  processKind: ProcessKind;
  processId: string;
  span: TraceSpanLine;
}

export interface TraceSearchResult {
  traceId: string;
  spans: FoundSpan[];
  /** The search stopped at its limit, so the trace may have more spans than these. */
  truncated: boolean;
}

export interface TracesHistoryRepository {
  /**
   * Every span of one trace, wherever it ran. The per-process reads below cannot answer this: a
   * caller would have to already know which processes took part, and a worker replaced since the
   * call no longer appears in any instance listing to be named.
   */
  searchByTraceId(traceId: string, limit?: number): Promise<TraceSearchResult>;
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
