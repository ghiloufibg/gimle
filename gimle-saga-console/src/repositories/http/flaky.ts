import type { FailureSignature, FlakyBoard, FlakyEntry, FlakyFilter } from "@/domain/types";
import type { FlakyRepository } from "@/repositories/contracts";
import { requestJson } from "./apiClient";

interface RawFlakyEntry {
  testId: string;
  module: string;
  occurrences: number;
  runsSeen: number;
  flakeRate: number;
  score: number;
  signatures: Record<string, number>;
  firstSeen: number;
  lastSeen: number;
}

/**
 * A flake "budget" (an allowed retry count per window, gating `mvn verify` via Surefire's own
 * `failOnFlakeCount`) isn't a server-tracked concept yet -- there's nothing to read it from. This
 * is a client-side reference threshold only, so the summary panel has something concrete to show
 * usage against until the server exposes a configured one.
 */
const REFERENCE_FLAKE_BUDGET = 120;

function mapSignatures(signatures: Record<string, number>): FailureSignature[] {
  return Object.entries(signatures)
    .map(([hash, count]) => ({ hash, count, exceptionType: "", message: "" }))
    .sort((a, b) => b.count - a.count);
}

function mapEntry(json: RawFlakyEntry): FlakyEntry {
  return {
    testId: json.testId,
    module: json.module,
    score: json.score,
    flakeRate: json.flakeRate,
    occurrences: json.occurrences,
    runsSeen: json.runsSeen,
    firstSeen: new Date(json.firstSeen).toISOString(),
    lastSeen: new Date(json.lastSeen).toISOString(),
    // Quarantine is a `@Tag("flaky")` marker on the test source, not something the store tracks
    // (see the "gimle:flaky-tests" quarantine design) -- always false until that lands.
    quarantined: false,
    signatures: mapSignatures(json.signatures),
    // A per-entry outcome strip needs a per-test history call; fetching that for every row on the
    // board would be an N+1 the board doesn't need -- the strip lives on the test detail page,
    // which already makes exactly that call.
    history: [],
  };
}

export class HttpFlakyRepository implements FlakyRepository {
  async getBoard(filter: FlakyFilter): Promise<FlakyBoard> {
    const json = await requestJson<RawFlakyEntry[]>(`/api/flaky?window=${filter.window}`);
    let entries = json.map(mapEntry);
    const modules = [...new Set(entries.map((e) => e.module))].sort();
    if (filter.module !== "all") entries = entries.filter((e) => e.module === filter.module);
    if (filter.quarantinedOnly) entries = entries.filter((e) => e.quarantined);
    entries = [...entries].sort((a, b) => b.score - a.score);

    const budgetSpent = entries.reduce((s, e) => s + e.occurrences, 0);
    return {
      entries,
      modules,
      summary: {
        activeFlaky: entries.filter((e) => !e.quarantined).length,
        budgetAllowance: REFERENCE_FLAKE_BUDGET,
        budgetSpent,
        budgetUsedPct: Math.min(999, Math.round((budgetSpent / REFERENCE_FLAKE_BUDGET) * 100)),
        worstOffender: entries[0] ? { testId: entries[0].testId, score: entries[0].score } : null,
      },
    };
  }
}
