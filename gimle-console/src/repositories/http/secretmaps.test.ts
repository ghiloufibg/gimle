import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpSecretMapsRepository } from "./secretmaps";
import { jsonResponse, okResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("HttpSecretMapsRepository", () => {
  it("fetchNames GETs /secretmaps/{tenantId} and unwraps the names array", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse({ names: ["db-creds", "api-keys"] })]);
    const repo = new HttpSecretMapsRepository();

    const names = await repo.fetchNames("acme");

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/secretmaps/acme");
    expect(names).toEqual(["db-creds", "api-keys"]);
  });

  it("fetchOne GETs /secretmaps/{tenantId}/{name} and stitches tenantId onto the result", async () => {
    const fetchMock = stubFetchSequence([
      () =>
        jsonResponse({
          name: "db-creds",
          keys: [{ key: "username", latestVersion: 1, deleted: false }],
        }),
    ]);
    const repo = new HttpSecretMapsRepository();

    const result = await repo.fetchOne("acme", "db-creds");

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/secretmaps/acme/db-creds");
    expect(result).toEqual({
      tenantId: "acme",
      name: "db-creds",
      keys: [{ key: "username", latestVersion: 1, deleted: false }],
    });
  });

  it("set base64-encodes every value and PUTs {data}, returning the per-key results verbatim", async () => {
    const fetchMock = stubFetchSequence([
      () => jsonResponse({ results: [{ key: "username", version: 1 }] }),
    ]);
    const repo = new HttpSecretMapsRepository();

    const results = await repo.set("acme", "db-creds", { username: "admin" });

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/secretmaps/acme/db-creds");
    expect(init.method).toBe("PUT");
    const body = JSON.parse(init.body as string) as { data: Record<string, string> };
    expect(atob(body.data.username)).toBe("admin");
    expect(results).toEqual([{ key: "username", version: 1 }]);
  });

  it("remove DELETEs /secretmaps/{tenantId}/{name} with no query string when destroy is omitted", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpSecretMapsRepository();

    await repo.remove("acme", "db-creds");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/secretmaps/acme/db-creds");
    expect(init.method).toBe("DELETE");
  });

  it("remove appends ?destroy=true when destroy is requested", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpSecretMapsRepository();

    await repo.remove("acme", "db-creds", true);

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/secretmaps/acme/db-creds?destroy=true");
  });
});
