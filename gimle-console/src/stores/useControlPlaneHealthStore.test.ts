import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => ({
  controlPlaneHealthRepo: {
    fetch: vi.fn(),
  },
}));

import { controlPlaneHealthRepo } from "@/repositories";
import { useControlPlaneHealthStore } from "./useControlPlaneHealthStore";
import type { ControlPlaneStatus } from "@/types";

function status(overrides: Partial<ControlPlaneStatus> = {}): ControlPlaneStatus {
  const up = { status: "UP" as const, lastRunAt: "2026-09-15T00:00:00Z", detail: null };
  return {
    reconcilerLeader: true,
    scheduler: up,
    quotaEnforcer: up,
    heartbeatWorker: up,
    artifactResolver: up,
    ...overrides,
  };
}

describe("useControlPlaneHealthStore", () => {
  beforeEach(() => {
    useControlPlaneHealthStore.setState({ status: null, loading: false, error: null });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("load stores the fetched status and clears loading", async () => {
    vi.mocked(controlPlaneHealthRepo.fetch).mockResolvedValueOnce(status());

    await useControlPlaneHealthStore.getState().load();

    const state = useControlPlaneHealthStore.getState();
    expect(state.status).toEqual(status());
    expect(state.loading).toBe(false);
    expect(state.error).toBeNull();
  });

  it("load surfaces a repository rejection (e.g. the store being down) as store.error", async () => {
    vi.mocked(controlPlaneHealthRepo.fetch).mockRejectedValueOnce(
      new Error("control plane responded 503: store unreachable"),
    );

    await useControlPlaneHealthStore.getState().load();

    const state = useControlPlaneHealthStore.getState();
    expect(state.error).toContain("503");
    expect(state.loading).toBe(false);
    expect(state.status).toBeNull();
  });

  it("load is a no-op while one is already in flight", async () => {
    useControlPlaneHealthStore.setState({ loading: true });

    await useControlPlaneHealthStore.getState().load();

    expect(controlPlaneHealthRepo.fetch).not.toHaveBeenCalled();
  });

  it("poll replaces the status without ever setting loading", async () => {
    useControlPlaneHealthStore.setState({ status: status({ reconcilerLeader: false }) });
    vi.mocked(controlPlaneHealthRepo.fetch).mockResolvedValueOnce(status());

    await useControlPlaneHealthStore.getState().poll();

    expect(useControlPlaneHealthStore.getState().status).toEqual(status());
    expect(useControlPlaneHealthStore.getState().loading).toBe(false);
  });

  it("poll surfaces a rejection as store.error without clearing the last-known status", async () => {
    useControlPlaneHealthStore.setState({ status: status() });
    vi.mocked(controlPlaneHealthRepo.fetch).mockRejectedValueOnce(new Error("timed out"));

    await useControlPlaneHealthStore.getState().poll();

    const state = useControlPlaneHealthStore.getState();
    expect(state.error).toBe("timed out");
    expect(state.status).toEqual(status());
  });
});
