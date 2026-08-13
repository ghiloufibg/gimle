import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpDeploymentsRepository } from "./deployments";
import { jsonResponse, okResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

const RAW_DEPLOYMENT = {
  spec: {
    name: "checkout-service",
    moduleId: { name: "checkout-service", version: "1.2.3" },
    artifactPath: "s3://bucket/checkout-service-1.2.3.jar",
    replicas: 2,
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
  unplacedCount: 1,
  quotaViolating: false,
};

describe("HttpDeploymentsRepository", () => {
  it("maps a missing spec.tenantId to null and a missing instance observation to a zeroed default", async () => {
    const withoutObservation = {
      ...RAW_DEPLOYMENT,
      instances: [{ instanceIndex: 1, nodeId: "node-2" }],
    };
    stubFetchSequence([() => jsonResponse([withoutObservation])]);

    const repo = new HttpDeploymentsRepository();
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
    });
  });

  it("fetchOne GETs /deployments/{name} and maps the result", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse(RAW_DEPLOYMENT)]);
    const repo = new HttpDeploymentsRepository();

    const deployment = await repo.fetchOne("checkout-service");

    expect(deployment.spec.name).toBe("checkout-service");
    expect(deployment.instances).toHaveLength(1);
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/deployments/checkout-service");
  });

  it("create() PUTs a YAML manifest, busts the cache, then re-fetches the created deployment", async () => {
    const fetchMock = stubFetchSequence([() => okResponse(), () => jsonResponse(RAW_DEPLOYMENT)]);
    const repo = new HttpDeploymentsRepository();

    const created = await repo.create({
      name: "checkout-service",
      moduleId: { name: "checkout-service", version: "1.2.3" },
      artifactPath: "s3://bucket/checkout-service-1.2.3.jar",
      replicas: 2,
      tenantId: null,
    });

    expect(created.spec.name).toBe("checkout-service");
    expect(fetchMock).toHaveBeenCalledTimes(2);

    const [putUrl, putInit] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(putUrl).toBe("/deployments/checkout-service");
    expect(putInit.method).toBe("PUT");
    const yaml = putInit.body as string;
    expect(yaml).toContain("kind: Deployment");
    expect(yaml).toContain('name: "checkout-service"');
    expect(yaml).toContain('  name: "checkout-service"');
    expect(yaml).toContain('  version: "1.2.3"');
    expect(yaml).toContain("replicas: 2");
    expect(yaml).not.toContain("tenantId:");

    const [getUrl] = fetchMock.mock.calls[1] as [string];
    expect(getUrl).toBe("/deployments/checkout-service");
  });

  it("remove() DELETEs /deployments/{name}", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpDeploymentsRepository();

    await repo.remove("checkout-service");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/deployments/checkout-service");
    expect(init.method).toBe("DELETE");
  });
});
