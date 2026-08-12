import type { HistoryEnvelope, MetricsHistoryLine, ProcessTarget } from "@/types";

export interface HistoryPageArgs {
  target: ProcessTarget;
  /** Backward page cursor. `null` means "newest page". */
  cursor: string | null;
  limit: number;
}

export interface HistorySinceArgs {
  target: ProcessTarget;
  /** ISO-8601 instant; returns lines strictly newer than this. */
  since: string;
}

export interface MetricsHistoryRepository {
  /** Backward page: `cursor` + `limit`. Never combined with `since`. */
  fetchPage(args: HistoryPageArgs): Promise<HistoryEnvelope<MetricsHistoryLine>>;
  /** Forward poll: `since` only. Never combined with `cursor`/`limit`. */
  fetchSince(args: HistorySinceArgs): Promise<HistoryEnvelope<MetricsHistoryLine>>;
  /**
   * "Live" mode. GET /metrics-history/* rejects follow=true (Muninn only ever serves shipped
   * history), so this polls fetchSince on an interval -- the same technique
   * HttpLogsRepository.openFollow uses for /logs/*.
   */
  openPoll(
    target: ProcessTarget,
    onLines: (lines: MetricsHistoryLine[]) => void,
    intervalMs?: number,
  ): () => void;
}
