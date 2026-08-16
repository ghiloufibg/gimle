import type {
  CompareResult,
  DurationRegression,
  FlakyBoard,
  FlakyEntry,
  FlakyFilter,
  Outcome,
  Run,
  RunDetail,
  TestHistory,
  TestHistoryPoint,
  TestResultEvent,
} from "@/domain/types";
import type {
  FlakyRepository,
  RunsRepository,
  TestHistoryRepository,
  Unsubscribe,
} from "./contracts";
import {
  MOCK_FLAKY,
  MOCK_RUNS,
  MODULES,
  makeLiveEvent,
  pendingTestIdsFor,
  type MockRun,
} from "./fixtures";
import { moduleOf } from "@/lib/testId";

const latency = (ms = 260) => new Promise<void>((r) => setTimeout(r, ms + Math.random() * 220));

const stripRun = ({ events: _e, attachments: _a, ...run }: MockRun): Run => run;

/** Mutable session state so live runs accumulate events across navigations. */
const liveEvents = new Map<string, TestResultEvent[]>();
const pending = new Map<string, string[]>();

function eventsFor(run: MockRun): TestResultEvent[] {
  if (!liveEvents.has(run.runId)) {
    liveEvents.set(run.runId, [...run.events]);
    pending.set(run.runId, pendingTestIdsFor(run));
  }
  return liveEvents.get(run.runId)!;
}

function lastOutcomes(events: TestResultEvent[]) {
  const byTest = new Map<string, TestResultEvent[]>();
  events.forEach((e) => byTest.set(e.testId, [...(byTest.get(e.testId) ?? []), e]));
  return byTest;
}

function totalsFrom(events: TestResultEvent[]) {
  const byTest = lastOutcomes(events);
  let passed = 0;
  let failed = 0;
  let skipped = 0;
  let flaky = 0;
  byTest.forEach((attempts) => {
    const last = attempts[attempts.length - 1]!;
    if (last.outcome === "SKIPPED") skipped++;
    else if (last.outcome === "PASSED") {
      passed++;
      if (attempts.some((a) => a.outcome === "FAILED")) flaky++;
    } else failed++;
  });
  return { tests: byTest.size, passed, failed, skipped, flaky };
}

export class MockRunsRepository implements RunsRepository {
  async listRuns(): Promise<Run[]> {
    await latency();
    return MOCK_RUNS.map((r) => {
      const events = r.status === "live" ? eventsFor(r) : r.events;
      return { ...stripRun(r), totals: totalsFrom(events) };
    });
  }

  async getRun(runId: string): Promise<RunDetail> {
    await latency(320);
    const run = MOCK_RUNS.find((r) => r.runId === runId);
    if (!run) throw new Error(`run ${runId} not found`);
    const events = run.status === "live" ? eventsFor(run) : run.events;
    const totals = totalsFrom(events);
    const moduleProgress = run.modules.map((module) => {
      const modEvents = [...lastOutcomes(events)].filter(([id]) => moduleOf(id) === module);
      const expected =
        modEvents.length + (pending.get(runId) ?? []).filter((t) => moduleOf(t) === module).length;
      return {
        module,
        total: Math.max(expected, modEvents.length, 1),
        completed: modEvents.length,
        failed: modEvents.filter(([, a]) => a[a.length - 1]!.outcome === "FAILED").length,
      };
    });
    return { run: { ...stripRun(run), totals }, moduleProgress, attachments: run.attachments };
  }

  async getRunEvents(runId: string): Promise<TestResultEvent[]> {
    await latency(300);
    const run = MOCK_RUNS.find((r) => r.runId === runId);
    if (!run) throw new Error(`run ${runId} not found`);
    return [...(run.status === "live" ? eventsFor(run) : run.events)];
  }

  followRunEvents(
    runId: string,
    _cursor: number,
    onEvent: (event: TestResultEvent) => void,
  ): Unsubscribe {
    const run = MOCK_RUNS.find((r) => r.runId === runId);
    if (!run || run.status !== "live") return () => {};
    const store = eventsFor(run);
    const timer = setInterval(() => {
      const queue = pending.get(runId) ?? [];
      const next = queue.shift();
      if (!next) return;
      const event = makeLiveEvent(runId, next);
      store.push(event);
      onEvent(event);
      if (event.outcome === "FAILED" && Math.random() < 0.5) {
        const retry: TestResultEvent = {
          runId,
          testId: next,
          attempt: 2,
          outcome: "PASSED",
          durationMillis: Math.floor(200 + Math.random() * 3200),
          finishedAt: new Date().toISOString(),
        };
        setTimeout(() => {
          store.push(retry);
          onEvent(retry);
        }, 900);
      }
    }, 1500);
    return () => clearInterval(timer);
  }

  async compareRuns(baseRunId: string, headRunId: string): Promise<CompareResult> {
    await latency(420);
    const base = MOCK_RUNS.find((r) => r.runId === baseRunId);
    const head = MOCK_RUNS.find((r) => r.runId === headRunId);
    if (!base || !head) throw new Error("both runs must be selected");
    const b = lastOutcomes(base.status === "live" ? eventsFor(base) : base.events);
    const h = lastOutcomes(head.status === "live" ? eventsFor(head) : head.events);

    const final = (m: Map<string, TestResultEvent[]>, id: string) =>
      m.get(id)?.[m.get(id)!.length - 1]?.outcome;
    const isFlaky = (m: Map<string, TestResultEvent[]>, id: string) => {
      const a = m.get(id);
      return !!a && a.some((e) => e.outcome === "FAILED") && a[a.length - 1]!.outcome === "PASSED";
    };

    const shared = [...h.keys()].filter((id) => b.has(id));
    const newlyFailing = shared.filter(
      (id) => final(h, id) === "FAILED" && final(b, id) === "PASSED",
    );
    const newlyPassing = shared.filter(
      (id) => final(h, id) === "PASSED" && final(b, id) === "FAILED",
    );
    const newlyFlaky = shared.filter((id) => isFlaky(h, id) && !isFlaky(b, id));
    const regressions: DurationRegression[] = shared
      .map((id) => {
        const baseMillis = b.get(id)!.reduce((s, e) => s + e.durationMillis, 0);
        const headMillis = h.get(id)!.reduce((s, e) => s + e.durationMillis, 0);
        const deltaPct = baseMillis > 0 ? ((headMillis - baseMillis) / baseMillis) * 100 : 0;
        return { testId: id, baseMillis, headMillis, deltaPct };
      })
      .filter((r) => r.deltaPct > 25 && r.baseMillis > 200)
      .sort((a, z) => z.deltaPct - a.deltaPct);

    return {
      base: stripRun(base),
      head: stripRun(head),
      newlyFailing,
      newlyPassing,
      newlyFlaky,
      regressions,
    };
  }
}

const WINDOW_DAYS = { 7: 7, 30: 30, 90: 90 } as const;

export class MockFlakyRepository implements FlakyRepository {
  async getBoard(filter: FlakyFilter): Promise<FlakyBoard> {
    await latency(340);
    const cutoff = Date.now() - WINDOW_DAYS[filter.window] * 86_400_000;
    let entries: FlakyEntry[] = MOCK_FLAKY.filter(
      (e) => Date.parse(e.lastSeen) >= cutoff - 86_400_000,
    );
    if (filter.module !== "all") entries = entries.filter((e) => e.module === filter.module);
    if (filter.quarantinedOnly) entries = entries.filter((e) => e.quarantined);
    entries = [...entries].sort((a, b) => b.score - a.score);

    const budgetAllowance = 120;
    const budgetSpent = entries.reduce((s, e) => s + e.occurrences, 0);
    return {
      entries,
      modules: [...MODULES],
      summary: {
        activeFlaky: entries.filter((e) => !e.quarantined).length,
        budgetAllowance,
        budgetSpent,
        budgetUsedPct: Math.min(999, Math.round((budgetSpent / budgetAllowance) * 100)),
        worstOffender: entries[0] ? { testId: entries[0].testId, score: entries[0].score } : null,
      },
    };
  }
}

export class MockTestHistoryRepository implements TestHistoryRepository {
  async getHistory(testId: string): Promise<TestHistory> {
    await latency(300);
    const entry = MOCK_FLAKY.find((e) => e.testId === testId);
    const history: Outcome[] = entry?.history ?? Array.from({ length: 30 }, () => "PASSED");
    const runs = MOCK_RUNS.filter((r) => r.modules.includes(moduleOf(testId)));
    const points: TestHistoryPoint[] = history
      .slice()
      .reverse()
      .map((outcome, i) => {
        const run = runs[i % Math.max(runs.length, 1)];
        const base = 900 + ((i * 137) % 2600);
        return {
          runId: run?.runId ?? `run-2026xx-${9000 - i}`,
          startedAt: new Date(Date.now() - (history.length - i) * 3 * 3_600_000).toISOString(),
          branch: run?.branch ?? "main",
          outcome,
          attempts: outcome === "PASSED" ? 1 : 2,
          durationMillis: outcome === "FAILED" ? base * 3 : base,
        };
      })
      .reverse();
    return {
      testId,
      module: moduleOf(testId),
      quarantined: entry?.quarantined ?? false,
      flakeRate: entry?.flakeRate ?? 0,
      score: entry?.score ?? 0,
      points,
      signatures: entry?.signatures ?? [],
    };
  }
}
