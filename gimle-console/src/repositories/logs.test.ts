import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MockLogsRepository, type LogsRepository } from "./logs";
import { deployments, nodes } from "./fixture";
import type { LogLine, LogTarget, StructuredLogLine } from "@/types";
import { EMPTY_LOG_FILTER, toLogFilter } from "@/lib/log-filter";

describe("MockLogsRepository", () => {
  const repo = new MockLogsRepository();
  const deployment = deployments[0];
  const instanceTarget: LogTarget = {
    kind: "instance",
    deploymentName: deployment.spec.name,
    instanceIndex: deployment.instances[0].instanceIndex,
    category: "APPLICATION",
  };
  const nodeTarget: LogTarget = { kind: "node", nodeId: nodes[0].nodeId, category: "SYSTEM" };
  const controlplaneTarget: LogTarget = { kind: "controlplane", category: "PLATFORM" };

  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("fetchPage returns exactly pageSize items and a cursor that advances pagination forward", async () => {
    const pagePromise = repo.fetchPage({
      target: controlplaneTarget,
      filter: EMPTY_LOG_FILTER,
      cursor: null,
      pageSize: 25,
    });
    await vi.advanceTimersByTimeAsync(1000);
    const page = await pagePromise;

    expect(page.items).toHaveLength(25);
    expect(page.nextCursor).not.toBeNull();
    expect(Number(page.nextCursor)).toBeGreaterThan(0);
  });

  it("fetchPage tags instance-target lines with the deployment's own identity", async () => {
    const pagePromise = repo.fetchPage({
      target: instanceTarget,
      filter: EMPTY_LOG_FILTER,
      cursor: null,
      pageSize: 10,
    });
    await vi.advanceTimersByTimeAsync(1000);
    const page = await pagePromise;

    const structured = page.items.filter((l): l is StructuredLogLine => !("raw" in l));
    expect(structured.length).toBeGreaterThan(0);
    for (const line of structured) {
      expect(line.deploymentName).toBe(deployment.spec.name);
      expect(line.instanceIndex).toBe(
        instanceTarget.kind === "instance" ? instanceTarget.instanceIndex : undefined,
      );
      expect(["APPLICATION", "PLATFORM"]).toContain(line.category);
    }
  });

  it("fetchPage for a SYSTEM-category node target may include raw capture lines", async () => {
    // SYSTEM category is the only case makeLine can emit a raw (non-structured) line for -- a
    // large enough page makes at least one likely without the test depending on exact odds.
    const pagePromise = repo.fetchPage({
      target: nodeTarget,
      filter: EMPTY_LOG_FILTER,
      cursor: null,
      pageSize: 200,
    });
    await vi.advanceTimersByTimeAsync(1000);
    const page = await pagePromise;

    for (const line of page.items) {
      if ("raw" in line) {
        expect(line.category).toBe("SYSTEM");
      } else {
        expect(["PLATFORM", "SYSTEM"]).toContain(line.category);
      }
    }
  });

  it("listCrashDumps always resolves empty, regardless of target", async () => {
    // MockLogsRepository's own listCrashDumps ignores its target parameter entirely (a rare
    // event "not worth faking", per its own comment) -- calling through the LogsRepository
    // interface type here, not the concrete class, since the class's declared signature (no
    // parameters) would otherwise make passing a target a type error.
    const asInterface: LogsRepository = repo;
    const instanceDumps = asInterface.listCrashDumps(instanceTarget);
    const controlplaneDumps = asInterface.listCrashDumps(controlplaneTarget);
    await vi.advanceTimersByTimeAsync(1000);

    expect(await instanceDumps).toEqual([]);
    expect(await controlplaneDumps).toEqual([]);
  });

  it("openFollow's stop function actually stops delivering further lines", async () => {
    const received: LogLine[] = [];
    const stop = repo.openFollow(controlplaneTarget, EMPTY_LOG_FILTER, (line) =>
      received.push(line),
    );

    await vi.advanceTimersByTimeAsync(3000);
    const countBeforeStop = received.length;
    expect(countBeforeStop).toBeGreaterThan(0);

    stop();
    await vi.advanceTimersByTimeAsync(5000);
    expect(received.length).toBe(countBeforeStop);
  });

  it("fetchPage applies a level threshold, keeping only that level and above", async () => {
    const pagePromise = repo.fetchPage({
      target: controlplaneTarget,
      filter: toLogFilter("WARN", null),
      cursor: null,
      pageSize: 200,
    });
    await vi.advanceTimersByTimeAsync(1000);
    const page = await pagePromise;

    // A filtered page is legitimately shorter than pageSize -- the mock generates weighted-random
    // levels, so the assertion is on what survives, not on how many did.
    for (const line of page.items) {
      expect("raw" in line).toBe(false);
      expect(["WARN", "ERROR"]).toContain((line as StructuredLogLine).level);
    }
  });

  it("fetchPage applies a text filter, and a query matching nothing yields an empty page", async () => {
    const pagePromise = repo.fetchPage({
      target: controlplaneTarget,
      filter: toLogFilter(null, "no such text anywhere in the fixture"),
      cursor: null,
      pageSize: 200,
    });
    await vi.advanceTimersByTimeAsync(1000);
    const page = await pagePromise;

    expect(page.items).toEqual([]);
    // Still a well-formed page: a zero-match query is a normal result, not an error or an end.
    expect(page.nextCursor).not.toBeNull();
  });

  it("openFollow only delivers lines matching the filter it was opened with", async () => {
    const received: LogLine[] = [];
    const stop = repo.openFollow(controlplaneTarget, toLogFilter("ERROR", null), (line) =>
      received.push(line),
    );

    await vi.advanceTimersByTimeAsync(60_000);
    stop();

    for (const line of received) {
      expect((line as StructuredLogLine).level).toBe("ERROR");
    }
  });
});
