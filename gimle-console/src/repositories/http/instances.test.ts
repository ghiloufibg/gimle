import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpDeploymentsRepository } from "./deployments";
import { HttpInstancesRepository } from "./instances";
import { jsonResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

const RAW_DEPLOYMENTS = [
  {
    spec: {
      name: "checkout-service",
      moduleId: { name: "checkout-service", version: "1.2.3" },
      artifactPath: "s3://bucket/checkout-service.jar",
      replicas: 1,
      tenantId: "acme",
    },
    instances: [
      {
        instanceIndex: 0,
        nodeId: "node-1",
        observation: {
          lifecycleState: "ACTIVE",
          alive: true,
          ready: true,
          requestRatePerSecond: 3,
          queueDepth: 0,
          cpuMillicoresUsed: 50,
          memoryBytesUsed: 2048,
        },
      },
    ],
    unplacedCount: 0,
    quotaViolating: false,
    limitRangeViolating: false,
  },
];

describe("HttpInstancesRepository", () => {
  it("flattens deployments' instances into rows carrying the parent spec's fields", async () => {
    stubFetchSequence([() => jsonResponse(RAW_DEPLOYMENTS)]);
    const deploymentsRepo = new HttpDeploymentsRepository();
    const repo = new HttpInstancesRepository(deploymentsRepo);

    const page = await repo.fetchPage({ cursor: null, pageSize: 100 });

    expect(page.items).toHaveLength(1);
    expect(page.items[0]).toMatchObject({
      deploymentName: "checkout-service",
      instanceIndex: 0,
      tenantId: "acme",
      nodeId: "node-1",
      lifecycleState: "ACTIVE",
    });
  });

  it("fetchOne throws when the instance isn't found even after a forced cache refresh", async () => {
    // fetchOne tries the cached snapshot first, then one forced refresh -- both need a response
    // queued, or the second fetch call has nothing to resolve against.
    stubFetchSequence([() => jsonResponse(RAW_DEPLOYMENTS), () => jsonResponse(RAW_DEPLOYMENTS)]);
    const deploymentsRepo = new HttpDeploymentsRepository();
    const repo = new HttpInstancesRepository(deploymentsRepo);

    await expect(repo.fetchOne("checkout-service", 99)).rejects.toThrow();
  });

  it("fetchOne resolves an instance found on the first lookup, no forced-refresh fallback needed", async () => {
    stubFetchSequence([() => jsonResponse(RAW_DEPLOYMENTS)]);
    const deploymentsRepo = new HttpDeploymentsRepository();
    const repo = new HttpInstancesRepository(deploymentsRepo);

    const found = await repo.fetchOne("checkout-service", 0);
    expect(found.nodeId).toBe("node-1");
  });
});
