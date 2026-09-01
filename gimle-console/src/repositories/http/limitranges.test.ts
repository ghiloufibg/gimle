import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpLimitRangesRepository } from "./limitranges";
import { jsonResponse, okResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("HttpLimitRangesRepository", () => {
  it("fetchAll GETs /limitranges", async () => {
    const fetchMock = stubFetchSequence([
      () => jsonResponse([{ tenantId: "acme", maxRequest: { memory: "2Gi", cpu: "2000m" } }]),
    ]);
    const repo = new HttpLimitRangesRepository();

    const ranges = await repo.fetchAll();

    expect(ranges).toHaveLength(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/limitranges");
    expect(init.method).toBe("GET");
  });

  it("fetchAll accepts a range the control plane declared no bounds for", async () => {
    stubFetchSequence([() => jsonResponse([{ tenantId: "acme" }])]);
    const repo = new HttpLimitRangesRepository();

    const [range] = await repo.fetchAll();

    expect(range.minRequest).toBeUndefined();
    expect(range.maxLimit).toBeUndefined();
  });

  it("fetchOne GETs /limitranges/{tenantId}, url-encoding the segment", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse({ tenantId: "a/b" })]);
    const repo = new HttpLimitRangesRepository();

    await repo.fetchOne("a/b");

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/limitranges/a%2Fb");
  });

  it("save PUTs only the bounds, with the tenant id left in the path", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpLimitRangesRepository();

    await repo.save({
      tenantId: "acme",
      minRequest: { memory: "64Mi", cpu: "50m" },
      maxLimit: { memory: "4Gi", cpu: "4000m" },
    });

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/limitranges/acme");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body as string)).toEqual({
      minRequest: { memory: "64Mi", cpu: "50m" },
      maxLimit: { memory: "4Gi", cpu: "4000m" },
    });
  });

  it("remove DELETEs /limitranges/{tenantId}", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpLimitRangesRepository();

    await repo.remove("acme");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/limitranges/acme");
    expect(init.method).toBe("DELETE");
  });
});
