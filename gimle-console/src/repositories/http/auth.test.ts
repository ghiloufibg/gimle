import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpAuthRepository } from "./auth";
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
});
