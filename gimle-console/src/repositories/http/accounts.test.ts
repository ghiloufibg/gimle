import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpAccountsRepository } from "./accounts";
import { jsonResponse, okResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("HttpAccountsRepository", () => {
  it("fetchAll GETs /accounts", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse([{ username: "admin" }])]);
    const repo = new HttpAccountsRepository();

    const items = await repo.fetchAll();

    expect(items).toEqual([{ username: "admin" }]);
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/accounts");
  });

  it("savePassword PUTs only {password} to /accounts/{username}", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpAccountsRepository();

    await repo.savePassword("admin", "hunter2");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/accounts/admin");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body as string)).toEqual({ password: "hunter2" });
  });

  it("remove DELETEs /accounts/{username}", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpAccountsRepository();

    await repo.remove("admin");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/accounts/admin");
    expect(init.method).toBe("DELETE");
  });
});
