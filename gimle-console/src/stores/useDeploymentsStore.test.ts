import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Every store in this app follows the identical catch-block pattern (set loading:false,
// error: e.message) around its repository calls -- one representative store is enough to pin
// down that pattern is actually wired correctly, rather than repeating this per store.
vi.mock("@/repositories", () => ({
  deploymentsRepo: { fetchPage: vi.fn() },
}));

import { deploymentsRepo } from "@/repositories";
import { useDeploymentsStore } from "./useDeploymentsStore";

describe("useDeploymentsStore error surfacing", () => {
  beforeEach(() => {
    useDeploymentsStore.setState({
      items: [],
      nextCursor: null,
      hasMore: true,
      loading: false,
      error: null,
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("loadFirstPage surfaces a repository rejection as store.error and clears loading", async () => {
    vi.mocked(deploymentsRepo.fetchPage).mockRejectedValueOnce(
      new Error("control plane unreachable"),
    );

    await useDeploymentsStore.getState().loadFirstPage();

    const state = useDeploymentsStore.getState();
    expect(state.error).toBe("control plane unreachable");
    expect(state.loading).toBe(false);
    expect(state.items).toEqual([]);
  });

  it("a successful loadFirstPage clears any previously surfaced error", async () => {
    useDeploymentsStore.setState({ error: "stale previous error" });
    vi.mocked(deploymentsRepo.fetchPage).mockResolvedValueOnce({ items: [], nextCursor: null });

    await useDeploymentsStore.getState().loadFirstPage();

    const state = useDeploymentsStore.getState();
    expect(state.error).toBeNull();
    expect(state.loading).toBe(false);
  });
});
