import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { ClusterConnection } from "./contracts";
import { HttpClustersRepository } from "./httpClusters";

const cluster: ClusterConnection = {
  id: "local-dev",
  name: "local-dev",
  environment: "local",
  controlPlaneUrl: "http://127.0.0.1:8080",
  runnerUrl: null,
  clientCertPath: "",
  clientKeyPath: "",
  description: "",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

describe("HttpClustersRepository", () => {
  const repo = new HttpClustersRepository();
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("list() returns the raw cluster array", async () => {
    fetchMock.mockResolvedValue(jsonResponse([cluster]));

    const clusters = await repo.list();

    expect(fetchMock).toHaveBeenCalledWith("/api/clusters", expect.anything());
    expect(clusters).toEqual([cluster]);
  });

  it("get() returns the stored connection, and undefined on any failure", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(cluster));
    await expect(repo.get("local-dev")).resolves.toEqual(cluster);

    fetchMock.mockResolvedValueOnce(new Response("not found", { status: 404 }));
    await expect(repo.get("missing")).resolves.toBeUndefined();
  });

  it("save() always PUTs to the cluster's own id -- there is no separate create call", async () => {
    fetchMock.mockResolvedValue(jsonResponse(cluster));

    const saved = await repo.save(cluster);

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/clusters/local-dev",
      expect.objectContaining({ method: "PUT", body: JSON.stringify(cluster) }),
    );
    expect(saved).toEqual(cluster);
  });

  it("delete() sends a DELETE and throws on a non-2xx response", async () => {
    fetchMock.mockResolvedValueOnce(new Response("", { status: 200 }));
    await expect(repo.delete("local-dev")).resolves.toBeUndefined();

    fetchMock.mockResolvedValueOnce(new Response("gone", { status: 404 }));
    await expect(repo.delete("local-dev")).rejects.toThrow();
  });
});
