import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { Blueprint } from "@/lib/blueprint";
import { HttpBlueprintsRepository } from "./httpBlueprints";

const blueprint: Blueprint = {
  id: "",
  name: "test-blueprint",
  version: "1.0.0",
  transport: "plaintext",
  runtime: { dataRoot: "~/.gimle/ivaldi/data" },
  nodes: [],
  edges: [],
  updatedAt: "2026-01-01T00:00:00Z",
};

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

describe("HttpBlueprintsRepository", () => {
  const repo = new HttpBlueprintsRepository();
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("list() maps the raw summary array and defaults missing fields", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse([{ id: "b1", name: "one", version: "1.0.0", updatedAt: "t" }, { id: "b2" }]),
    );

    const summaries = await repo.list();

    expect(fetchMock).toHaveBeenCalledWith("/api/blueprints", expect.anything());
    expect(summaries).toEqual([
      { id: "b1", name: "one", version: "1.0.0", updatedAt: "t" },
      { id: "b2", name: "(unnamed)", version: "1.0.0", updatedAt: "" },
    ]);
  });

  it("get() returns the raw Blueprint body on 200, and undefined on any failure", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(blueprint));
    await expect(repo.get("b1")).resolves.toEqual(blueprint);

    fetchMock.mockResolvedValueOnce(new Response("not found", { status: 404 }));
    await expect(repo.get("missing")).resolves.toBeUndefined();
  });

  it("create() POSTs the full document and returns the server-minted summary", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        id: "test-blueprint",
        name: "test-blueprint",
        version: "1.0.0",
        updatedAt: "t",
      }),
    );

    const summary = await repo.create(blueprint);

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/blueprints",
      expect.objectContaining({ method: "POST", body: JSON.stringify(blueprint) }),
    );
    expect(summary.id).toBe("test-blueprint");
  });

  it("save() PUTs to the blueprint's own id", async () => {
    const withId = { ...blueprint, id: "test-blueprint" };
    fetchMock.mockResolvedValue(
      jsonResponse({
        id: "test-blueprint",
        name: "test-blueprint",
        version: "1.0.0",
        updatedAt: "t",
      }),
    );

    await repo.save(withId);

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/blueprints/test-blueprint",
      expect.objectContaining({ method: "PUT" }),
    );
  });

  it("delete() sends a DELETE and throws ApiError on a non-2xx response", async () => {
    fetchMock.mockResolvedValueOnce(new Response("", { status: 200 }));
    await expect(repo.delete("b1")).resolves.toBeUndefined();

    fetchMock.mockResolvedValueOnce(new Response("gone", { status: 404 }));
    await expect(repo.delete("b1")).rejects.toThrow();
  });
});
