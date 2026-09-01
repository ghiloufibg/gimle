import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpVolumesRepository } from "./volumes";
import { jsonResponse, okResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("HttpVolumesRepository", () => {
  it("fetchAll GETs /volumes and keeps the envelope", async () => {
    const fetchMock = stubFetchSequence([
      () =>
        jsonResponse({
          volumes: [
            {
              tenantId: null,
              statefulSet: "ledger",
              instanceIndex: 3,
              volumeName: "data",
              usedBytes: 12,
              path: "/var/lib/gimle/volumes/ledger/3/data",
              inUse: false,
              nodeId: "node-b",
              attached: false,
            },
          ],
          unreachableNodes: ["node-c"],
        }),
    ]);
    const repo = new HttpVolumesRepository();

    const listing = await repo.fetchAll();

    expect(listing.volumes).toHaveLength(1);
    expect(listing.unreachableNodes).toEqual(["node-c"]);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/volumes");
    expect(init.method).toBe("GET");
  });

  it("fetchAll accepts a listing the control plane sent no unreachableNodes for", async () => {
    stubFetchSequence([() => jsonResponse({ volumes: [] })]);
    const repo = new HttpVolumesRepository();

    const listing = await repo.fetchAll();

    expect(listing.unreachableNodes).toBeUndefined();
  });

  it("destroy DELETEs the node/statefulSet/index triple", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpVolumesRepository();

    await repo.destroy("node-b", "ledger", 3);

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/volumes/node-b/ledger/3");
    expect(init.method).toBe("DELETE");
  });

  it("destroy appends ?tenant= for a tenanted volume and url-encodes each segment", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpVolumesRepository();

    await repo.destroy("node/b", "a b", 0, "acme corp");

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/volumes/node%2Fb/a%20b/0?tenant=acme%20corp");
  });
});
