import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpRolesRepository } from "./roles";
import { jsonResponse, okResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("HttpRolesRepository", () => {
  it("fetchAll GETs /roles", async () => {
    const fetchMock = stubFetchSequence([
      () => jsonResponse([{ name: "readonly", permissions: [] }]),
    ]);
    const repo = new HttpRolesRepository();

    const roles = await repo.fetchAll();

    expect(roles).toHaveLength(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/roles");
    expect(init.method).toBe("GET");
  });

  it("fetchOne GETs /roles/{name}, url-encoding the segment", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse({ name: "a/b", permissions: [] })]);
    const repo = new HttpRolesRepository();

    await repo.fetchOne("a/b");

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/roles/a%2Fb");
  });

  it("save PUTs {permissions} to /roles/{name}", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpRolesRepository();

    await repo.save("readonly", [{ resource: "LOGS", verb: "READ" }]);

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/roles/readonly");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body as string)).toEqual({
      permissions: [{ resource: "LOGS", verb: "READ" }],
    });
  });

  it("remove DELETEs /roles/{name}", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpRolesRepository();

    await repo.remove("readonly");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/roles/readonly");
    expect(init.method).toBe("DELETE");
  });
});
