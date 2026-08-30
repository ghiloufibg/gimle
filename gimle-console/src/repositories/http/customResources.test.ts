import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpCustomResourcesRepository } from "./customResources";
import { ApiError } from "./apiClient";
import { jsonResponse, textResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

const greetingDefinition = {
  kindName: "custom.Greeting",
  scope: "Tenant",
  description: "A greeting this cluster should keep saying",
  names: { plural: "greetings", shortNames: ["gr"] },
  schema: { fields: [{ name: "message", type: "string", required: true }] },
  printColumns: [{ name: "MESSAGE", path: "spec.message" }],
  generation: 1,
};

const helloWorld = {
  kind: "custom.Greeting",
  name: "hello-world",
  tenantId: "team-a",
  generation: 2,
  spec: { message: "hello", repeat: 3, tone: "friendly" },
  status: { timesSaid: 3, observedGeneration: 2 },
};

describe("HttpCustomResourcesRepository", () => {
  it("fetchKinds GETs /kinddefinitions and returns the definition array verbatim", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse([greetingDefinition])]);
    const repo = new HttpCustomResourcesRepository();

    const kinds = await repo.fetchKinds();

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/kinddefinitions");
    expect(init.method).toBe("GET");
    expect(kinds).toEqual([greetingDefinition]);
  });

  it("fetchResources GETs /resources/{kind} and returns spec and status untouched", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse([helloWorld])]);
    const repo = new HttpCustomResourcesRepository();

    const resources = await repo.fetchResources("custom.Greeting");

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/resources/custom.Greeting");
    expect(resources).toEqual([helloWorld]);
  });

  it("fetchResources percent-encodes the kind name rather than splicing it into the path raw", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse([])]);
    const repo = new HttpCustomResourcesRepository();

    await repo.fetchResources("custom.a/b");

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/resources/custom.a%2Fb");
  });

  it("fetchResources propagates the server's 400 for an unknown kind", async () => {
    stubFetchSequence([() => textResponse("unknown kind: custom.Nope", 400)]);
    const repo = new HttpCustomResourcesRepository();

    await expect(repo.fetchResources("custom.Nope")).rejects.toMatchObject(
      new ApiError(400, "unknown kind: custom.Nope"),
    );
  });

  it("fetchKinds propagates an authorization failure rather than masking it as empty", async () => {
    stubFetchSequence([() => textResponse("forbidden", 403)]);
    const repo = new HttpCustomResourcesRepository();

    await expect(repo.fetchKinds()).rejects.toMatchObject(new ApiError(403, "forbidden"));
  });
});
