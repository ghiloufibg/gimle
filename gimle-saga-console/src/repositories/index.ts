/**
 * COMPOSITION ROOT.
 *
 * The only place in the app where repository implementations are chosen.
 * Swapping the mocks for HTTP-backed implementations means editing this file
 * (and nothing else): stores depend exclusively on the interfaces below.
 */
import type { FlakyRepository, RunsRepository, TestHistoryRepository } from "./contracts";
import { HttpFlakyRepository } from "./http/flaky";
import { HttpRunsRepository } from "./http/runs";
import { HttpTestHistoryRepository } from "./http/testHistory";

export const runsRepository: RunsRepository = new HttpRunsRepository();
export const flakyRepository: FlakyRepository = new HttpFlakyRepository();
export const testHistoryRepository: TestHistoryRepository = new HttpTestHistoryRepository();

export type {
  FlakyRepository,
  RunsRepository,
  TestHistoryRepository,
  Unsubscribe,
} from "./contracts";
