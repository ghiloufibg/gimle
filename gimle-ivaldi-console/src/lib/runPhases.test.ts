import { describe, expect, it } from "vitest";

import { applyLogLine, finalizeSteps, initialSteps, markCurrentPhase } from "./runPhases";

describe("initialSteps", () => {
  it("starts every phase pending", () => {
    const steps = initialSteps();
    expect(steps.map((s) => s.status)).toEqual([
      "pending",
      "pending",
      "pending",
      "pending",
      "pending",
    ]);
    expect(steps.map((s) => s.id)).toEqual(["validate", "boot", "seed", "deploy", "active"]);
  });
});

describe("markCurrentPhase", () => {
  it("moves the named phase from pending to running, leaving everything else alone", () => {
    const steps = markCurrentPhase(initialSteps(), "boot");
    expect(steps.find((s) => s.id === "boot")?.status).toBe("running");
    expect(steps.find((s) => s.id === "validate")?.status).toBe("pending");
  });

  it("never regresses a phase that already resolved", () => {
    let steps = applyLogLine(initialSteps(), "validated 6 file(s), 0 errors");
    steps = markCurrentPhase(steps, "validate");
    expect(steps.find((s) => s.id === "validate")?.status).toBe("ok");
  });
});

describe("applyLogLine", () => {
  it("recognizes every marker RunController actually logs", () => {
    let steps = initialSteps();
    steps = applyLogLine(steps, "validated 6 file(s), 0 errors");
    steps = applyLogLine(steps, "booted 5 process(es) on machine local");
    steps = applyLogLine(steps, "pushed artifact com.example.api@1.0.0 from /tmp/api.jar");
    steps = applyLogLine(steps, "release orders-platform deployed fresh (revision 1)");
    steps = applyLogLine(steps, "run complete");

    expect(steps.map((s) => s.status)).toEqual(["ok", "ok", "ok", "ok", "ok"]);
  });

  it("marks boot skipped on a deploy-only run", () => {
    const steps = applyLogLine(initialSteps(), "topology unchanged -- deploying without a reboot");
    expect(steps.find((s) => s.id === "boot")?.status).toBe("skipped");
  });

  it("marks seed ok when there is nothing to push", () => {
    const steps = applyLogLine(initialSteps(), "no jar-sourced workloads to push");
    expect(steps.find((s) => s.id === "seed")?.status).toBe("ok");
  });

  it("leaves every step untouched when a line matches no marker", () => {
    const before = initialSteps();
    const after = applyLogLine(before, "some unrelated log line");
    expect(after).toEqual(before);
  });
});

describe("finalizeSteps", () => {
  it("resolves every pending/running step to ok on a successful run", () => {
    const steps = markCurrentPhase(initialSteps(), "validate");
    const finalized = finalizeSteps(steps, "running");
    expect(finalized.every((s) => s.status === "ok")).toBe(true);
  });

  it("fails the first unresolved step and skips the rest, leaving ok/skipped steps alone", () => {
    let steps = applyLogLine(initialSteps(), "validated 6 file(s), 0 errors");
    steps = applyLogLine(steps, "booted 5 process(es) on machine local");
    steps = markCurrentPhase(steps, "seed");

    const finalized = finalizeSteps(steps, "failed");

    expect(finalized.map((s) => s.status)).toEqual(["ok", "ok", "failed", "skipped", "skipped"]);
  });
});
