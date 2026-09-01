import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpSealRepository } from "./seal";
import { jsonResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("HttpSealRepository", () => {
  it("fetchPublicKey GETs /seal/public-key", async () => {
    const fetchMock = stubFetchSequence([
      () => jsonResponse({ sealingKeyId: 3, publicKey: "MIIBIjAN", algorithm: "RSA-OAEP-SHA256" }),
    ]);
    const repo = new HttpSealRepository();

    const key = await repo.fetchPublicKey();

    expect(key).toEqual({
      sealingKeyId: 3,
      publicKey: "MIIBIjAN",
      algorithm: "RSA-OAEP-SHA256",
    });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/seal/public-key");
    expect(init.method).toBe("GET");
  });

  it("rotateKey POSTs an empty body and reads the new active id back", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse({ activeSealingKeyId: 4 })]);
    const repo = new HttpSealRepository();

    expect(await repo.rotateKey()).toEqual({ activeSealingKeyId: 4 });

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/seal/rotate-key");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({});
  });

  it("retireKey POSTs the key id and reads the retired id back", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse({ retiredKeyId: 2 })]);
    const repo = new HttpSealRepository();

    expect(await repo.retireKey(2)).toEqual({ retiredKeyId: 2 });

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/seal/retire-key");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({ keyId: 2 });
  });

  it("surfaces the server's own rejection of an unknown key id", async () => {
    stubFetchSequence([() => new Response("no sealing key with id 9", { status: 400 })]);
    const repo = new HttpSealRepository();

    await expect(repo.retireKey(9)).rejects.toThrow("no sealing key with id 9");
  });
});
