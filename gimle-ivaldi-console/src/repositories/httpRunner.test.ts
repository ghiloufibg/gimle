import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { HttpRunnerClient } from "./httpRunner";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

const idle = { id: null, clusterId: null, status: "idle", processes: [], error: null };

describe("HttpRunnerClient.currentRun", () => {
  const client = new HttpRunnerClient("http://127.0.0.1:8079");
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => vi.unstubAllGlobals());

  it("asks the backend for the run of one blueprint, not for the global latest", async () => {
    fetchMock.mockResolvedValue(jsonResponse(idle));

    await client.currentRun("bp-orders");

    expect(fetchMock.mock.calls[0][0]).toBe(
      "http://127.0.0.1:8079/api/runs/for-blueprint/bp-orders",
    );
  });

  it("falls back to the latest run only when no blueprint is named", async () => {
    fetchMock.mockResolvedValue(jsonResponse(idle));

    await client.currentRun();

    expect(fetchMock.mock.calls[0][0]).toBe("http://127.0.0.1:8079/api/runs/current");
  });

  it("reads an idle answer as no run to re-attach to", async () => {
    fetchMock.mockResolvedValue(jsonResponse(idle));

    expect(await client.currentRun("bp-orders")).toBeNull();
  });

  it("re-attaches to a run the backend still holds for this blueprint", async () => {
    fetchMock.mockImplementation((url: string) =>
      Promise.resolve(
        url.includes("/endpoints")
          ? jsonResponse([])
          : jsonResponse({
              id: "run-1",
              clusterId: "c1",
              blueprintId: "bp-orders",
              status: "running",
              processes: [],
              error: null,
              startedAt: "2026-01-01T00:00:00Z",
              updatedAt: "2026-01-01T00:00:00Z",
            }),
      ),
    );

    const snapshot = await client.currentRun("bp-orders");

    expect(snapshot?.runId).toBe("run-1");
    expect(snapshot?.status).toBe("running");
  });
});
