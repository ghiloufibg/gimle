/**
 * COMPOSITION ROOT.
 *
 * The only place in the app where repository implementations are chosen.
 * Swapping the mocks for HTTP-backed implementations means editing this file
 * (and nothing else): stores depend exclusively on the interfaces below.
 */
import type { FlakyRepository, RunsRepository, TestHistoryRepository } from "./contracts";
import { MockFlakyRepository, MockRunsRepository, MockTestHistoryRepository } from "./mock";

export const runsRepository: RunsRepository = new MockRunsRepository();
export const flakyRepository: FlakyRepository = new MockFlakyRepository();
export const testHistoryRepository: TestHistoryRepository = new MockTestHistoryRepository();

export type {
  FlakyRepository,
  RunsRepository,
  TestHistoryRepository,
  Unsubscribe,
} from "./contracts";
