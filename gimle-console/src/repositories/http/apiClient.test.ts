import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, requestJson, requestOk } from "./apiClient";
import { jsonResponse, okResponse, stubFetchSequence, textResponse } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("requestJson", () => {
  it("returns the parsed JSON body on a 2xx response", async () => {
    stubFetchSequence([() => jsonResponse({ hello: "world" })]);
    const body = await requestJson<{ hello: string }>("GET", "/deployments");
    expect(body).toEqual({ hello: "world" });
  });

  it("throws ApiError with the status and body text on a non-2xx response", async () => {
    stubFetchSequence([() => textResponse("nope", 404)]);
    await expect(requestJson("GET", "/deployments/missing")).rejects.toMatchObject(
      new ApiError(404, "nope"),
    );
  });

  it("retries against leaderApiAddress on a 307 not-leader redirect", async () => {
    const fetchMock = stubFetchSequence([
      () => jsonResponse({ error: "not-leader", leaderApiAddress: "10.0.0.5:8080" }, 307),
      () => jsonResponse({ hello: "from-leader" }),
    ]);

    const body = await requestJson<{ hello: string }>("GET", "/deployments");

    expect(body).toEqual({ hello: "from-leader" });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    const secondCallUrl = fetchMock.mock.calls[1]?.[0] as string;
    expect(secondCallUrl).toBe("http://10.0.0.5:8080/deployments");
  });

  it("does not retry forever: a 307 with no leaderApiAddress surfaces as an error", async () => {
    // A cluster genuinely between elections -- no address to retry against, so it must not loop.
    stubFetchSequence([() => jsonResponse({ error: "not-leader", leaderApiAddress: null }, 307)]);
    await expect(requestJson("GET", "/deployments")).rejects.toThrow();
  });
});

describe("requestOk", () => {
  it("resolves without throwing on a 2xx response", async () => {
    stubFetchSequence([() => okResponse()]);
    await expect(requestOk("DELETE", "/deployments/foo")).resolves.toBeUndefined();
  });

  it("sends a JSON body with the right content type when a body is given", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    await requestOk("PUT", "/tenants/acme", { quota: { maxInstances: 5 } });

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/tenants/acme");
    expect(init.method).toBe("PUT");
    expect(init.headers).toMatchObject({ "Content-Type": "application/json" });
    expect(init.body).toBe(JSON.stringify({ quota: { maxInstances: 5 } }));
  });

  it("throws ApiError on a non-2xx response", async () => {
    stubFetchSequence([() => textResponse("forbidden", 403)]);
    await expect(requestOk("DELETE", "/tenants/acme")).rejects.toMatchObject(
      new ApiError(403, "forbidden"),
    );
  });
});
