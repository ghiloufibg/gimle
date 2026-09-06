import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  SESSION_EXPIRED_MESSAGE,
  SessionExpiredError,
  isThrottled,
  requestJson,
  requestOk,
  requestOkWithWarning,
  setUnauthorizedHandler,
  throttleDelayMs,
} from "./apiClient";
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

describe("requestOkWithWarning", () => {
  it("resolves to null when the response carries no X-Gimle-Warning header", async () => {
    stubFetchSequence([() => okResponse()]);
    await expect(requestOkWithWarning("POST", "/services", {})).resolves.toBeNull();
  });

  it("resolves to the header's value when the control plane attaches one to a 2xx response", async () => {
    stubFetchSequence([
      () => new Response("ok", { status: 200, headers: { "X-Gimle-Warning": "overlap" } }),
    ]);
    await expect(requestOkWithWarning("POST", "/services", {})).resolves.toBe("overlap");
  });

  it("still throws ApiError on a non-2xx response, warning header or not", async () => {
    stubFetchSequence([() => textResponse("forbidden", 403)]);
    await expect(requestOkWithWarning("POST", "/services", {})).rejects.toMatchObject(
      new ApiError(403, "forbidden"),
    );
  });
});

describe("401 handling", () => {
  it("throws SessionExpiredError, whose message never shows the raw status line", async () => {
    stubFetchSequence([() => textResponse("not authenticated", 401)]);

    const failure = await requestJson("GET", "/deployments").catch((e: unknown) => e);

    expect(failure).toBeInstanceOf(SessionExpiredError);
    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as SessionExpiredError).status).toBe(401);
    expect((failure as SessionExpiredError).message).toBe(SESSION_EXPIRED_MESSAGE);
    expect((failure as SessionExpiredError).message).not.toMatch(/401/);
  });

  it("notifies the registered unauthorized handler exactly once, on write calls too", async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    try {
      stubFetchSequence([() => textResponse("not authenticated", 401)]);
      await expect(requestOk("DELETE", "/tenants/acme")).rejects.toBeInstanceOf(
        SessionExpiredError,
      );
      expect(handler).toHaveBeenCalledTimes(1);
    } finally {
      setUnauthorizedHandler(() => {});
    }
  });
});

/**
 * The control plane refuses a caller it is currently throttling with a 429 plus a `Retry-After`
 * header -- its per-address request rate limiter, which a console page-load's own burst of reads
 * can trip, and its admission-control guard both answer that way, before the request's handler
 * runs. "Ask again shortly" must never reach a call site as an answer: read as one, a throttled
 * /auth/session says "nobody is signed in" and a throttled /kinddefinitions says "this cluster has
 * no custom kinds", neither of which the control plane said.
 */
describe("429 throttling", () => {
  it("retries a throttled read and returns the body the retry answers with", async () => {
    vi.useFakeTimers();
    try {
      const fetchMock = stubFetchSequence([
        () => textResponse("control plane at capacity; retry shortly", 429),
        () => jsonResponse([{ kindName: "custom.Greeting" }]),
      ]);

      const pending = requestJson<unknown[]>("GET", "/kinddefinitions");
      await vi.advanceTimersByTimeAsync(250);

      await expect(pending).resolves.toEqual([{ kindName: "custom.Greeting" }]);
      expect(fetchMock).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it("waits out the Retry-After the response carries before asking again", async () => {
    vi.useFakeTimers();
    try {
      const fetchMock = stubFetchSequence([
        () => new Response("too many attempts", { status: 429, headers: { "Retry-After": "2" } }),
        () => jsonResponse({ username: "anonymous", anonymous: true }),
      ]);

      const pending = requestJson("GET", "/auth/session");
      await vi.advanceTimersByTimeAsync(1_999);
      expect(fetchMock).toHaveBeenCalledTimes(1);
      await vi.advanceTimersByTimeAsync(1);

      await expect(pending).resolves.toEqual({ username: "anonymous", anonymous: true });
      expect(fetchMock).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it("gives up after a bounded number of retries rather than retrying forever", async () => {
    vi.useFakeTimers();
    try {
      const fetchMock = stubFetchSequence([
        () => textResponse("at capacity", 429),
        () => textResponse("at capacity", 429),
        () => textResponse("at capacity", 429),
        () => textResponse("at capacity", 429),
      ]);

      const pending = requestJson("GET", "/kinddefinitions").catch((e: unknown) => e);
      await vi.advanceTimersByTimeAsync(10_000);

      const failure = await pending;
      expect(isThrottled(failure)).toBe(true);
      expect(fetchMock).toHaveBeenCalledTimes(4);
    } finally {
      vi.useRealTimers();
    }
  });

  it("surfaces a refusal asking for a long wait instead of sleeping through it", async () => {
    // A login lockout, not a capacity burst: the operator is meant to be told, not left spinning.
    const fetchMock = stubFetchSequence([
      () => new Response("too many attempts", { status: 429, headers: { "Retry-After": "60" } }),
    ]);

    await expect(requestJson("GET", "/auth/session")).rejects.toMatchObject(
      new ApiError(429, "too many attempts"),
    );
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe("throttleDelayMs", () => {
  it("backs off exponentially when the response carries no Retry-After", () => {
    expect(throttleDelayMs(null, 0)).toBe(250);
    expect(throttleDelayMs(null, 1)).toBe(500);
    expect(throttleDelayMs(null, 2)).toBe(1_000);
  });

  it("honours a delta-seconds Retry-After over its own backoff", () => {
    expect(throttleDelayMs("2", 0)).toBe(2_000);
  });

  it("honours an HTTP-date Retry-After, which the header is also allowed to carry", () => {
    const delay = throttleDelayMs(new Date(Date.now() + 2_000).toUTCString(), 0);
    expect(delay).toBeGreaterThan(1_000);
    expect(delay).toBeLessThanOrEqual(2_000);
  });

  it("never waits less than this attempt's own backoff, whatever the header asks for", () => {
    expect(throttleDelayMs("0", 2)).toBe(1_000);
  });

  it("answers null -- do not retry -- for a wait too long to sit through", () => {
    expect(throttleDelayMs("60", 0)).toBeNull();
  });

  it("falls back to backoff for a Retry-After it cannot read", () => {
    expect(throttleDelayMs("soon", 0)).toBe(250);
    expect(throttleDelayMs("", 0)).toBe(250);
  });
});
