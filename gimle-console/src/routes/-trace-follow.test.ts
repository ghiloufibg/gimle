import { describe, expect, it } from "vitest";

import type { FoundSpan, TracesHistoryRepository } from "@/repositories/tracesHistory";
import type { ProcessTarget, TraceSpanLine } from "@/types";
import { coverageSummary, groupSpansByProcess, searchTrace, spanDepths } from "./-trace-follow";

// Pure cross-process assembly logic only -- this project's vitest config is deliberately
// node-environment (see vitest.config.ts), so the panel that renders these results is verified in
// a real browser instead, not here.

function span(overrides: Partial<TraceSpanLine>): TraceSpanLine {
  return {
    timestamp: "2026-08-10T10:00:00Z",
    traceId: "trace-1",
    spanId: "span-1",
    parentSpanId: "",
    name: "Greeter/greet",
    kind: "SERVER",
    status: "OK",
    ...overrides,
  };
}

/** A repository whose trace search is scripted, including a search that fails outright. */
function fakeRepo(hits: FoundSpan[] | Error, truncated = false): TracesHistoryRepository {
  return {
    async searchByTraceId(traceId: string) {
      if (hits instanceof Error) throw hits;
      return { traceId, spans: hits, truncated };
    },
    async fetchProcessKinds() {
      throw new Error("not used");
    },
    async fetchPage() {
      throw new Error("not used");
    },
    async fetchSince() {
      throw new Error("not used");
    },
    openPoll() {
      return () => {};
    },
  };
}

const workerA: ProcessTarget = { processKind: "WORKER", processId: "node-a:worker-1" };
const workerB: ProcessTarget = { processKind: "WORKER", processId: "node-b:worker-2" };

function hit(target: ProcessTarget, line: TraceSpanLine): FoundSpan {
  return { processKind: target.processKind, processId: target.processId, span: line };
}

describe("searchTrace", () => {
  it("orders the spans the search returned by time, keeping where each ran", async () => {
    const repo = fakeRepo([
      hit(
        workerB,
        span({ spanId: "server", parentSpanId: "client", timestamp: "2026-08-10T10:00:01Z" }),
      ),
      hit(workerA, span({ spanId: "client", timestamp: "2026-08-10T10:00:00Z", kind: "CLIENT" })),
    ]);

    const result = await searchTrace(repo, "trace-1");

    expect(result.spans.map((s) => s.span.spanId)).toEqual(["client", "server"]);
    expect(result.spans.map((s) => s.target.processId)).toEqual([
      "node-a:worker-1",
      "node-b:worker-2",
    ]);
    expect(result.failure).toBeNull();
    expect(result.danglingParentSpanIds).toEqual([]);
  });

  it("reports a failed search rather than an empty trace", async () => {
    const repo = fakeRepo(new Error("trace search unavailable (404)"));

    const result = await searchTrace(repo, "trace-1");

    expect(result.spans).toEqual([]);
    expect(result.failure).toBe("trace search unavailable (404)");
    expect(coverageSummary(result)).toContain("trace search unavailable (404)");
  });

  it("says so when the search stopped at its limit", async () => {
    const repo = fakeRepo([hit(workerA, span({ spanId: "one" }))], true);

    expect(coverageSummary(await searchTrace(repo, "trace-1"))).toContain(
      "truncated at the search limit",
    );
  });

  it("flags a parent span the search never found", async () => {
    const repo = fakeRepo([
      hit(workerA, span({ spanId: "orphan", parentSpanId: "ran-somewhere-else" })),
    ]);

    const result = await searchTrace(repo, "trace-1");

    expect(result.danglingParentSpanIds).toEqual(["ran-somewhere-else"]);
    expect(coverageSummary(result)).toContain("this trace is incomplete");
  });
});

describe("grouping and nesting", () => {
  it("groups spans under the process they were found in", () => {
    const spans = [
      { span: span({ spanId: "a" }), target: workerA },
      { span: span({ spanId: "b" }), target: workerB },
      { span: span({ spanId: "c" }), target: workerA },
    ];

    const groups = groupSpansByProcess(spans);

    expect(groups.map((g) => g.target.processId)).toEqual(["node-a:worker-1", "node-b:worker-2"]);
    expect(groups[0].spans.map((s) => s.spanId)).toEqual(["a", "c"]);
  });

  it("nests a child under its parent and treats an unresolvable parent as a root", () => {
    const spans = [
      { span: span({ spanId: "root" }), target: workerA },
      { span: span({ spanId: "child", parentSpanId: "root" }), target: workerB },
      { span: span({ spanId: "grandchild", parentSpanId: "child" }), target: workerB },
      { span: span({ spanId: "orphan", parentSpanId: "missing" }), target: workerB },
    ];

    const depths = spanDepths(spans);

    expect(depths.get("root")).toBe(0);
    expect(depths.get("child")).toBe(1);
    expect(depths.get("grandchild")).toBe(2);
    expect(depths.get("orphan")).toBe(0);
  });
});
