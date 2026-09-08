import { beforeEach, describe, expect, it, vi } from "vitest";

import type { ActiveRun, RunnerClient } from "@/repositories/contracts";

const listRunsMock = vi.fn();

vi.mock("@/repositories", () => ({
  runnerClientFor: () =>
    ({
      mode: "http",
      baseUrl: null,
      health: vi.fn(),
      createRun: vi.fn(),
      currentRun: vi.fn(),
      listRuns: (...args: unknown[]) => listRunsMock(...args),
      subscribe: vi.fn(() => () => {}),
      stopRun: vi.fn(),
    }) satisfies RunnerClient,
}));

// Imported after the mock so the store picks up the mocked repository module.
const { useActiveRunsStore } = await import("./useActiveRunsStore");

describe("useActiveRunsStore.refresh", () => {
  beforeEach(() => {
    listRunsMock.mockReset();
    useActiveRunsStore.setState({ runs: [] });
  });

  it("skips an overlapping call rather than running it alongside the one still in flight", async () => {
    const run: ActiveRun = {
      runId: "run-1",
      clusterId: "c1",
      blueprintId: "bp-1",
      status: "running",
    };
    let resolveFirst!: (runs: ActiveRun[]) => void;
    const first = new Promise<ActiveRun[]>((resolve) => (resolveFirst = resolve));
    listRunsMock.mockReturnValueOnce(first);

    const firstCall = useActiveRunsStore.getState().refresh();
    const secondCall = useActiveRunsStore.getState().refresh();

    expect(listRunsMock).toHaveBeenCalledTimes(1);

    resolveFirst([run]);
    await Promise.all([firstCall, secondCall]);

    expect(useActiveRunsStore.getState().runs).toEqual([run]);

    // The guard must not outlive the call it guarded.
    listRunsMock.mockResolvedValueOnce([]);
    await useActiveRunsStore.getState().refresh();
    expect(listRunsMock).toHaveBeenCalledTimes(2);
  });
});
