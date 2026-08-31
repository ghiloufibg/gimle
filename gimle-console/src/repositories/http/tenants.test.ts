import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpTenantsRepository } from "./tenants";
import { jsonResponse, okResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

const RAW_TENANT = {
  id: "acme",
  quota: { maxMemoryBytes: 1000, maxCpuMillicores: 2000, maxInstances: 10 },
  usage: { memoryBytes: 100, cpuMillicores: 200, instances: 1 },
  quotaViolating: false,
};

describe("HttpTenantsRepository", () => {
  it("fetchPage maps the raw /tenants array", async () => {
    stubFetchSequence([() => jsonResponse([RAW_TENANT])]);
    const repo = new HttpTenantsRepository();

    const page = await repo.fetchPage({ cursor: null, pageSize: 10 });

    expect(page.items).toEqual([RAW_TENANT]);
  });

  it("fetchOne throws for an unknown id even after a forced refresh", async () => {
    stubFetchSequence([() => jsonResponse([RAW_TENANT]), () => jsonResponse([RAW_TENANT])]);
    const repo = new HttpTenantsRepository();

    await expect(repo.fetchOne("no-such-tenant")).rejects.toThrow();
  });

  it("updateQuota PUTs the new quota, then re-fetches so a changed quotaViolating is picked up", async () => {
    // ApiServer.java's handlePutTenant responds "ok", not the tenant JSON, and a changed quota can
    // flip quotaViolating even though usage is unchanged -- so updateQuota must re-fetch rather
    // than build its return value client-side.
    const newQuota = { maxMemoryBytes: 1, maxCpuMillicores: 2, maxInstances: 3 };
    const fetchMock = stubFetchSequence([
      () => okResponse(),
      () => jsonResponse([{ ...RAW_TENANT, quota: newQuota, quotaViolating: true }]),
    ]);
    const repo = new HttpTenantsRepository();

    const updated = await repo.updateQuota("acme", newQuota);

    expect(updated.quota).toEqual(newQuota);
    expect(updated.quotaViolating).toBe(true);
    const [putUrl, putInit] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(putUrl).toBe("/tenants/acme");
    expect(putInit.method).toBe("PUT");
    expect(JSON.parse(putInit.body as string)).toEqual({ quota: newQuota });
  });

  it("remove() DELETEs /tenants/{id}", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpTenantsRepository();

    await repo.remove("acme");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/tenants/acme");
    expect(init.method).toBe("DELETE");
  });
});
