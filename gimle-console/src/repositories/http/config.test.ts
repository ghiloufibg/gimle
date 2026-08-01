import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpConfigRepository } from "./config";
import { jsonResponse, okResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("HttpConfigRepository", () => {
  // Regression test for a real bug found during manual verification: ApiServer.java's
  // handleListConfig returns only {key, value, encrypted} per entry -- tenantId is implicit in the
  // URL, not repeated in the body -- but ConfigEntry.tenantId is non-optional. Every fetched entry
  // must have the requested tenantId stitched back in, not left undefined.
  it("stitches the requested tenantId onto every entry, which the wire response omits", async () => {
    stubFetchSequence([
      () => jsonResponse([{ key: "db.url", value: "postgres://x", encrypted: false }]),
    ]);
    const repo = new HttpConfigRepository();

    const page = await repo.fetchPage({ tenantId: "acme", cursor: null, pageSize: 10 });

    expect(page.items[0]).toEqual({
      tenantId: "acme",
      key: "db.url",
      value: "postgres://x",
      encrypted: false,
    });
  });

  it("fetchPage GETs /config/{tenantId} and paginates the full list client-side", async () => {
    // Deliberately uncached and unpaginated server-side (see the class's own comment): the whole
    // per-tenant list is small, so pagination happens by slicing it here, not via query params.
    const fetchMock = stubFetchSequence([
      () =>
        jsonResponse([
          { key: "a", value: "1", encrypted: false },
          { key: "b", value: "2", encrypted: false },
          { key: "c", value: "3", encrypted: false },
        ]),
    ]);
    const repo = new HttpConfigRepository();

    const page = await repo.fetchPage({ tenantId: "acme", cursor: null, pageSize: 2 });

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/config/acme");
    expect(page.items.map((e) => e.key)).toEqual(["a", "b"]);
    expect(page.nextCursor).toBe("2");
  });

  it("upsert PUTs {value, encrypted} to /config/{tenantId}/{key} and returns the entry as given", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpConfigRepository();
    const entry = { tenantId: "acme", key: "db.url", value: "postgres://y", encrypted: true };

    const result = await repo.upsert(entry);

    expect(result).toEqual(entry);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/config/acme/db.url");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body as string)).toEqual({ value: "postgres://y", encrypted: true });
  });

  it("remove() DELETEs /config/{tenantId}/{key}", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpConfigRepository();

    await repo.remove("acme", "db.url");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/config/acme/db.url");
    expect(init.method).toBe("DELETE");
  });
});
