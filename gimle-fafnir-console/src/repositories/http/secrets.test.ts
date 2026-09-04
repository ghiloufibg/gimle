import { afterEach, describe, expect, it, vi } from "vitest";

import { HttpSecretsRepository } from "./secrets";
import { jsonResponse, okResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("HttpSecretsRepository", () => {
  it("list fetches /secrets/{tenantId} and attaches the tenantId to each row", async () => {
    const fetch = stubFetchSequence([
      () => jsonResponse({ secrets: [{ key: "db/password", latestVersion: 2, deleted: false }] }),
    ]);

    const secrets = await new HttpSecretsRepository().list("asgard");

    expect(secrets).toEqual([
      { tenantId: "asgard", key: "db/password", latestVersion: 2, deleted: false },
    ]);
    expect(fetch.mock.calls[0]?.[0]).toBe("/secrets/asgard");
  });

  it("read decodes the base64 value returned by the API", async () => {
    stubFetchSequence([() => jsonResponse({ value: btoa("hunter2"), version: 3 })]);

    const secret = await new HttpSecretsRepository().read("asgard", "db/password");

    expect(secret).toEqual({
      tenantId: "asgard",
      key: "db/password",
      version: 3,
      value: "hunter2",
    });
  });

  it("read with an explicit version appends ?version=N", async () => {
    const fetch = stubFetchSequence([() => jsonResponse({ value: btoa("old"), version: 1 })]);

    await new HttpSecretsRepository().read("asgard", "db/password", 1);

    expect(fetch.mock.calls[0]?.[0]).toBe("/secrets/asgard/db%2Fpassword?version=1");
  });

  it("versions returns the API's full per-version objects rather than bare numbers", async () => {
    stubFetchSequence([
      () =>
        jsonResponse({
          versions: [
            { version: 1, author: "anonymous", writtenAtEpochMilli: 1000 },
            { version: 2, author: "anonymous", writtenAtEpochMilli: 2000 },
          ],
        }),
    ]);

    const versions = await new HttpSecretsRepository().versions("asgard", "db/password");

    expect(versions).toEqual([
      { version: 1, author: "anonymous", writtenAtEpochMilli: 1000 },
      { version: 2, author: "anonymous", writtenAtEpochMilli: 2000 },
    ]);
  });

  it("upsert base64-encodes the value before sending it", async () => {
    const fetch = stubFetchSequence([() => jsonResponse({ version: 4 })]);

    const version = await new HttpSecretsRepository().upsert("asgard", "db/password", "s3cr3t");

    expect(version).toBe(4);
    const init = fetch.mock.calls[0]?.[1] as RequestInit;
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body as string)).toEqual({ value: btoa("s3cr3t") });
  });

  it("remove without destroy omits the query string", async () => {
    const fetch = stubFetchSequence([() => okResponse()]);

    await new HttpSecretsRepository().remove("asgard", "db/password");

    expect(fetch.mock.calls[0]?.[0]).toBe("/secrets/asgard/db%2Fpassword");
  });

  it("remove with destroy=true appends the query string", async () => {
    const fetch = stubFetchSequence([() => okResponse()]);

    await new HttpSecretsRepository().remove("asgard", "db/password", true);

    expect(fetch.mock.calls[0]?.[0]).toBe("/secrets/asgard/db%2Fpassword?destroy=true");
  });

  it("rotateKey posts to /secrets/rotate-key and returns the new active key id", async () => {
    const fetch = stubFetchSequence([() => jsonResponse({ activeKeyId: 9 })]);

    const activeKeyId = await new HttpSecretsRepository().rotateKey();

    expect(activeKeyId).toBe(9);
    expect(fetch.mock.calls[0]?.[0]).toBe("/secrets/rotate-key");
    expect((fetch.mock.calls[0]?.[1] as RequestInit).method).toBe("POST");
  });
});
