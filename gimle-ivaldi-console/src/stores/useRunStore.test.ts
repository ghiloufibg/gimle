import { beforeEach, describe, expect, it, vi } from "vitest";

import { createBlueprint } from "@/lib/blueprint";
import type { RunnerClient } from "@/repositories/contracts";

const createRunMock = vi.fn();

vi.mock("@/repositories", () => ({
  runnerClient: { mode: "http", baseUrl: null } as RunnerClient,
  runnerClientFor: () =>
    ({
      mode: "http",
      baseUrl: null,
      health: vi.fn(),
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

describe("useRunStore.start", () => {
  beforeEach(() => {
    createRunMock.mockReset();
    useRunStore.setState({
      runId: null,
      blueprintId: null,
      status: "idle",
      busy: false,
      reason: null,
      cluster: {
        id: "c1",
        name: "local",
        environment: "local",
        controlPlaneUrl: "http://127.0.0.1:8080",
        runnerUrl: null,
        clientCertPath: "",
        clientKeyPath: "",
        description: "",
        createdAt: "",
        updatedAt: "",
      },
    });
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
});
