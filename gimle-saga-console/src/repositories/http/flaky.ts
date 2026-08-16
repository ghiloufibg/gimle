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
  quarantined: boolean;
}

/** The shape of `SagaServer`'s `GET /api/flaky` response: a real per-entry quarantine flag (from
 * the server's own `@Tag("flaky")` test-tags index) and a server-configured budget allowance
 * (`-Dgimle.saga.flakeBudgetAllowance`), not client-side placeholders. */
interface RawFlakyResponse {
  entries: RawFlakyEntry[];
  budgetAllowance: number;
}

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
    quarantined: json.quarantined,
    signatures: mapSignatures(json.signatures),
    // A per-entry outcome strip needs a per-test history call; fetching that for every row on the
    // board would be an N+1 the board doesn't need -- the strip lives on the test detail page,
    // which already makes exactly that call.
    history: [],
  };
}

export class HttpFlakyRepository implements FlakyRepository {
  async getBoard(filter: FlakyFilter): Promise<FlakyBoard> {
    const json = await requestJson<RawFlakyResponse>(`/api/flaky?window=${filter.window}`);
    let entries = json.entries.map(mapEntry);
    const modules = [...new Set(entries.map((e) => e.module))].sort();
    if (filter.module !== "all") entries = entries.filter((e) => e.module === filter.module);
    if (filter.quarantinedOnly) entries = entries.filter((e) => e.quarantined);
    entries = [...entries].sort((a, b) => b.score - a.score);

    const budgetAllowance = json.budgetAllowance;
    const budgetSpent = entries.reduce((s, e) => s + e.occurrences, 0);
    return {
      entries,
      modules,
      summary: {
        activeFlaky: entries.filter((e) => !e.quarantined).length,
        budgetAllowance,
        budgetSpent,
        budgetUsedPct:
          budgetAllowance > 0
            ? Math.min(999, Math.round((budgetSpent / budgetAllowance) * 100))
            : 0,
        worstOffender: entries[0] ? { testId: entries[0].testId, score: entries[0].score } : null,
      },
    };
  }
}
