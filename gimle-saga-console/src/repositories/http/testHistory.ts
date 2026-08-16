import type { FailureSignature, TestHistory, TestHistoryPoint } from "@/domain/types";
import type { TestHistoryRepository } from "@/repositories/contracts";
import { moduleOf } from "@/lib/testId";
import { requestJson } from "./apiClient";
import { fetchAllEvents } from "./eventsClient";
import type { RawRunMeta } from "./mapping";

interface RawTestHistoryEntry {
  runId: string;
  startedAtEpochMilli: number;
  outcome: string;
  durationMillis: number;
  attempts: number;
  flaky: boolean;
}

interface RawFlakyEntry {
  testId: string;
  flakeRate: number;
  score: number;
  signatures: Record<string, number>;
}

/** Bounds how many of a test's own failing runs get fetched to enrich signature detail -- the
 * hash/count in every signature is already exact from the scoreboard; this only fills in the
 * exception type/message for as many distinct hashes as this sample happens to cover. */
const MAX_SAMPLED_RUNS = 5;

/** Large enough to cover this dev tool's realistic run history in one call; a run older than
 * this list falls back to an empty branch rather than a second per-point fetch. */
const RUN_LOOKUP_LIMIT = 500;

async function branchLookup(): Promise<Map<string, string>> {
  const runs = await requestJson<RawRunMeta[]>(`/api/runs?limit=${RUN_LOOKUP_LIMIT}`);
  return new Map(runs.map((r) => [r.runId, r.gitBranch]));
}

async function sampleSignatures(
  testId: string,
  failingRunIds: string[],
  knownHashes: Set<string>,
): Promise<Map<string, { exceptionType: string; message: string }>> {
  const resolved = new Map<string, { exceptionType: string; message: string }>();
  for (const runId of failingRunIds.slice(0, MAX_SAMPLED_RUNS)) {
    if (resolved.size >= knownHashes.size) break;
    const events = await fetchAllEvents(runId);
    for (const event of events) {
      if (
        event.type === "test-finished" &&
        event.testId === testId &&
        event.outcome === "FAILED" &&
        event.failureSignature &&
        knownHashes.has(event.failureSignature) &&
        !resolved.has(event.failureSignature)
      ) {
        resolved.set(event.failureSignature, {
          exceptionType: event.failureType ?? "",
          message: event.failureMessage ?? "",
        });
      }
    }
  }
  return resolved;
}

export class HttpTestHistoryRepository implements TestHistoryRepository {
  async getHistory(testId: string): Promise<TestHistory> {
    const [entries, flaky, branches] = await Promise.all([
      requestJson<RawTestHistoryEntry[]>(`/api/tests/${encodeURIComponent(testId)}/history`),
      requestJson<RawFlakyEntry[]>(`/api/flaky?window=90`),
      branchLookup(),
    ]);
    const points: TestHistoryPoint[] = entries.map((e) => ({
      runId: e.runId,
      startedAt: new Date(e.startedAtEpochMilli).toISOString(),
      branch: branches.get(e.runId) ?? "",
      outcome: e.outcome as TestHistoryPoint["outcome"],
      attempts: e.attempts,
      durationMillis: e.durationMillis,
    }));

    const flakyEntry = flaky.find((e) => e.testId === testId);
    const knownHashes = new Set(Object.keys(flakyEntry?.signatures ?? {}));
    const failingRunIds = entries
      .filter((e) => e.outcome === "FAILED" || e.attempts > 1)
      .map((e) => e.runId);
    const sampled = knownHashes.size
      ? await sampleSignatures(testId, failingRunIds, knownHashes)
      : new Map<string, { exceptionType: string; message: string }>();
    const signatures: FailureSignature[] = Object.entries(flakyEntry?.signatures ?? {})
      .map(([hash, count]) => ({
        hash,
        count,
        exceptionType: sampled.get(hash)?.exceptionType ?? "",
        message: sampled.get(hash)?.message ?? "",
      }))
      .sort((a, b) => b.count - a.count);

    return {
      testId,
      module: moduleOf(testId),
      quarantined: false,
      flakeRate: flakyEntry?.flakeRate ?? 0,
      score: flakyEntry?.score ?? 0,
      points,
      signatures,
    };
  }
}
