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
        errorRatePerSecond: 0.2,
        queueDepth: 0,
        cpuMillicoresUsed: 100,
        memoryBytesUsed: 1024,
      },
    },
  ],
  unplacedCount: 1,
  quotaViolating: false,
  limitRangeViolating: false,
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
      errorRatePerSecond: 0,
      queueDepth: 0,
      cpuMillicoresUsed: 0,
      memoryBytesUsed: 0,
      workerId: null,
    });
  });

  it("maps an instance observation's errorRatePerSecond through unchanged", async () => {
    stubFetchSequence([() => jsonResponse([RAW_DEPLOYMENT])]);

    const repo = new HttpDeploymentsRepository();
    const all = await repo.all(true);

    expect(all[0].instances[0].observation.errorRatePerSecond).toBe(0.2);
  });

  it("maps an instance observation with no workerId key to null", async () => {
    stubFetchSequence([() => jsonResponse([RAW_DEPLOYMENT])]);

    const repo = new HttpDeploymentsRepository();
    const all = await repo.all(true);

    expect(all[0].instances[0].observation.workerId).toBeNull();
  });

  it("maps an instance observation's real workerId through unchanged", async () => {
    const withWorkerId = {
      ...RAW_DEPLOYMENT,
      instances: [
        {
          ...RAW_DEPLOYMENT.instances[0],
          observation: { ...RAW_DEPLOYMENT.instances[0].observation, workerId: "worker-4821" },
        },
      ],
    };
    stubFetchSequence([() => jsonResponse([withWorkerId])]);

    const repo = new HttpDeploymentsRepository();
    const all = await repo.all(true);

    expect(all[0].instances[0].observation.workerId).toBe("worker-4821");
  });

  it("maps limitRangeViolating and its reason through unchanged", async () => {
    const violating = {
      ...RAW_DEPLOYMENT,
      limitRangeViolating: true,
      limitRangeViolationReason: "request memory 512Mi above maximum 256Mi",
    };
    stubFetchSequence([() => jsonResponse([violating])]);

    const repo = new HttpDeploymentsRepository();
    const all = await repo.all(true);

    expect(all[0].limitRangeViolating).toBe(true);
    expect(all[0].limitRangeViolationReason).toBe("request memory 512Mi above maximum 256Mi");
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

  it("remove() appends ?tenant=<id> when a tenantId is given", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpDeploymentsRepository();

    await repo.remove("checkout-service", "acme");

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/deployments/checkout-service?tenant=acme");
  });

  it("remove() omits ?tenant= when the tenantId is null or absent", async () => {
    const fetchMock = stubFetchSequence([() => okResponse(), () => okResponse()]);
    const repo = new HttpDeploymentsRepository();

    await repo.remove("checkout-service", null);
    await repo.remove("checkout-service");

    const [urlWithNull] = fetchMock.mock.calls[0] as [string];
    const [urlWithoutArg] = fetchMock.mock.calls[1] as [string];
    expect(urlWithNull).toBe("/deployments/checkout-service");
    expect(urlWithoutArg).toBe("/deployments/checkout-service");
  });

  it("fetchRevisions GETs /deployments/{name}/revisions and unwraps the envelope", async () => {
    const fetchMock = stubFetchSequence([
      () =>
        jsonResponse({
          revisions: [
            {
              revision: 2,
              createdAtEpochMilli: 2000,
              moduleId: { name: "checkout-service", version: "1.2.3" },
              artifactPath: "",
            },
          ],
        }),
    ]);
    const repo = new HttpDeploymentsRepository();

    const revisions = await repo.fetchRevisions("checkout-service", "acme");

    expect(revisions).toHaveLength(1);
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/deployments/checkout-service/revisions?tenant=acme");
  });

  it("rollback() POSTs {toRevision} and busts the cache", async () => {
    const fetchMock = stubFetchSequence([
      () =>
        jsonResponse({
          revision: 3,
          createdAtEpochMilli: 3000,
          rollbackOfRevision: 1,
          moduleId: { name: "checkout-service", version: "1.0.0" },
          artifactPath: "",
        }),
    ]);
    const repo = new HttpDeploymentsRepository();

    const rev = await repo.rollback("checkout-service", 1);

    expect(rev.rollbackOfRevision).toBe(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/deployments/checkout-service/rollback");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({ toRevision: 1 });
  });

  it("rollback() with no toRevision POSTs an empty body, letting the server default to the previous revision", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse(RAW_DEPLOYMENT)]);
    const repo = new HttpDeploymentsRepository();

    await repo.rollback("checkout-service");

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(init.body as string)).toEqual({});
  });
});
