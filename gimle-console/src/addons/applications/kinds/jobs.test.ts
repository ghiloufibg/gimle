import { describe, expect, it } from "vitest";

import type { CronJob, InstanceObservation, Job, JobPhase } from "@/types";
import { fromCronJob, fromJob, isGeneratedBy } from "@/addons/applications/kinds/jobs";

const OBSERVATION: InstanceObservation = {
  lifecycleState: "ACTIVE",
  alive: true,
  ready: true,
  requestRatePerSecond: 0,
  errorRatePerSecond: 0,
  queueDepth: 0,
  cpuMillicoresUsed: 0,
  memoryBytesUsed: 0,
  workerId: null,
};

function job(name: string, phase: JobPhase, overrides: Partial<Job> = {}): Job {
  return {
    spec: {
      name,
      moduleId: { name: "reindexer", version: "1.2.0" },
      artifactPath: "",
      backoffLimit: 3,
      tenantId: null,
    },
    phase,
    currentRun: { attempt: 1, nodeId: "node-a", observation: OBSERVATION },
    ...overrides,
  };
}

function cronJob(overrides: Partial<CronJob> = {}): CronJob {
  return {
    spec: {
      name: "nightly-report",
      schedule: "0 2 * * *",
      jobTemplate: {
        moduleId: { name: "reporter", version: "1.0.0" },
        artifactPath: "",
        backoffLimit: 1,
      },
      concurrencyPolicy: "FORBID",
      tenantId: null,
    },
    lastScheduleTime: "2026-09-02T02:00:00Z",
    ...overrides,
  };
}

describe("job health", () => {
  it("reads a SUCCEEDED job as healthy -- its desired state is having run", () => {
    const app = fromJob(job("reindex", "SUCCEEDED"), []);
    expect(app.health).toBe("Healthy");
    expect(app.sync).toBe("Synced");
    expect(app.conditions).toEqual([]);
  });

  it("reads a RUNNING job as progressing", () => {
    expect(fromJob(job("reindex", "RUNNING"), []).health).toBe("Progressing");
  });

  it("reads a FAILED job as degraded, naming the exhausted backoff limit", () => {
    const app = fromJob(
      job("reindex", "FAILED", {
        currentRun: { attempt: 3, nodeId: "node-a", observation: OBSERVATION },
      }),
      [],
    );
    expect(app.health).toBe("Degraded");
    expect(app.conditions[0].message).toBe("attempt 3 of 3 failed, exhausting the backoff limit");
  });

  it("flags a retry in flight without calling the job degraded", () => {
    const app = fromJob(
      job("reindex", "RUNNING", {
        currentRun: { attempt: 2, nodeId: "node-a", observation: OBSERVATION },
      }),
      [],
    );
    expect(app.health).toBe("Progressing");
    expect(app.conditions.map((c) => c.type)).toEqual(["Retrying"]);
  });

  it("is out of sync when it believes it is running with nothing placed", () => {
    const app = fromJob(job("reindex", "RUNNING", { currentRun: null }), []);
    expect(app.sync).toBe("OutOfSync");
    expect(app.conditions.map((c) => c.type)).toEqual(["Unplaced"]);
  });

  it("addresses an attempt as instance 0, the index its logs live under", () => {
    const app = fromJob(
      job("reindex", "RUNNING", {
        currentRun: { attempt: 2, nodeId: "node-b", observation: OBSERVATION },
      }),
      [],
    );
    expect(app.instances).toHaveLength(1);
    expect(app.instances[0].instanceIndex).toBe(0);
    expect(app.instances[0].label).toBe("attempt 2");
  });
});

describe("matching a generated job to its schedule", () => {
  it("matches the reconciler's own <name>-<epochSeconds> naming", () => {
    expect(isGeneratedBy(job("nightly-report-1756778400", "SUCCEEDED"), cronJob())).toBe(true);
  });

  it("never matches a hand-applied job that merely shares the prefix", () => {
    expect(isGeneratedBy(job("nightly-report-v2", "SUCCEEDED"), cronJob())).toBe(false);
    expect(isGeneratedBy(job("nightly-report-", "SUCCEEDED"), cronJob())).toBe(false);
  });

  it("never matches another tenant's same-named generated job", () => {
    const other = job("nightly-report-1756778400", "SUCCEEDED");
    other.spec.tenantId = "acme";
    expect(isGeneratedBy(other, cronJob())).toBe(false);
  });
});

describe("cronjob health", () => {
  it("takes its verdict from the newest generated job, not the oldest", () => {
    const app = fromCronJob(
      cronJob(),
      [job("nightly-report-1756692000", "SUCCEEDED"), job("nightly-report-1756778400", "FAILED")],
      [],
    );
    expect(app.health).toBe("Degraded");
    expect(app.conditions[0].message).toContain("nightly-report-1756778400");
    expect(app.detail.type === "cronjob" && app.detail.generatedJobs[0].name).toBe(
      "nightly-report-1756778400",
    );
  });

  it("is healthy before it has ever fired -- waiting is what it was asked to do", () => {
    const app = fromCronJob(cronJob({ lastScheduleTime: null }), [], []);
    expect(app.health).toBe("Healthy");
    expect(app.conditions).toEqual([]);
  });

  it("is unknown when it fired but nothing it generated is still listed", () => {
    const app = fromCronJob(cronJob(), [], []);
    expect(app.health).toBe("Unknown");
    expect(app.conditions.map((c) => c.type)).toEqual(["GeneratedJobMissing"]);
  });

  it("shows at most five generated jobs, newest first", () => {
    const jobs = [0, 1, 2, 3, 4, 5, 6].map((i) =>
      job(`nightly-report-${1756000000 + i}`, "SUCCEEDED"),
    );
    const app = fromCronJob(cronJob(), jobs, []);
    const shown = app.detail.type === "cronjob" ? app.detail.generatedJobs : [];
    expect(shown.map((j) => j.name)).toEqual([
      "nightly-report-1756000006",
      "nightly-report-1756000005",
      "nightly-report-1756000004",
      "nightly-report-1756000003",
      "nightly-report-1756000002",
    ]);
  });

  it("runs nothing of its own, so it carries no instances", () => {
    const app = fromCronJob(cronJob(), [job("nightly-report-1756778400", "RUNNING")], []);
    expect(app.instances).toEqual([]);
    expect(app.sync).toBe("Synced");
  });
});
