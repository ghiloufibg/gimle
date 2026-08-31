import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Every store in this app follows the identical catch-block pattern (set loading:false,
// error: e.message) around its repository calls -- one representative store is enough to pin
// down that pattern is actually wired correctly, rather than repeating this per store.
vi.mock("@/repositories", () => ({
  deploymentsRepo: {
    fetchPage: vi.fn(),
    fetchOne: vi.fn(),
    fetchRevisions: vi.fn(),
    rollback: vi.fn(),
  },
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

  it("loadRevisions populates revisions from the repository", async () => {
    vi.mocked(deploymentsRepo.fetchRevisions).mockResolvedValueOnce([
      {
        revision: 2,
        createdAtEpochMilli: 2000,
        moduleId: { name: "m", version: "2.0.0" },
        artifactPath: "",
      },
      {
        revision: 1,
        createdAtEpochMilli: 1000,
        moduleId: { name: "m", version: "1.0.0" },
        artifactPath: "",
      },
    ]);

    await useDeploymentsStore.getState().loadRevisions("checkout-service");

    expect(useDeploymentsStore.getState().revisions).toHaveLength(2);
  });

  it("loadRevisions surfaces a repository rejection as store.error", async () => {
    vi.mocked(deploymentsRepo.fetchRevisions).mockRejectedValueOnce(
      new Error("no such deployment"),
    );

    await useDeploymentsStore.getState().loadRevisions("checkout-service");

    expect(useDeploymentsStore.getState().error).toBe("no such deployment");
  });

  it("rollback re-fetches the item and its revisions, updating both in the store", async () => {
    useDeploymentsStore.setState({
      items: [
        {
          spec: {
            name: "checkout-service",
            moduleId: { name: "m", version: "1.0.0" },
            artifactPath: "",
            replicas: 1,
            tenantId: null,
          },
          instances: [],
          unplacedCount: 0,
          quotaViolating: false,
          limitRangeViolating: false,
        },
      ],
    });
    vi.mocked(deploymentsRepo.rollback).mockResolvedValueOnce({
      revision: 2,
      createdAtEpochMilli: 2000,
      rollbackOfRevision: 1,
      moduleId: { name: "m", version: "0.9.0" },
      artifactPath: "",
    });
    vi.mocked(deploymentsRepo.fetchOne).mockResolvedValueOnce({
      spec: {
        name: "checkout-service",
        moduleId: { name: "m", version: "0.9.0" },
        artifactPath: "",
        replicas: 1,
        tenantId: null,
      },
      instances: [],
      unplacedCount: 0,
      quotaViolating: false,
      limitRangeViolating: false,
    });
    vi.mocked(deploymentsRepo.fetchRevisions).mockResolvedValueOnce([
      {
        revision: 2,
        createdAtEpochMilli: 2000,
        rollbackOfRevision: 1,
        moduleId: { name: "m", version: "0.9.0" },
        artifactPath: "",
      },
    ]);

    await useDeploymentsStore.getState().rollback("checkout-service", 1);

    const state = useDeploymentsStore.getState();
    expect(state.items[0].spec.moduleId.version).toBe("0.9.0");
    expect(state.revisions).toHaveLength(1);
  });

  it("rollback surfaces a repository rejection as store.error", async () => {
    vi.mocked(deploymentsRepo.rollback).mockRejectedValueOnce(new Error("no such revision"));

    await useDeploymentsStore.getState().rollback("checkout-service", 99);

    expect(useDeploymentsStore.getState().error).toBe("no such revision");
  });
});
