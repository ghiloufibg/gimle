import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpDaemonSetsRepository } from "./daemonsets";
import { jsonResponse, okResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

const RAW_DAEMONSET = {
  spec: {
    name: "node-exporter",
    moduleId: { name: "node-exporter", version: "1.0.0" },
    artifactPath: "s3://bucket/node-exporter-1.0.0.jar",
    placement: { requiredLabels: ["gpu"] },
  },
  instances: [
    {
      nodeId: "node-1",
      observation: {
        lifecycleState: "ACTIVE",
        alive: true,
        ready: true,
        requestRatePerSecond: 0,
        queueDepth: 0,
        cpuMillicoresUsed: 50,
        memoryBytesUsed: 1024,
      },
    },
  ],
};

describe("HttpDaemonSetsRepository", () => {
  it("maps a missing spec.tenantId to null, a missing placement.requiredLabels to [], and a missing instance observation to a zeroed default", async () => {
    const bare = {
      spec: {
        name: "node-exporter",
        moduleId: { name: "node-exporter", version: "1.0.0" },
        artifactPath: "s3://bucket/node-exporter-1.0.0.jar",
        placement: {},
      },
      instances: [{ nodeId: "node-2" }],
    };
    stubFetchSequence([() => jsonResponse([bare])]);

    const repo = new HttpDaemonSetsRepository();
    const all = await repo.all(true);

    expect(all[0].spec.tenantId).toBeNull();
    expect(all[0].spec.placement.requiredNodeLabels).toEqual([]);
    expect(all[0].instances[0].observation).toEqual({
      lifecycleState: "INSTALLED",
      alive: false,
      ready: false,
      requestRatePerSecond: 0,
      errorRatePerSecond: 0,
      queueDepth: 0,
      cpuMillicoresUsed: 0,
      memoryBytesUsed: 0,
      workerId: null,
    });
  });

  it("fetchOne GETs /daemonsets/{name} and maps the result", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse(RAW_DAEMONSET)]);
    const repo = new HttpDaemonSetsRepository();

    const daemonSet = await repo.fetchOne("node-exporter");

    expect(daemonSet.spec.name).toBe("node-exporter");
    expect(daemonSet.spec.placement.requiredNodeLabels).toEqual(["gpu"]);
    expect(daemonSet.instances).toHaveLength(1);
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/daemonsets/node-exporter");
  });

  it("create() PUTs a YAML manifest with kind: DaemonSet and requiredLabels, busts the cache, then re-fetches", async () => {
    const fetchMock = stubFetchSequence([() => okResponse(), () => jsonResponse(RAW_DAEMONSET)]);
    const repo = new HttpDaemonSetsRepository();

    const created = await repo.create({
      name: "node-exporter",
      moduleId: { name: "node-exporter", version: "1.0.0" },
      artifactPath: "s3://bucket/node-exporter-1.0.0.jar",
      placement: { requiredNodeLabels: ["gpu"] },
      tenantId: null,
    });

    expect(created.spec.name).toBe("node-exporter");
    expect(fetchMock).toHaveBeenCalledTimes(2);

    const [putUrl, putInit] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(putUrl).toBe("/daemonsets/node-exporter");
    expect(putInit.method).toBe("PUT");
    const yaml = putInit.body as string;
    expect(yaml).toContain("kind: DaemonSet");
    expect(yaml).toContain('name: "node-exporter"');
    expect(yaml).toContain("placement:");
    expect(yaml).toContain('requiredLabels: ["gpu"]');
    expect(yaml).not.toContain("tenantId:");
    expect(yaml).not.toContain("antiAffinity");

    const [getUrl] = fetchMock.mock.calls[1] as [string];
    expect(getUrl).toBe("/daemonsets/node-exporter");
  });

  it("create() omits the placement block entirely when no labels are required", async () => {
    const fetchMock = stubFetchSequence([() => okResponse(), () => jsonResponse(RAW_DAEMONSET)]);
    const repo = new HttpDaemonSetsRepository();

    await repo.create({
      name: "node-exporter",
      moduleId: { name: "node-exporter", version: "1.0.0" },
      artifactPath: "s3://bucket/node-exporter-1.0.0.jar",
      placement: { requiredNodeLabels: [] },
      tenantId: "tenant-1",
    });

    const [, putInit] = fetchMock.mock.calls[0] as [string, RequestInit];
    const yaml = putInit.body as string;
    expect(yaml).not.toContain("placement:");
    expect(yaml).toContain('tenantId: "tenant-1"');
  });

  it("remove() DELETEs /daemonsets/{name}", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpDaemonSetsRepository();

    await repo.remove("node-exporter");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/daemonsets/node-exporter");
    expect(init.method).toBe("DELETE");
  });
});
