import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpSecretsRepository } from "./secrets";
import { jsonResponse, okResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("HttpSecretsRepository", () => {
  it("fetchPage GETs /secrets/{tenantId} and stitches the requested tenantId onto every entry", async () => {
    const fetchMock = stubFetchSequence([
      () =>
        jsonResponse({
          secrets: [{ key: "db.password", latestVersion: 2, deleted: false }],
        }),
    ]);
    const repo = new HttpSecretsRepository();

    const page = await repo.fetchPage({ tenantId: "acme", cursor: null, pageSize: 10 });

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/secrets/acme");
    expect(page.items[0]).toEqual({
      tenantId: "acme",
      key: "db.password",
      latestVersion: 2,
      deleted: false,
    });
  });

  it("fetchPage paginates the full list client-side, same as HttpConfigRepository", async () => {
    stubFetchSequence([
      () =>
        jsonResponse({
          secrets: [
            { key: "a", latestVersion: 1, deleted: false },
            { key: "b", latestVersion: 1, deleted: false },
            { key: "c", latestVersion: 1, deleted: false },
          ],
        }),
    ]);
    const repo = new HttpSecretsRepository();

    const page = await repo.fetchPage({ tenantId: "acme", cursor: null, pageSize: 2 });

    expect(page.items.map((e) => e.key)).toEqual(["a", "b"]);
    expect(page.nextCursor).toBe("2");
  });

  it("fetchValue GETs /secrets/{tenantId}/{key}, decodes base64, and keeps the version record", async () => {
    const fetchMock = stubFetchSequence([
      () =>
        jsonResponse({
          value: btoa("hunter2"),
          version: 3,
          type: "opaque",
          author: "alice",
          writtenAtEpochMilli: 1700,
        }),
    ]);
    const repo = new HttpSecretsRepository();

    const result = await repo.fetchValue("acme", "db.password");

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/secrets/acme/db.password");
    expect(result).toEqual({
      tenantId: "acme",
      key: "db.password",
      version: 3,
      value: "hunter2",
      type: "opaque",
      author: "alice",
      writtenAtEpochMilli: 1700,
    });
  });

  it("fetchValue with an explicit version appends ?version=N", async () => {
    const fetchMock = stubFetchSequence([
      () => jsonResponse({ value: btoa("old-value"), version: 1 }),
    ]);
    const repo = new HttpSecretsRepository();

    await repo.fetchValue("acme", "db.password", 1);

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/secrets/acme/db.password?version=1");
  });

  it("fetchVersions GETs /secrets/{tenantId}/{key}/versions with each version's author and type", async () => {
    const fetchMock = stubFetchSequence([
      () =>
        jsonResponse({
          versions: [
            { version: 1, author: "alice", writtenAtEpochMilli: 100, type: "opaque" },
            { version: 2, author: "bob", writtenAtEpochMilli: 200, type: "pem-certificate" },
          ],
        }),
    ]);
    const repo = new HttpSecretsRepository();

    const versions = await repo.fetchVersions("acme", "db.password");

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/secrets/acme/db.password/versions");
    expect(versions.map((v) => [v.version, v.author, v.type])).toEqual([
      [1, "alice", "opaque"],
      [2, "bob", "pem-certificate"],
    ]);
  });

  it("upsert PUTs a base64-encoded value and returns metadata reflecting the returned version", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse({ version: 2 })]);
    const repo = new HttpSecretsRepository();

    const result = await repo.upsert("acme", "db.password", "hunter2");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/secrets/acme/db.password");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body as string)).toEqual({ value: btoa("hunter2"), type: "opaque" });
    expect(result).toEqual({
      tenantId: "acme",
      key: "db.password",
      latestVersion: 2,
      deleted: false,
    });
  });

  it("remove() DELETEs /secrets/{tenantId}/{key} with no query string by default", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpSecretsRepository();

    await repo.remove("acme", "db.password", false);

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/secrets/acme/db.password");
    expect(init.method).toBe("DELETE");
  });

  it("remove() with destroy=true appends ?destroy=true", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpSecretsRepository();

    await repo.remove("acme", "db.password", true);

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/secrets/acme/db.password?destroy=true");
  });

  it("rotateKey POSTs /secrets/rotate-key and returns the new active key id", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse({ activeKeyId: 4 })]);
    const repo = new HttpSecretsRepository();

    const activeKeyId = await repo.rotateKey();

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/secrets/rotate-key");
    expect(init.method).toBe("POST");
    expect(activeKeyId).toBe(4);
  });

  it("upsert sends an explicitly declared type so the server validates the value's shape", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse({ version: 1 })]);
    const repo = new HttpSecretsRepository();

    await repo.upsert("acme", "tls.cert", "-----BEGIN CERTIFICATE-----", "pem-certificate");

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(init.body as string).type).toBe("pem-certificate");
  });
});
