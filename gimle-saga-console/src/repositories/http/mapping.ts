import type {
  ChaosStrike,
  CompareResult,
  DurationRegression,
  GherkinFeature,
  ModuleProgress,
  Outcome,
  Run,
  RunAttachments,
  RunStatus,
  SurtrPhase,
  TestResultEvent,
} from "@/domain/types";
import { moduleOf } from "@/lib/testId";

/** The subset of `RunMeta#toJsonMap()`'s shape the console reads. */
export interface RawRunMeta {
  runId: string;
  startedAtEpochMilli: number;
  finishedAtEpochMilli: number;
  gitBranch: string;
  gitSha: string;
  command: string;
  modules: string[];
  status: string;
  totals?: { tests: number; passed: number; failed: number; skipped: number; flaky: number };
}

/** One decoded line from a run's `events.ndjson`, in `SagaEventCodec`'s own wire shape. */
export interface RawEvent {
  type: string;
  runId: string;
  testId?: string;
  attempt?: number;
  outcome?: string;
  durationMillis?: number;
  failureType?: string;
  failureMessage?: string;
  failureSignature?: string;
  kind?: string;
  payloadJson?: string;
}

export function mapRun(json: RawRunMeta): Run {
  return {
    runId: json.runId,
    startedAt: new Date(json.startedAtEpochMilli).toISOString(),
    ...(json.finishedAtEpochMilli > 0
      ? { finishedAt: new Date(json.finishedAtEpochMilli).toISOString() }
      : {}),
    gitSha: json.gitSha,
    branch: json.gitBranch,
    command: json.command,
    status: json.status as RunStatus,
    totals: json.totals ?? { tests: 0, passed: 0, failed: 0, skipped: 0, flaky: 0 },
    modules: json.modules,
  };
}

/**
 * `null` for every wire event that isn't a finished test attempt (run-started, test-started,
 * attachment, run-finished) -- callers filter those out rather than this function skipping them,
 * so a caller that also wants raw non-test events (module-progress/attachment derivation) can
 * still see the full stream.
 */
export function mapTestFinishedEvent(json: RawEvent): TestResultEvent | null {
  if (json.type !== "test-finished" || !json.testId || !json.outcome) return null;
  return {
    runId: json.runId,
    testId: json.testId,
    attempt: json.attempt ?? 1,
    outcome: json.outcome as Outcome,
    durationMillis: json.durationMillis ?? 0,
    ...(json.failureType !== undefined ? { failureType: json.failureType } : {}),
    ...(json.failureMessage !== undefined ? { failureMessage: json.failureMessage } : {}),
    ...(json.failureSignature !== undefined ? { failureSignature: json.failureSignature } : {}),
    // The wire event carries a duration, not a wall-clock timestamp -- this is receipt time, an
    // approximation never rendered directly (only run-level start/finish timestamps are shown).
    finishedAt: new Date().toISOString(),
  };
}

/**
 * Per-module progress, derived from the testIds actually observed rather than a planned count:
 * the real backend has no way to know how many tests a module will run before they've run (unlike
 * the demo fixtures, which pre-know the whole plan). `total` tracks `completed` 1:1, so the bar
 * fills as events arrive instead of stalling against a guessed ceiling.
 */
export function computeModuleProgress(
  events: RawEvent[],
  declaredModules: string[],
): ModuleProgress[] {
  const byModule = new Map<string, { completed: number; failed: number }>();
  for (const event of events) {
    if (event.type !== "test-finished" || !event.testId) continue;
    const module = moduleOf(event.testId);
    const stat = byModule.get(module) ?? { completed: 0, failed: 0 };
    stat.completed++;
    if (event.outcome === "FAILED") stat.failed++;
    byModule.set(module, stat);
  }
  const modules = new Set([...declaredModules, ...byModule.keys()]);
  return [...modules].sort().map((module) => {
    const stat = byModule.get(module) ?? { completed: 0, failed: 0 };
    return { module, total: stat.completed, completed: stat.completed, failed: stat.failed };
  });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

/** Accepts either one payload object or an array of them -- forward-compatible with either shape
 * a future producer might choose to ship for a single `attachment` event. */
function parsePayload<T>(payloadJson: string, isShape: (v: unknown) => v is T): T[] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(payloadJson);
  } catch {
    return [];
  }
  const candidates = Array.isArray(parsed) ? parsed : [parsed];
  return candidates.filter(isShape);
}

const isGherkinFeature = (v: unknown): v is GherkinFeature =>
  isRecord(v) && typeof v["name"] === "string" && Array.isArray(v["scenarios"]);
const isChaosStrike = (v: unknown): v is ChaosStrike =>
  isRecord(v) && typeof v["kind"] === "string" && typeof v["target"] === "string";
const isSurtrPhase = (v: unknown): v is SurtrPhase =>
  isRecord(v) && typeof v["phase"] === "string" && typeof v["throughput"] === "number";

/**
 * Groups a run's `attachment` events by kind into the shapes the run-detail screen's tabs render.
 * No producer ships these yet (the holmgang Gherkin/Fenrir/Surtr integration is still to be
 * built), so real runs simply have none -- the tabs stay hidden, which is the correct state to
 * reflect until that producer exists, not a stand-in to fill.
 */
export function collectAttachments(events: RawEvent[]): RunAttachments {
  const gherkin: GherkinFeature[] = [];
  const chaosLedger: ChaosStrike[] = [];
  const surtr: SurtrPhase[] = [];
  for (const event of events) {
    if (event.type !== "attachment" || !event.payloadJson) continue;
    switch (event.kind) {
      case "gherkin-scenario":
        gherkin.push(...parsePayload(event.payloadJson, isGherkinFeature));
        break;
      case "fenrir-ledger":
        chaosLedger.push(...parsePayload(event.payloadJson, isChaosStrike));
        break;
      case "surtr-result":
        surtr.push(...parsePayload(event.payloadJson, isSurtrPhase));
        break;
      default:
        break;
    }
  }
  const attachments: RunAttachments = {};
  if (gherkin.length) attachments.gherkin = gherkin;
  if (chaosLedger.length) attachments.chaosLedger = chaosLedger;
  if (surtr.length) attachments.surtr = surtr;
  return attachments;
}

function lastOutcomes(events: TestResultEvent[]): Map<string, TestResultEvent[]> {
  const byTest = new Map<string, TestResultEvent[]>();
  events.forEach((e) => byTest.set(e.testId, [...(byTest.get(e.testId) ?? []), e]));
  return byTest;
}

function isFlakyIn(m: Map<string, TestResultEvent[]>, id: string): boolean {
  const attempts = m.get(id);
  if (!attempts) return false;
  const last = attempts[attempts.length - 1]!;
  return attempts.some((a) => a.outcome === "FAILED") && last.outcome === "PASSED";
}

/** Diffs two runs' finished-test events; `base`/`head` are attached by the caller. */
export function diffRuns(
  baseEvents: TestResultEvent[],
  headEvents: TestResultEvent[],
): Omit<CompareResult, "base" | "head"> {
  const b = lastOutcomes(baseEvents);
  const h = lastOutcomes(headEvents);
  const finalOutcome = (m: Map<string, TestResultEvent[]>, id: string) =>
    m.get(id)?.[m.get(id)!.length - 1]?.outcome;

  const shared = [...h.keys()].filter((id) => b.has(id));
  const newlyFailing = shared.filter(
    (id) => finalOutcome(h, id) === "FAILED" && finalOutcome(b, id) === "PASSED",
  );
  const newlyPassing = shared.filter(
    (id) => finalOutcome(h, id) === "PASSED" && finalOutcome(b, id) === "FAILED",
  );
  const newlyFlaky = shared.filter((id) => isFlakyIn(h, id) && !isFlakyIn(b, id));
  const regressions: DurationRegression[] = shared
    .map((id) => {
      const baseMillis = b.get(id)!.reduce((s, e) => s + e.durationMillis, 0);
      const headMillis = h.get(id)!.reduce((s, e) => s + e.durationMillis, 0);
      const deltaPct = baseMillis > 0 ? ((headMillis - baseMillis) / baseMillis) * 100 : 0;
      return { testId: id, baseMillis, headMillis, deltaPct };
    })
    .filter((r) => r.deltaPct > 25 && r.baseMillis > 200)
    .sort((a, z) => z.deltaPct - a.deltaPct);

  return { newlyFailing, newlyPassing, newlyFlaky, regressions };
}
