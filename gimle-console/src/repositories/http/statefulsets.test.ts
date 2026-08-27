import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpStatefulSetsRepository } from "./statefulsets";
import { jsonResponse, okResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

const RAW_STATEFULSET = {
  spec: {
    name: "orders",
    moduleId: { name: "orders", version: "1.0.0" },
    artifactPath: "s3://bucket/orders-1.0.0.jar",
    replicas: 3,
  },
  instances: [
    {
      instanceIndex: 0,
      nodeId: "node-1",
      observation: {
        lifecycleState: "ACTIVE",
        alive: true,
        ready: true,
        requestRatePerSecond: 1.5,
        queueDepth: 0,
        cpuMillicoresUsed: 100,
        memoryBytesUsed: 1024,
      },
    },
  ],
  unplacedCount: 2,
};

describe("HttpStatefulSetsRepository", () => {
  it("maps a missing spec.tenantId to null and a missing instance observation to a zeroed default", async () => {
    const withoutObservation = {
      ...RAW_STATEFULSET,
      instances: [{ instanceIndex: 1, nodeId: "node-2" }],
    };
    stubFetchSequence([() => jsonResponse([withoutObservation])]);

    const repo = new HttpStatefulSetsRepository();
    const all = await repo.all(true);

    expect(all[0].spec.tenantId).toBeNull();
    expect(all[0].instances[0].observation).toEqual({
      lifecycleState: "INSTALLED",
      alive: false,
      ready: false,
      requestRatePerSecond: 0,
      queueDepth: 0,
      cpuMillicoresUsed: 0,
      memoryBytesUsed: 0,
      workerId: null,
    });
  });

  it("fetchOne GETs /statefulsets/{name} and maps the result", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse(RAW_STATEFULSET)]);
    const repo = new HttpStatefulSetsRepository();

    const statefulSet = await repo.fetchOne("orders");

    expect(statefulSet.spec.name).toBe("orders");
    expect(statefulSet.instances).toHaveLength(1);
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/statefulsets/orders");
  });

  it("create() PUTs a YAML manifest with kind: StatefulSet, busts the cache, then re-fetches", async () => {
    const fetchMock = stubFetchSequence([() => okResponse(), () => jsonResponse(RAW_STATEFULSET)]);
    const repo = new HttpStatefulSetsRepository();

    const created = await repo.create({
      name: "orders",
      moduleId: { name: "orders", version: "1.0.0" },
      artifactPath: "s3://bucket/orders-1.0.0.jar",
      replicas: 3,
      tenantId: null,
    });

    expect(created.spec.name).toBe("orders");
    expect(fetchMock).toHaveBeenCalledTimes(2);

    const [putUrl, putInit] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(putUrl).toBe("/statefulsets/orders");
    expect(putInit.method).toBe("PUT");
    const yaml = putInit.body as string;
    expect(yaml).toContain("kind: StatefulSet");
    expect(yaml).toContain('name: "orders"');
    expect(yaml).toContain("replicas: 3");
    expect(yaml).not.toContain("tenantId:");

    const [getUrl] = fetchMock.mock.calls[1] as [string];
    expect(getUrl).toBe("/statefulsets/orders");
  });

  it("remove() DELETEs /statefulsets/{name}", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpStatefulSetsRepository();

    await repo.remove("orders");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/statefulsets/orders");
    expect(init.method).toBe("DELETE");
  });
});
