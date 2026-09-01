import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => ({
  metricsRepo: { fetchRollup: vi.fn() },
}));

import { metricsRepo } from "@/repositories";
import { useMetricsRollupStore } from "./useMetricsRollupStore";

const ROW = {
  tenantId: null,
  deploymentName: "greeter-provider",
  instanceCount: 2,
  avgRequestRatePerSecond: 42.5,
  avgErrorRatePerSecond: 0.25,
};

describe("useMetricsRollupStore", () => {
  beforeEach(() => {
    useMetricsRollupStore.setState({ rows: [], loaded: false, loading: false, error: null });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("loads the rollup and marks itself loaded", async () => {
    vi.mocked(metricsRepo.fetchRollup).mockResolvedValueOnce([ROW]);

    await useMetricsRollupStore.getState().load();

    const state = useMetricsRollupStore.getState();
    expect(state.rows).toEqual([ROW]);
    expect(state.loaded).toBe(true);
    expect(state.loading).toBe(false);
    expect(state.error).toBeNull();
  });

  it("surfaces a repository rejection as store.error and stops loading", async () => {
    vi.mocked(metricsRepo.fetchRollup).mockRejectedValueOnce(
      new Error("control plane responded 403"),
    );

    await useMetricsRollupStore.getState().load();

    const state = useMetricsRollupStore.getState();
    expect(state.error).toBe("control plane responded 403");
    expect(state.loading).toBe(false);
    expect(state.loaded).toBe(false);
  });

  it("keeps the previously loaded rows when a refresh fails", async () => {
    vi.mocked(metricsRepo.fetchRollup).mockResolvedValueOnce([ROW]);
    await useMetricsRollupStore.getState().load();

    vi.mocked(metricsRepo.fetchRollup).mockRejectedValueOnce(new Error("connection refused"));
    await useMetricsRollupStore.getState().load();

    expect(useMetricsRollupStore.getState().rows).toEqual([ROW]);
    expect(useMetricsRollupStore.getState().error).toBe("connection refused");
  });

  it("clears a stale error on the next successful load", async () => {
    useMetricsRollupStore.setState({ error: "connection refused" });
    vi.mocked(metricsRepo.fetchRollup).mockResolvedValueOnce([ROW]);

    await useMetricsRollupStore.getState().load();

    expect(useMetricsRollupStore.getState().error).toBeNull();
  });

  it("does not issue a second fetch while one is already in flight", async () => {
    let release: (rows: (typeof ROW)[]) => void = () => {};
    vi.mocked(metricsRepo.fetchRollup).mockReturnValueOnce(
      new Promise((resolve) => {
        release = resolve;
      }),
    );

    const first = useMetricsRollupStore.getState().load();
    await useMetricsRollupStore.getState().load();
    release([ROW]);
    await first;

    expect(metricsRepo.fetchRollup).toHaveBeenCalledTimes(1);
  });
});
