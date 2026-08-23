import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => ({
  secretMapsRepo: {
    fetchNames: vi.fn(),
    fetchOne: vi.fn(),
    set: vi.fn(),
    remove: vi.fn(),
  },
}));

import { secretMapsRepo } from "@/repositories";
import { useSecretMapsStore } from "./useSecretMapsStore";

describe("useSecretMapsStore", () => {
  beforeEach(() => {
    useSecretMapsStore.setState({
      tenantId: "acme",
      names: [],
      loading: false,
      error: null,
      selected: null,
      lastSetResults: null,
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("loadNames surfaces a repository rejection as store.error and clears loading", async () => {
    vi.mocked(secretMapsRepo.fetchNames).mockRejectedValueOnce(new Error("store unreachable"));

    await useSecretMapsStore.getState().loadNames();

    const state = useSecretMapsStore.getState();
    expect(state.error).toBe("store unreachable");
    expect(state.loading).toBe(false);
  });

  it("select(null) clears the selection without calling the repository", async () => {
    useSecretMapsStore.setState({
      selected: { tenantId: "acme", name: "db-creds", keys: [] },
    });

    await useSecretMapsStore.getState().select(null);

    expect(useSecretMapsStore.getState().selected).toBeNull();
    expect(secretMapsRepo.fetchOne).not.toHaveBeenCalled();
  });

  it("save calls set with no expectedVersion concept, then re-fetches the refreshed metadata", async () => {
    vi.mocked(secretMapsRepo.set).mockResolvedValueOnce([{ key: "username", version: 1 }]);
    vi.mocked(secretMapsRepo.fetchOne).mockResolvedValueOnce({
      tenantId: "acme",
      name: "db-creds",
      keys: [{ key: "username", latestVersion: 1, deleted: false }],
    });

    await useSecretMapsStore.getState().save("db-creds", { username: "admin" });

    expect(secretMapsRepo.set).toHaveBeenCalledWith("acme", "db-creds", { username: "admin" });
    const state = useSecretMapsStore.getState();
    expect(state.names).toContain("db-creds");
    expect(state.selected?.keys).toEqual([{ key: "username", latestVersion: 1, deleted: false }]);
    expect(state.lastSetResults).toEqual([{ key: "username", version: 1 }]);
  });

  it("save surfaces a per-key failure in lastSetResults rather than throwing", async () => {
    vi.mocked(secretMapsRepo.set).mockResolvedValueOnce([
      { key: "username", version: 2 },
      { key: "password", error: "write contention" },
    ]);
    vi.mocked(secretMapsRepo.fetchOne).mockResolvedValueOnce({
      tenantId: "acme",
      name: "db-creds",
      keys: [{ key: "username", latestVersion: 2, deleted: false }],
    });

    await useSecretMapsStore.getState().save("db-creds", { username: "admin", password: "x" });

    const state = useSecretMapsStore.getState();
    expect(state.error).toBeNull();
    expect(state.lastSetResults?.find((r) => r.key === "password")?.error).toBe(
      "write contention",
    );
  });

  it("save surfaces a repository-level rejection as store.error", async () => {
    vi.mocked(secretMapsRepo.set).mockRejectedValueOnce(new Error("forbidden"));

    await useSecretMapsStore.getState().save("db-creds", { username: "admin" });

    expect(useSecretMapsStore.getState().error).toBe("forbidden");
  });

  it("remove clears the selection when the removed name was selected", async () => {
    useSecretMapsStore.setState({
      names: ["db-creds"],
      selected: { tenantId: "acme", name: "db-creds", keys: [] },
    });
    vi.mocked(secretMapsRepo.remove).mockResolvedValueOnce(undefined);

    await useSecretMapsStore.getState().remove("db-creds");

    const state = useSecretMapsStore.getState();
    expect(state.names).toEqual([]);
    expect(state.selected).toBeNull();
  });
});
