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

  it("fetchOne passes a raw spec.disruption block through unchanged", async () => {
    const withDisruption = {
      ...RAW_DEPLOYMENT,
      spec: { ...RAW_DEPLOYMENT.spec, disruption: { maxUnavailable: 2, maxSurge: 1 } },
    };
    stubFetchSequence([() => jsonResponse(withDisruption)]);
    const repo = new HttpDeploymentsRepository();

    const deployment = await repo.fetchOne("checkout-service");

    expect(deployment.spec.disruption).toEqual({ maxUnavailable: 2, maxSurge: 1 });
  });

  it("create() omits the disruption: block when none is given", async () => {
    const fetchMock = stubFetchSequence([() => okResponse(), () => jsonResponse(RAW_DEPLOYMENT)]);
    const repo = new HttpDeploymentsRepository();

    await repo.create({
      name: "checkout-service",
      moduleId: { name: "checkout-service", version: "1.2.3" },
      artifactPath: "s3://bucket/checkout-service-1.2.3.jar",
      replicas: 2,
      tenantId: null,
    });

    const [, putInit] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(putInit.body as string).not.toContain("disruption:");
  });

  it("create() omits the artifactPath: line entirely for a blank artifactPath (registry-only deploy)", async () => {
    const fetchMock = stubFetchSequence([() => okResponse(), () => jsonResponse(RAW_DEPLOYMENT)]);
    const repo = new HttpDeploymentsRepository();

    await repo.create({
      name: "checkout-service",
      moduleId: { name: "checkout-service", version: "1.2.3" },
      artifactPath: "",
      replicas: 2,
      tenantId: null,
    });

    const [, putInit] = fetchMock.mock.calls[0] as [string, RequestInit];
    // A *present but blank* artifactPath is a manifest error server-side
    // (ManifestFields.optionalArtifactPath) -- the key must be absent entirely so the control
    // plane resolves the module coordinate from the Andvari registry instead.
    expect(putInit.body as string).not.toContain("artifactPath:");
  });

  it("create() PUTs a disruption: block in the YAML manifest when disruption is set", async () => {
    const fetchMock = stubFetchSequence([() => okResponse(), () => jsonResponse(RAW_DEPLOYMENT)]);
    const repo = new HttpDeploymentsRepository();

    await repo.create({
      name: "checkout-service",
      moduleId: { name: "checkout-service", version: "1.2.3" },
      artifactPath: "s3://bucket/checkout-service-1.2.3.jar",
      replicas: 2,
      tenantId: null,
      disruption: { maxUnavailable: 2, maxSurge: 1 },
    });

    const [, putInit] = fetchMock.mock.calls[0] as [string, RequestInit];
    const yaml = putInit.body as string;
    expect(yaml).toContain("disruption:");
    expect(yaml).toContain("  maxUnavailable: 2");
    expect(yaml).toContain("  maxSurge: 1");
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
