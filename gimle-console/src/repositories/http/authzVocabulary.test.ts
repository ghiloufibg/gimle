import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpAuthzVocabularyRepository } from "./authzVocabulary";
import { jsonResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("HttpAuthzVocabularyRepository", () => {
  it("GETs /authz/vocabulary and returns the served kinds and verbs verbatim", async () => {
    const fetchMock = stubFetchSequence([
      () =>
        jsonResponse({
          resourceKinds: ["DEPLOYMENT", "CONFIGMAP", "ALERT_RULE"],
          verbs: ["READ", "WRITE", "DELETE", "APPROVE"],
        }),
    ]);
    const repo = new HttpAuthzVocabularyRepository();

    const vocabulary = await repo.fetch();

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/authz/vocabulary");
    expect(init.method).toBe("GET");
    expect(vocabulary).toEqual({
      resourceKinds: ["DEPLOYMENT", "CONFIGMAP", "ALERT_RULE"],
      verbs: ["READ", "WRITE", "DELETE", "APPROVE"],
    });
  });

  it("carries a kind this build's own union predates, rather than dropping it", async () => {
    stubFetchSequence([
      () => jsonResponse({ resourceKinds: ["DEPLOYMENT", "SOMETHING_NEW"], verbs: ["READ"] }),
    ]);
    const repo = new HttpAuthzVocabularyRepository();

    expect((await repo.fetch()).resourceKinds).toContain("SOMETHING_NEW");
  });

  it("reads an absent field as empty rather than undefined, so the caller can fall back", async () => {
    stubFetchSequence([() => jsonResponse({})]);
    const repo = new HttpAuthzVocabularyRepository();

    expect(await repo.fetch()).toEqual({ resourceKinds: [], verbs: [] });
  });

  it("rejects on an unauthenticated response rather than reporting an empty vocabulary", async () => {
    stubFetchSequence([() => new Response("authentication required", { status: 401 })]);
    const repo = new HttpAuthzVocabularyRepository();

    await expect(repo.fetch()).rejects.toThrow();
  });

  it("follows a Raft leader redirect like every other proxied read", async () => {
    const fetchMock = stubFetchSequence([
      () =>
        jsonResponse(
          { error: "not-leader", leaderRaftId: "cp-2", leaderApiAddress: "10.0.0.2:8080" },
          307,
        ),
      () => jsonResponse({ resourceKinds: ["DEPLOYMENT"], verbs: ["READ"] }),
    ]);
    const repo = new HttpAuthzVocabularyRepository();

    expect((await repo.fetch()).resourceKinds).toEqual(["DEPLOYMENT"]);
    expect(fetchMock.mock.calls[1]?.[0]).toBe("http://10.0.0.2:8080/authz/vocabulary");
  });
});
