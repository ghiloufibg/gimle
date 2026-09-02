import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => {
  const page = { fetchPage: vi.fn(), fetchRevisions: vi.fn(), rollback: vi.fn() };
  return {
    deploymentsRepo: page,
    statefulSetsRepo: { fetchPage: vi.fn(), fetchRevisions: vi.fn(), rollback: vi.fn() },
    daemonSetsRepo: { fetchPage: vi.fn(), fetchRevisions: vi.fn(), rollback: vi.fn() },
    jobsRepo: { fetchPage: vi.fn() },
    cronJobsRepo: { fetchPage: vi.fn() },
    servicesRepo: { fetchAll: vi.fn() },
    customResourcesRepo: { fetchKinds: vi.fn(), fetchResources: vi.fn() },
  };
});

import {
  cronJobsRepo,
  customResourcesRepo,
  daemonSetsRepo,
  deploymentsRepo,
  jobsRepo,
  servicesRepo,
  statefulSetsRepo,
} from "@/repositories";
import type { Deployment } from "@/types";
import { useApplicationsStore } from "@/addons/applications/store";

const EMPTY_PAGE = { items: [], nextCursor: null };

function deployment(name: string): Deployment {
  return {
    spec: {
      name,
      moduleId: { name, version: "1.0.0" },
      artifactPath: "",
      replicas: 1,
      tenantId: null,
    },
    instances: [],
    unplacedCount: 1,
    quotaViolating: false,
    limitRangeViolating: false,
  };
}

function allEmpty() {
  vi.mocked(deploymentsRepo.fetchPage).mockResolvedValue(EMPTY_PAGE);
  vi.mocked(statefulSetsRepo.fetchPage).mockResolvedValue(EMPTY_PAGE);
  vi.mocked(daemonSetsRepo.fetchPage).mockResolvedValue(EMPTY_PAGE);
  vi.mocked(jobsRepo.fetchPage).mockResolvedValue(EMPTY_PAGE);
  vi.mocked(cronJobsRepo.fetchPage).mockResolvedValue(EMPTY_PAGE);
  vi.mocked(servicesRepo.fetchAll).mockResolvedValue([]);
  vi.mocked(customResourcesRepo.fetchKinds).mockResolvedValue([]);
  vi.mocked(customResourcesRepo.fetchResources).mockResolvedValue([]);
}

describe("useApplicationsStore", () => {
  beforeEach(() => {
    useApplicationsStore.setState({
      applications: [],
      partialFailures: [],
      loading: false,
      loaded: false,
      error: null,
      revisions: [],
      revisionsKey: null,
    });
    allEmpty();
  });

  afterEach(() => vi.clearAllMocks());

  it("folds every kind's read into one application list", async () => {
    vi.mocked(deploymentsRepo.fetchPage).mockResolvedValue({
      items: [deployment("orders")],
      nextCursor: null,
    });
    await useApplicationsStore.getState().load();

    const state = useApplicationsStore.getState();
    expect(state.applications.map((a) => a.name)).toEqual(["orders"]);
    expect(state.loading).toBe(false);
    expect(state.loaded).toBe(true);
  });

  it("surfaces a failed read as an error and clears loading", async () => {
    vi.mocked(deploymentsRepo.fetchPage).mockRejectedValueOnce(
      new Error("control plane unreachable"),
    );
    await useApplicationsStore.getState().load();

    const state = useApplicationsStore.getState();
    expect(state.error).toBe("control plane unreachable");
    expect(state.loading).toBe(false);
  });

  it("names a custom kind whose own read failed rather than dropping it silently", async () => {
    vi.mocked(customResourcesRepo.fetchKinds).mockResolvedValue([
      {
        kindName: "custom.Greeting",
        scope: "Tenant",
        description: "",
        names: { shortNames: [] },
        schema: { fields: [] },
        printColumns: [],
        generation: 1,
      },
    ]);
    vi.mocked(customResourcesRepo.fetchResources).mockRejectedValueOnce(new Error("403"));
    await useApplicationsStore.getState().load();

    expect(useApplicationsStore.getState().partialFailures).toEqual(["custom.Greeting"]);
    expect(useApplicationsStore.getState().error).toBeNull();
  });

  it("polls without raising loading, keeping the last good list when a read fails", async () => {
    vi.mocked(deploymentsRepo.fetchPage).mockResolvedValue({
      items: [deployment("orders")],
      nextCursor: null,
    });
    await useApplicationsStore.getState().load();
    vi.mocked(deploymentsRepo.fetchPage).mockRejectedValueOnce(new Error("read timed out"));

    await useApplicationsStore.getState().poll();

    const state = useApplicationsStore.getState();
    expect(state.loading).toBe(false);
    expect(state.applications.map((a) => a.name)).toEqual(["orders"]);
    expect(state.error).toBe("read timed out");
  });

  it("reads revision history only for a kind that has one", async () => {
    await useApplicationsStore.getState().loadRevisions("cronjob", "nightly-report", null);
    expect(statefulSetsRepo.fetchRevisions).not.toHaveBeenCalled();
    expect(useApplicationsStore.getState().revisions).toEqual([]);

    vi.mocked(daemonSetsRepo.fetchRevisions).mockResolvedValue([
      {
        revision: 2,
        createdAtEpochMilli: 1,
        moduleId: { name: "log-shipper", version: "0.4.0" },
        artifactPath: "",
      },
    ]);
    await useApplicationsStore.getState().loadRevisions("daemonset", "log-shipper", null);
    expect(useApplicationsStore.getState().revisions).toHaveLength(1);
  });

  it("discards a revision response for an application already navigated away from", async () => {
    let release: (value: []) => void = () => {};
    vi.mocked(deploymentsRepo.fetchRevisions).mockReturnValueOnce(
      new Promise((resolve) => {
        release = resolve;
      }),
    );
    const slow = useApplicationsStore.getState().loadRevisions("deployment", "orders", null);
    await useApplicationsStore.getState().loadRevisions("cronjob", "other", null);
    release([]);
    await slow;

    expect(useApplicationsStore.getState().revisionsKey).toBe("cronjob//other");
  });

  it("re-reads the whole cluster after a rollback, since admission runs again", async () => {
    vi.mocked(deploymentsRepo.rollback).mockResolvedValue({
      revision: 3,
      createdAtEpochMilli: 1,
      moduleId: { name: "orders", version: "1.0.0" },
      artifactPath: "",
    });
    vi.mocked(deploymentsRepo.fetchRevisions).mockResolvedValue([]);

    await useApplicationsStore.getState().rollback("deployment", "orders", "acme", 2);

    expect(deploymentsRepo.rollback).toHaveBeenCalledWith("orders", 2, "acme");
    expect(deploymentsRepo.fetchPage).toHaveBeenCalled();
  });
});
