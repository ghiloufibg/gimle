import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpConfigMapsRepository } from "./configmaps";
import { ConfigMapConflict } from "@/repositories/configmaps";
import { jsonResponse, okResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("HttpConfigMapsRepository", () => {
  it("fetchNames GETs /configmaps/{tenantId} and returns the bare name array", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse(["app-config", "feature-flags"])]);
    const repo = new HttpConfigMapsRepository();

    const names = await repo.fetchNames("acme");

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/configmaps/acme");
    expect(names).toEqual(["app-config", "feature-flags"]);
  });

  it("fetchOne GETs /configmaps/{tenantId}/{name} and stitches tenantId onto the result", async () => {
    const fetchMock = stubFetchSequence([
      () => jsonResponse({ name: "app-config", version: 3, data: { "log.level": "DEBUG" } }),
    ]);
    const repo = new HttpConfigMapsRepository();

    const result = await repo.fetchOne("acme", "app-config");

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/configmaps/acme/app-config");
    expect(result).toEqual({
      tenantId: "acme",
      name: "app-config",
      version: 3,
      data: { "log.level": "DEBUG" },
    });
  });

  it("upsert PUTs {data, expectedVersion} and returns the echoed data at the new version", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse({ version: 4 })]);
    const repo = new HttpConfigMapsRepository();

    const result = await repo.upsert("acme", "app-config", { a: "1" }, 3);

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/configmaps/acme/app-config");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body as string)).toEqual({ data: { a: "1" }, expectedVersion: 3 });
    expect(result).toEqual({ tenantId: "acme", name: "app-config", version: 4, data: { a: "1" } });
  });

  it("upsert omits expectedVersion from the body when not given (unconditional overwrite)", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse({ version: 1 })]);
    const repo = new HttpConfigMapsRepository();

    await repo.upsert("acme", "app-config", { a: "1" });

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(init.body as string)).toEqual({ data: { a: "1" } });
  });

  it("upsert on a 409 throws ConfigMapConflict carrying the server's current snapshot", async () => {
    stubFetchSequence([
      () => jsonResponse({ currentVersion: 5, currentData: { a: "server-value" } }, 409),
    ]);
    const repo = new HttpConfigMapsRepository();

    const error = await repo.upsert("acme", "app-config", { a: "my-value" }, 3).catch((e) => e);

    expect(error).toBeInstanceOf(ConfigMapConflict);
    expect((error as ConfigMapConflict).currentVersion).toBe(5);
    expect((error as ConfigMapConflict).currentData).toEqual({ a: "server-value" });
  });

  it("remove DELETEs /configmaps/{tenantId}/{name}", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpConfigMapsRepository();

    await repo.remove("acme", "app-config");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/configmaps/acme/app-config");
    expect(init.method).toBe("DELETE");
  });
});
