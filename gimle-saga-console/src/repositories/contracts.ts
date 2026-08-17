import type {
  CompareResult,
  FlakyBoard,
  FlakyFilter,
  Run,
  RunDetail,
  TestHistory,
  TestResultEvent,
} from "@/domain/types";

/** Cancels an active subscription. */
export type Unsubscribe = () => void;

export interface RunsRepository {
  listRuns(): Promise<Run[]>;
  getRun(runId: string): Promise<RunDetail>;
  getRunEvents(runId: string): Promise<TestResultEvent[]>;
  /**
   * Live feed. Emits test-finished events after `cursor` (event index).
   * Implementations may be websocket / SSE / polling based.
   *
   * `onEnd`, if given, fires once when the stream stops: with an `Error` for a genuine failure
   * (server restart, network drop), or with no argument for a clean end (abort/unsubscribe).
   */
  followRunEvents(
    runId: string,
    cursor: number,
    onEvent: (event: TestResultEvent) => void,
    onEnd?: (error?: Error) => void,
  ): Unsubscribe;
  compareRuns(baseRunId: string, headRunId: string): Promise<CompareResult>;
}

export interface FlakyRepository {
  getBoard(filter: FlakyFilter): Promise<FlakyBoard>;
}

export interface TestHistoryRepository {
  getHistory(testId: string): Promise<TestHistory>;
}
