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
import { SessionExpiredError } from "@/repositories/http/apiClient";
import type { Deployment } from "@/types";
import { useDeploymentsStore } from "./useDeploymentsStore";

function deployment(name: string): Deployment {
  return {
    spec: {
      name,
      moduleId: { name: "m", version: "1.0.0" },
      artifactPath: "",
      replicas: 1,
      tenantId: null,
    },
    instances: [],
    unplacedCount: 0,
    quotaViolating: false,
    limitRangeViolating: false,
  };
}

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

// poll() is what the screens' auto-refresh calls; the whole point of it being separate from
// refresh() is what it must NOT do to a screen someone is looking at.
describe("useDeploymentsStore.poll", () => {
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

  it("re-reads at least as many rows as are already on screen, so paged-in rows survive", async () => {
    const loaded = Array.from({ length: 45 }, (_, i) => deployment(`d-${i}`));
    useDeploymentsStore.setState({ items: loaded });
    vi.mocked(deploymentsRepo.fetchPage).mockResolvedValueOnce({
      items: loaded,
      nextCursor: null,
    });

    await useDeploymentsStore.getState().poll();

    expect(deploymentsRepo.fetchPage).toHaveBeenCalledWith({ cursor: null, pageSize: 45 });
  });

  it("never raises the loading flag, so nothing on the screen flickers or disables", async () => {
    vi.mocked(deploymentsRepo.fetchPage).mockResolvedValueOnce({
      items: [deployment("a")],
      nextCursor: null,
    });

    const inFlight = useDeploymentsStore.getState().poll();
    expect(useDeploymentsStore.getState().loading).toBe(false);
    await inFlight;
    expect(useDeploymentsStore.getState().loading).toBe(false);
  });

  it("keeps the last good rows when a poll fails, and says why", async () => {
    useDeploymentsStore.setState({ items: [deployment("a")] });
    vi.mocked(deploymentsRepo.fetchPage).mockRejectedValueOnce(
      new Error("control plane unreachable"),
    );

    await useDeploymentsStore.getState().poll();

    const state = useDeploymentsStore.getState();
    expect(state.items).toHaveLength(1);
    expect(state.error).toBe("control plane unreachable");
  });

  it("shows no error banner when the failure is an expired session", async () => {
    useDeploymentsStore.setState({ items: [deployment("a")] });
    vi.mocked(deploymentsRepo.fetchPage).mockRejectedValueOnce(
      new SessionExpiredError("not authenticated"),
    );

    await useDeploymentsStore.getState().poll();

    const state = useDeploymentsStore.getState();
    expect(state.error).toBeNull();
    expect(state.items).toHaveLength(1);
  });

  it("stands aside while a manual load is already in flight", async () => {
    useDeploymentsStore.setState({ loading: true });

    await useDeploymentsStore.getState().poll();

    expect(deploymentsRepo.fetchPage).not.toHaveBeenCalled();
  });

  it("clears a stale error once a poll succeeds again", async () => {
    useDeploymentsStore.setState({ error: "control plane unreachable" });
    vi.mocked(deploymentsRepo.fetchPage).mockResolvedValueOnce({
      items: [deployment("a")],
      nextCursor: "next",
    });

    await useDeploymentsStore.getState().poll();

    const state = useDeploymentsStore.getState();
    expect(state.error).toBeNull();
    expect(state.hasMore).toBe(true);
  });
});
