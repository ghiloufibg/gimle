import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpCronJobsRepository } from "./cronjobs";
import { jsonResponse, okResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

const RAW_CRONJOB = {
  spec: {
    name: "nightly-cleanup",
    schedule: "0 2 * * *",
    jobTemplate: {
      moduleId: { name: "cleanup-job", version: "1.0.0" },
      artifactPath: "s3://bucket/cleanup-job-1.0.0.jar",
      backoffLimit: 3,
    },
    concurrencyPolicy: "FORBID",
  },
  lastScheduleTime: "2026-01-01T02:00:00Z",
};

describe("HttpCronJobsRepository", () => {
  it("maps a missing spec.tenantId to null and a missing lastScheduleTime to null", async () => {
    const withoutOptionals = { spec: { ...RAW_CRONJOB.spec } };
    stubFetchSequence([() => jsonResponse([withoutOptionals])]);

    const repo = new HttpCronJobsRepository();
    const all = await repo.all(true);

    expect(all[0].spec.tenantId).toBeNull();
    expect(all[0].lastScheduleTime).toBeNull();
  });

  it("fetchOne GETs /cronjobs/{name} and maps the result", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse(RAW_CRONJOB)]);
    const repo = new HttpCronJobsRepository();

    const cronJob = await repo.fetchOne("nightly-cleanup");

    expect(cronJob.spec.name).toBe("nightly-cleanup");
    expect(cronJob.lastScheduleTime).toBe("2026-01-01T02:00:00Z");
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/cronjobs/nightly-cleanup");
  });

  it("create() PUTs a YAML manifest with kind: CronJob, busts the cache, then re-fetches the created cronjob", async () => {
    const fetchMock = stubFetchSequence([() => okResponse(), () => jsonResponse(RAW_CRONJOB)]);
    const repo = new HttpCronJobsRepository();

    const created = await repo.create({
      name: "nightly-cleanup",
      schedule: "0 2 * * *",
      jobTemplate: {
        moduleId: { name: "cleanup-job", version: "1.0.0" },
        artifactPath: "s3://bucket/cleanup-job-1.0.0.jar",
        backoffLimit: 3,
      },
      concurrencyPolicy: "FORBID",
      tenantId: null,
    });

    expect(created.spec.name).toBe("nightly-cleanup");
    expect(fetchMock).toHaveBeenCalledTimes(2);

    const [putUrl, putInit] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(putUrl).toBe("/cronjobs/nightly-cleanup");
    expect(putInit.method).toBe("PUT");
    const yaml = putInit.body as string;
    expect(yaml).toContain("kind: CronJob");
    expect(yaml).toContain('name: "nightly-cleanup"');
    expect(yaml).toContain('schedule: "0 2 * * *"');
    expect(yaml).toContain("backoffLimit: 3");
    expect(yaml).toContain("concurrencyPolicy: FORBID");
    expect(yaml).not.toContain("tenantId:");
    expect(yaml).not.toContain("startingDeadlineSeconds:");

    const [getUrl] = fetchMock.mock.calls[1] as [string];
    expect(getUrl).toBe("/cronjobs/nightly-cleanup");
  });

  it("create() includes startingDeadlineSeconds and tenantId when present", async () => {
    const fetchMock = stubFetchSequence([() => okResponse(), () => jsonResponse(RAW_CRONJOB)]);
    const repo = new HttpCronJobsRepository();

    await repo.create({
      name: "nightly-cleanup",
      schedule: "0 2 * * *",
      jobTemplate: {
        moduleId: { name: "cleanup-job", version: "1.0.0" },
        artifactPath: "s3://bucket/cleanup-job-1.0.0.jar",
        backoffLimit: 3,
      },
      startingDeadlineSeconds: 300,
      concurrencyPolicy: "ALLOW",
      tenantId: "tenant-1",
    });

    const [, putInit] = fetchMock.mock.calls[0] as [string, RequestInit];
    const yaml = putInit.body as string;
    expect(yaml).toContain("startingDeadlineSeconds: 300");
    expect(yaml).toContain('tenantId: "tenant-1"');
  });

  it("create() omits artifactPath when blank, so the server resolves it from Andvari", async () => {
    const fetchMock = stubFetchSequence([() => okResponse(), () => jsonResponse(RAW_CRONJOB)]);
    const repo = new HttpCronJobsRepository();

    await repo.create({
      name: "nightly-cleanup",
      schedule: "0 2 * * *",
      jobTemplate: {
        moduleId: { name: "cleanup-job", version: "1.0.0" },
        artifactPath: "",
        backoffLimit: 3,
      },
      concurrencyPolicy: "ALLOW",
      tenantId: null,
    });

    const [, putInit] = fetchMock.mock.calls[0] as [string, RequestInit];
    const yaml = putInit.body as string;
    expect(yaml).not.toContain("artifactPath:");
  });

  it("remove() DELETEs /cronjobs/{name}", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpCronJobsRepository();

    await repo.remove("nightly-cleanup");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/cronjobs/nightly-cleanup");
    expect(init.method).toBe("DELETE");
  });

  it("trigger() POSTs /cronjobs/{name}/trigger and returns the generated job name", async () => {
    const fetchMock = stubFetchSequence([
      () => jsonResponse({ jobName: "nightly-cleanup-1735689600" }),
    ]);
    const repo = new HttpCronJobsRepository();

    const jobName = await repo.trigger("nightly-cleanup");

    expect(jobName).toBe("nightly-cleanup-1735689600");
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/cronjobs/nightly-cleanup/trigger");
    expect(init.method).toBe("POST");
  });
});
