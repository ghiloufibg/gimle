import { describe, expect, it } from "vitest";

import type { TracesHistoryRepository, TracesPageArgs } from "@/repositories/tracesHistory";
import type { HistoryEnvelope, ModuleInstance, ProcessTarget, TraceSpanLine } from "@/types";
import {
  coverageSummary,
  followTraceAcrossProcesses,
  groupSpansByProcess,
  spanDepths,
  workerTargetsFromInstances,
} from "./-trace-follow";

// Pure cross-process assembly logic only -- this project's vitest config is deliberately
// node-environment (see vitest.config.ts), so the panel that renders these results is verified in
// a real browser instead, not here.

function instance(overrides: Partial<ModuleInstance>): ModuleInstance {
  return {
    deploymentName: "greeter",
    instanceIndex: 0,
    moduleId: { name: "greeter", version: "1.0.0" },
    artifactPath: "/tmp/greeter.jar",
    tenantId: null,
    nodeId: "node-a",
    lifecycleState: "ACTIVE",
    alive: true,
    ready: true,
    requestRatePerSecond: 0,
    errorRatePerSecond: 0,
    queueDepth: 0,
    cpuMillicoresUsed: 0,
    memoryBytesUsed: 0,
    workerId: "worker-1",
    ...overrides,
  } as ModuleInstance;
}

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

/** A repository whose per-target pages are scripted, including targets that throw. */
function fakeRepo(
  pagesByProcessId: Record<string, TraceSpanLine[][] | Error>,
): TracesHistoryRepository {
  return {
    async fetchProcessKinds() {
      throw new Error("not used");
    },
    async fetchPage({ target, cursor }: TracesPageArgs): Promise<HistoryEnvelope<TraceSpanLine>> {
      const scripted = pagesByProcessId[target.processId];
      if (scripted instanceof Error) throw scripted;
      const pages = scripted ?? [];
      const index = cursor === null ? 0 : Number(cursor);
      const lines = pages[index] ?? [];
      const hasMore = index + 1 < pages.length;
      return { lines, olderCursor: hasMore ? String(index + 1) : null, newerCursor: null };
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

describe("workerTargetsFromInstances", () => {
  it("derives one deduplicated WORKER target per distinct node/worker pair", () => {
    const targets = workerTargetsFromInstances([
      instance({ nodeId: "node-a", workerId: "worker-1" }),
      instance({ nodeId: "node-a", workerId: "worker-1", instanceIndex: 1 }),
      instance({ nodeId: "node-b", workerId: "worker-2" }),
    ]);

    expect(targets).toEqual([workerA, workerB]);
  });

  it("skips an instance whose worker has not reported an id yet", () => {
    expect(workerTargetsFromInstances([instance({ workerId: null })])).toEqual([]);
  });
});

describe("followTraceAcrossProcesses", () => {
  it("collects the same trace's spans from two different processes, ordered by time", async () => {
    const repo = fakeRepo({
      "node-a:worker-1": [
        [span({ spanId: "client", timestamp: "2026-08-10T10:00:00Z", kind: "CLIENT" })],
      ],
      "node-b:worker-2": [
        [
          span({
            spanId: "server",
            parentSpanId: "client",
            timestamp: "2026-08-10T10:00:01Z",
          }),
        ],
      ],
    });

    const result = await followTraceAcrossProcesses(repo, "trace-1", [workerA, workerB]);

    expect(result.spans.map((s) => s.span.spanId)).toEqual(["client", "server"]);
    expect(result.spans.map((s) => s.target.processId)).toEqual([
      "node-a:worker-1",
      "node-b:worker-2",
    ]);
    expect(result.failures).toEqual([]);
    expect(result.danglingParentSpanIds).toEqual([]);
  });

  it("ignores spans belonging to other traces in the same history", async () => {
    const repo = fakeRepo({
      "node-a:worker-1": [
        [span({ spanId: "mine" }), span({ spanId: "theirs", traceId: "trace-other" })],
      ],
    });

    const result = await followTraceAcrossProcesses(repo, "trace-1", [workerA]);

    expect(result.spans.map((s) => s.span.spanId)).toEqual(["mine"]);
  });

  it("walks older pages until the history runs out, within the page cap", async () => {
    const repo = fakeRepo({
      "node-a:worker-1": [
        [span({ spanId: "newest", timestamp: "2026-08-10T10:00:02Z" })],
        [span({ spanId: "older", timestamp: "2026-08-10T10:00:01Z" })],
        [span({ spanId: "oldest", timestamp: "2026-08-10T10:00:00Z" })],
      ],
    });

    const capped = await followTraceAcrossProcesses(repo, "trace-1", [workerA], {
      maxPagesPerTarget: 2,
    });
    expect(capped.spans.map((s) => s.span.spanId)).toEqual(["older", "newest"]);

    const full = await followTraceAcrossProcesses(repo, "trace-1", [workerA], {
      maxPagesPerTarget: 5,
    });
    expect(full.spans.map((s) => s.span.spanId)).toEqual(["oldest", "older", "newest"]);
  });

  it("reports an unreachable process instead of failing the whole search", async () => {
    const repo = fakeRepo({
      "node-a:worker-1": [[span({ spanId: "found" })]],
      "node-b:worker-2": new Error("traces history unavailable (404)"),
    });

    const result = await followTraceAcrossProcesses(repo, "trace-1", [workerA, workerB]);

    expect(result.spans.map((s) => s.span.spanId)).toEqual(["found"]);
    expect(result.failures).toEqual([
      { target: workerB, message: "traces history unavailable (404)" },
    ]);
    expect(result.searched).toEqual([workerA, workerB]);
  });

  it("flags a parent span that no searched process could produce", async () => {
    const repo = fakeRepo({
      "node-a:worker-1": [[span({ spanId: "orphan", parentSpanId: "ran-somewhere-else" })]],
    });

    const result = await followTraceAcrossProcesses(repo, "trace-1", [workerA]);

    expect(result.danglingParentSpanIds).toEqual(["ran-somewhere-else"]);
    expect(coverageSummary(result)).toContain("this trace is incomplete");
  });

  it("searches nothing and finds nothing when no worker can be named", async () => {
    const result = await followTraceAcrossProcesses(fakeRepo({}), "trace-1", []);

    expect(result.spans).toEqual([]);
    expect(result.searched).toEqual([]);
    expect(coverageSummary(result)).toBe("0 spans found · 0/0 worker processes read");
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
