// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { ClusterConnection } from "@/repositories/contracts";

const listMock = vi.fn();
const getMock = vi.fn();
const saveMock = vi.fn();
const deleteMock = vi.fn();

vi.mock("@/repositories", () => ({
  clustersRepository: {
    list: (...args: unknown[]) => listMock(...args),
    get: (...args: unknown[]) => getMock(...args),
    save: (...args: unknown[]) => saveMock(...args),
    delete: (...args: unknown[]) => deleteMock(...args),
  },
  checkClusterStatus: vi.fn(),
}));

// Imported after the mock so the store picks up the mocked repository module.
const { useClustersStore } = await import("./useClustersStore");

const cluster: ClusterConnection = {
  id: "c1",
  name: "one",
  environment: "local",
  controlPlaneUrl: "http://127.0.0.1:8080",
  runnerUrl: null,
  clientCertPath: "",
  clientKeyPath: "",
  description: "",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

beforeEach(() => {
  listMock.mockReset();
  getMock.mockReset();
  saveMock.mockReset();
  deleteMock.mockReset();
  listMock.mockResolvedValue([cluster]);
  useClustersStore.setState({
    clusters: [],
    selectedId: null,
    status: {},
    checking: null,
    error: null,
    errorTitle: null,
  });
});

// Every action used to fall back to the same generic `error` field, so a failed delete or save
// surfaced under whatever title `refresh`'s own failure would have used ("Couldn't load
// clusters") even though the list itself had loaded fine -- each action now carries its own title.
describe("useClustersStore error titles", () => {
  it("refresh failure is titled as a load failure", async () => {
    listMock.mockRejectedValueOnce(new Error("boom"));

    await useClustersStore.getState().refresh();

    expect(useClustersStore.getState().error).toBe("boom");
    expect(useClustersStore.getState().errorTitle).toBe("Couldn't load clusters");
  });

  it("save failure is titled as a save failure, not a load failure", async () => {
    saveMock.mockRejectedValueOnce(new Error("409 conflict"));

    await useClustersStore.getState().save(cluster);

    expect(useClustersStore.getState().error).toBe("409 conflict");
    expect(useClustersStore.getState().errorTitle).toBe("Couldn't save cluster");
  });

  it("patch failure is titled as a save failure", async () => {
    getMock.mockResolvedValueOnce(cluster);
    saveMock.mockRejectedValueOnce(new Error("boom"));

    await useClustersStore.getState().patch("c1", { name: "renamed" });

    expect(useClustersStore.getState().errorTitle).toBe("Couldn't save cluster");
  });

  it("remove failure is titled as a delete failure -- the exact regression this covers", async () => {
    deleteMock.mockRejectedValueOnce(
      new Error("cluster c1 has 1 run(s) this process is tracking -- stop it first"),
    );

    await useClustersStore.getState().remove("c1");

    expect(useClustersStore.getState().error).toContain("stop it first");
    expect(useClustersStore.getState().errorTitle).toBe("Couldn't delete cluster");
  });

  it("a successful action clears both error and errorTitle", async () => {
    useClustersStore.setState({ error: "boom", errorTitle: "Couldn't load clusters" });

    await useClustersStore.getState().refresh();

    expect(useClustersStore.getState().error).toBeNull();
    expect(useClustersStore.getState().errorTitle).toBeNull();
  });
});

// A private window, blocked storage, or a full quota must never stop a cluster choice from
// working -- persisting it is best-effort only.
describe("useClustersStore localStorage resilience", () => {
  afterEach(() => vi.restoreAllMocks());

  it("select still updates the selected cluster even when persisting it throws", () => {
    vi.spyOn(window.localStorage, "setItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });

    useClustersStore.getState().select("c1");

    expect(useClustersStore.getState().selectedId).toBe("c1");
  });

  it("selectFor still updates the selected cluster even when persisting it throws", () => {
    vi.spyOn(window.localStorage, "setItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });

    useClustersStore.getState().selectFor("bp-1", "c1");

    expect(useClustersStore.getState().selectedId).toBe("c1");
  });

  it("refresh still loads the cluster list even when reading the stored selection throws", async () => {
    vi.spyOn(window.localStorage, "getItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });

    await useClustersStore.getState().refresh();

    expect(useClustersStore.getState().clusters).toEqual([cluster]);
    expect(useClustersStore.getState().error).toBeNull();
  });
});
