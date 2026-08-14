import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpArtifactsRepository } from "./artifacts";
import { ApiError } from "./apiClient";
import { jsonResponse, okResponse, textResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

const storedVersion = {
  moduleId: "greeter-provider",
  version: "1.1.0",
  sha256: "a".repeat(64),
  sizeBytes: 19104,
  pushedAtEpochMilli: 1_754_000_000_000,
  pushedBy: "ci-publisher",
};

describe("HttpArtifactsRepository", () => {
  it("fetchCatalog GETs /artifacts and returns the bare module id array", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse(["greeter-provider", "edge-router"])]);
    const repo = new HttpArtifactsRepository();

    const catalog = await repo.fetchCatalog();

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/artifacts");
    expect(init.method).toBe("GET");
    expect(catalog).toEqual(["greeter-provider", "edge-router"]);
  });

  it("fetchVersions GETs /artifacts/{moduleId} and unwraps the versions array", async () => {
    const fetchMock = stubFetchSequence([
      () => jsonResponse({ moduleId: "greeter-provider", versions: [storedVersion] }),
    ]);
    const repo = new HttpArtifactsRepository();

    const versions = await repo.fetchVersions("greeter-provider");

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/artifacts/greeter-provider");
    expect(versions).toEqual([storedVersion]);
  });

  it("fetchVersions percent-encodes a module id rather than splicing it into the path raw", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse({ moduleId: "a/b", versions: [] })]);
    const repo = new HttpArtifactsRepository();

    await repo.fetchVersions("a/b");

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/artifacts/a%2Fb");
  });

  it("fetchVersions maps the registry's 404 for an empty module to an empty list, not an error", async () => {
    stubFetchSequence([() => textResponse("no versions stored for gone-module", 404)]);
    const repo = new HttpArtifactsRepository();

    await expect(repo.fetchVersions("gone-module")).resolves.toEqual([]);
  });

  it("fetchVersions still propagates a non-404 failure", async () => {
    stubFetchSequence([() => textResponse("forbidden", 403)]);
    const repo = new HttpArtifactsRepository();

    await expect(repo.fetchVersions("greeter-provider")).rejects.toMatchObject(
      new ApiError(403, "forbidden"),
    );
  });

  it("remove DELETEs /artifacts/{moduleId}/{version}", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpArtifactsRepository();

    await repo.remove("greeter-provider", "1.1.0");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/artifacts/greeter-provider/1.1.0");
    expect(init.method).toBe("DELETE");
  });

  it("remove propagates the registry's 404 for a coordinate that was never stored", async () => {
    stubFetchSequence([() => textResponse("no artifact stored for x:9.9.9", 404)]);
    const repo = new HttpArtifactsRepository();

    await expect(repo.remove("x", "9.9.9")).rejects.toMatchObject(
      new ApiError(404, "no artifact stored for x:9.9.9"),
    );
  });
});
