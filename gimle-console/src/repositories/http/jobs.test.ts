import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpJobsRepository } from "./jobs";
import { jsonResponse, okResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

const RAW_JOB = {
  spec: {
    name: "nightly-cleanup",
    moduleId: { name: "cleanup-job", version: "1.0.0" },
    artifactPath: "s3://bucket/cleanup-job-1.0.0.jar",
    backoffLimit: 3,
  },
  phase: "RUNNING",
  currentRun: {
    attempt: 0,
    nodeId: "node-1",
    observation: {
      lifecycleState: "ACTIVE",
      alive: true,
      ready: false,
      requestRatePerSecond: 0,
      queueDepth: 0,
      cpuMillicoresUsed: 200,
      memoryBytesUsed: 2048,
    },
  },
};

describe("HttpJobsRepository", () => {
  it("maps a missing spec.tenantId to null and a missing currentRun to null", async () => {
    const withoutRun = { spec: { ...RAW_JOB.spec }, phase: "SUCCEEDED" };
    stubFetchSequence([() => jsonResponse([withoutRun])]);

    const repo = new HttpJobsRepository();
    const all = await repo.all(true);

    expect(all[0].spec.tenantId).toBeNull();
    expect(all[0].currentRun).toBeNull();
    expect(all[0].phase).toBe("SUCCEEDED");
  });

  it("maps a currentRun with no observation yet to a null observation, not a missing field", async () => {
    const noObservationYet = {
      ...RAW_JOB,
      currentRun: { attempt: 0, nodeId: "node-1" },
    };
    stubFetchSequence([() => jsonResponse([noObservationYet])]);

    const repo = new HttpJobsRepository();
    const all = await repo.all(true);

    expect(all[0].currentRun).toEqual({ attempt: 0, nodeId: "node-1", observation: null });
  });

  it("fetchOne GETs /jobs/{name} and maps the result", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse(RAW_JOB)]);
    const repo = new HttpJobsRepository();

    const job = await repo.fetchOne("nightly-cleanup");

    expect(job.spec.name).toBe("nightly-cleanup");
    expect(job.currentRun?.attempt).toBe(0);
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/jobs/nightly-cleanup");
  });

  it("create() PUTs a YAML manifest with kind: Job, busts the cache, then re-fetches the created job", async () => {
    const fetchMock = stubFetchSequence([() => okResponse(), () => jsonResponse(RAW_JOB)]);
    const repo = new HttpJobsRepository();

    const created = await repo.create({
      name: "nightly-cleanup",
      moduleId: { name: "cleanup-job", version: "1.0.0" },
      artifactPath: "s3://bucket/cleanup-job-1.0.0.jar",
      backoffLimit: 3,
      tenantId: null,
    });

    expect(created.spec.name).toBe("nightly-cleanup");
    expect(fetchMock).toHaveBeenCalledTimes(2);

    const [putUrl, putInit] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(putUrl).toBe("/jobs/nightly-cleanup");
    expect(putInit.method).toBe("PUT");
    const yaml = putInit.body as string;
    expect(yaml).toContain("kind: Job");
    expect(yaml).toContain('name: "nightly-cleanup"');
    expect(yaml).toContain('  name: "cleanup-job"');
    expect(yaml).toContain('  version: "1.0.0"');
    expect(yaml).toContain("backoffLimit: 3");
    expect(yaml).not.toContain("tenantId:");
    expect(yaml).not.toContain("activeDeadlineSeconds:");

    const [getUrl] = fetchMock.mock.calls[1] as [string];
    expect(getUrl).toBe("/jobs/nightly-cleanup");
  });

  it("create() includes activeDeadlineSeconds when present", async () => {
    const fetchMock = stubFetchSequence([() => okResponse(), () => jsonResponse(RAW_JOB)]);
    const repo = new HttpJobsRepository();

    await repo.create({
      name: "nightly-cleanup",
      moduleId: { name: "cleanup-job", version: "1.0.0" },
      artifactPath: "s3://bucket/cleanup-job-1.0.0.jar",
      backoffLimit: 3,
      activeDeadlineSeconds: 600,
      tenantId: "tenant-1",
    });

    const [, putInit] = fetchMock.mock.calls[0] as [string, RequestInit];
    const yaml = putInit.body as string;
    expect(yaml).toContain("activeDeadlineSeconds: 600");
    expect(yaml).toContain('tenantId: "tenant-1"');
  });

  it("create() omits artifactPath when blank, so the server resolves it from Andvari", async () => {
    const fetchMock = stubFetchSequence([() => okResponse(), () => jsonResponse(RAW_JOB)]);
    const repo = new HttpJobsRepository();

    await repo.create({
      name: "nightly-cleanup",
      moduleId: { name: "cleanup-job", version: "1.0.0" },
      artifactPath: "",
      backoffLimit: 3,
      tenantId: null,
    });

    const [, putInit] = fetchMock.mock.calls[0] as [string, RequestInit];
    const yaml = putInit.body as string;
    expect(yaml).not.toContain("artifactPath:");
  });

  it("remove() DELETEs /jobs/{name}", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpJobsRepository();

    await repo.remove("nightly-cleanup");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/jobs/nightly-cleanup");
    expect(init.method).toBe("DELETE");
  });
});
