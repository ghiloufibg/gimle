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
   */
  followRunEvents(
    runId: string,
    cursor: number,
    onEvent: (event: TestResultEvent) => void,
  ): Unsubscribe;
  compareRuns(baseRunId: string, headRunId: string): Promise<CompareResult>;
}

export interface FlakyRepository {
  getBoard(filter: FlakyFilter): Promise<FlakyBoard>;
}

export interface TestHistoryRepository {
  getHistory(testId: string): Promise<TestHistory>;
}
