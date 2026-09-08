// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from "vitest";

import { createBlueprint } from "@/lib/blueprint";
import type { ClusterConnection, RunnerClient, RunSnapshot } from "@/repositories/contracts";

const createRunMock = vi.fn();
const healthMock = vi.fn();

vi.mock("@/repositories", () => ({
  runnerClient: { mode: "http", baseUrl: null } as RunnerClient,
  runnerClientFor: () =>
    ({
      mode: "http",
      baseUrl: null,
      health: (...args: unknown[]) => healthMock(...args),
      createRun: (...args: unknown[]) => createRunMock(...args),
      currentRun: vi.fn(),
      listRuns: vi.fn(),
      subscribe: vi.fn(() => () => {}),
      stopRun: vi.fn(),
    }) satisfies RunnerClient,
  hilmirValidator: { mode: "http", baseUrl: null, validate: vi.fn() },
}));

// Imported after the mock so the store picks up the mocked repository module.
const { useRunStore } = await import("./useRunStore");
const { useValidationStore } = await import("./useValidationStore");
const { useClustersStore } = await import("./useClustersStore");

function cluster(id: string): ClusterConnection {
  return {
    id,
    name: id,
    environment: "local",
    controlPlaneUrl: "http://127.0.0.1:8080",
    runnerUrl: null,
    clientCertPath: "",
    clientKeyPath: "",
    description: "",
    createdAt: "",
    updatedAt: "",
  };
}

describe("useRunStore.start", () => {
  beforeEach(() => {
    createRunMock.mockReset();
    localStorage.clear();
    useRunStore.setState({
      runId: null,
      blueprintId: null,
      status: "idle",
      busy: false,
      reason: null,
      cluster: cluster("c1"),
    });
    useClustersStore.setState({ clusters: [cluster("c1")], selectedId: "c1" });
    useValidationStore.setState({ problems: [], serverProblems: [] });
  });

  it("returns Run to a clickable state and surfaces a clear error when create-run rejects (a timed-out request, say)", async () => {
    createRunMock.mockRejectedValue(new Error("POST /api/runs timed out after 30000ms"));
    const blueprint = createBlueprint("test");

    await useRunStore.getState().start(blueprint);

    expect(useRunStore.getState().busy).toBe(false);
    expect(useRunStore.getState().status).toBe("failed");
    expect(useRunStore.getState().reason).toContain("timed out");
  });

  it("remembers the cluster a run actually started against, keyed by blueprint id, for the picker to default to next time", async () => {
    const clusterA = cluster("c1");
    const clusterB = cluster("c2");
    useClustersStore.setState({ clusters: [clusterA, clusterB], selectedId: clusterA.id });
    createRunMock.mockResolvedValue({
      runId: "run-1",
      status: "running",
      steps: [],
      endpoints: [],
      machines: [],
      artifacts: [],
      cronJobs: [],
      startedAt: "2026-01-01T00:00:00Z",
      finishedAt: null,
      error: null,
      revision: null,
    } satisfies RunSnapshot);
    const blueprint = createBlueprint("test");

    // Run against clusterB, not the global default (clusterA) -- the same shape as the Designer's
    // own "Run locally" button, which never touches the picker at all.
    await useRunStore.getState().start(blueprint, { cluster: clusterB });

    expect(useClustersStore.getState().selectedFor(blueprint.id)?.id).toBe(clusterB.id);
  });
});

describe("useRunStore.checkHealth", () => {
  beforeEach(() => {
    healthMock.mockReset();
    useRunStore.setState({ health: null, cluster: cluster("c1") });
  });

  it("skips an overlapping call rather than running it alongside the one still in flight", async () => {
    let resolveFirst!: (health: {
      ok: boolean;
      mode: "http";
      version: null;
      message: null;
    }) => void;
    const first = new Promise((resolve) => (resolveFirst = resolve));
    healthMock.mockReturnValueOnce(first);

    const firstCall = useRunStore.getState().checkHealth();
    const secondCall = useRunStore.getState().checkHealth();

    expect(healthMock).toHaveBeenCalledTimes(1);

    resolveFirst({ ok: true, mode: "http", version: null, message: null });
    await Promise.all([firstCall, secondCall]);

    expect(useRunStore.getState().health).toEqual({
      ok: true,
      mode: "http",
      version: null,
      message: null,
    });

    // The guard must not outlive the call it guarded.
    healthMock.mockResolvedValueOnce({ ok: false, mode: "http", version: null, message: "down" });
    await useRunStore.getState().checkHealth();
    expect(healthMock).toHaveBeenCalledTimes(2);
  });
});
