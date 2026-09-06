import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpAuthRepository } from "./auth";
import { ApiError } from "./apiClient";
import { jsonResponse, okResponse, stubFetchSequence, textResponse } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

const PRINCIPAL = { username: "admin", groups: ["gimle:operators"] };

describe("HttpAuthRepository", () => {
  it("login POSTs credentials and returns the resulting Principal", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse(PRINCIPAL)]);
    const repo = new HttpAuthRepository();

    const principal = await repo.login("admin", "correct-password");

    expect(principal).toEqual(PRINCIPAL);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/auth/login");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({
      username: "admin",
      password: "correct-password",
    });
  });

  it("login rejects with a generic message on a 401, not the raw ApiError", async () => {
    stubFetchSequence([() => textResponse("invalid username or password", 401)]);
    const repo = new HttpAuthRepository();

    await expect(repo.login("admin", "wrong")).rejects.toThrow("invalid username or password");
  });

  it("logout POSTs to /auth/logout", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpAuthRepository();

    await repo.logout();

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/auth/logout");
    expect(init.method).toBe("POST");
  });

  it("session returns the Principal when a valid session cookie is presented", async () => {
    stubFetchSequence([() => jsonResponse(PRINCIPAL)]);
    const repo = new HttpAuthRepository();

    await expect(repo.session()).resolves.toEqual(PRINCIPAL);
  });

  it("session returns null, not a thrown error, when there is no valid session", async () => {
    stubFetchSequence([() => textResponse("not authenticated", 401)]);
    const repo = new HttpAuthRepository();

    await expect(repo.session()).resolves.toBeNull();
  });

  it("session rides out a throttled probe rather than reading it as nobody being signed in", async () => {
    // Plaintext mode answers this probe with a synthetic anonymous principal, so a 429 read as
    // "no session" would send an operator who has no credentials at all to the sign-in screen.
    vi.useFakeTimers();
    try {
      const fetchMock = stubFetchSequence([
        () =>
          new Response("control plane at capacity; retry shortly", {
            status: 429,
            headers: { "Retry-After": "1" },
          }),
        () => jsonResponse({ username: "anonymous", groups: [], anonymous: true }),
      ]);
      const repo = new HttpAuthRepository();

      const pending = repo.session();
      await vi.advanceTimersByTimeAsync(1_000);

      await expect(pending).resolves.toEqual({
        username: "anonymous",
        groups: [],
        anonymous: true,
      });
      expect(fetchMock).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it("session propagates a throttle it could not outlast instead of answering null", async () => {
    vi.useFakeTimers();
    try {
      stubFetchSequence([
        () => textResponse("at capacity", 429),
        () => textResponse("at capacity", 429),
        () => textResponse("at capacity", 429),
        () => textResponse("at capacity", 429),
      ]);
      const repo = new HttpAuthRepository();

      const pending = repo.session().catch((e: unknown) => e);
      await vi.advanceTimersByTimeAsync(10_000);

      expect(await pending).toMatchObject(new ApiError(429, "at capacity"));
    } finally {
      vi.useRealTimers();
    }
  });
});
