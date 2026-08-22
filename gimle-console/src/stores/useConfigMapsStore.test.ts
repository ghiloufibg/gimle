import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ConfigMapConflict } from "@/repositories/configmaps";

vi.mock("@/repositories", () => ({
  configMapsRepo: {
    fetchNames: vi.fn(),
    fetchOne: vi.fn(),
    upsert: vi.fn(),
    remove: vi.fn(),
  },
}));

import { configMapsRepo } from "@/repositories";
import { useConfigMapsStore } from "./useConfigMapsStore";

describe("useConfigMapsStore", () => {
  beforeEach(() => {
    useConfigMapsStore.setState({
      tenantId: "acme",
      names: [],
      loading: false,
      error: null,
      selected: null,
      conflict: null,
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("loadNames surfaces a repository rejection as store.error and clears loading", async () => {
    vi.mocked(configMapsRepo.fetchNames).mockRejectedValueOnce(new Error("store unreachable"));

    await useConfigMapsStore.getState().loadNames();

    const state = useConfigMapsStore.getState();
    expect(state.error).toBe("store unreachable");
    expect(state.loading).toBe(false);
  });

  it("select(null) clears the selection without calling the repository", async () => {
    useConfigMapsStore.setState({
      selected: { tenantId: "acme", name: "app-config", version: 1, data: {} },
    });

    await useConfigMapsStore.getState().select(null);

    expect(useConfigMapsStore.getState().selected).toBeNull();
    expect(configMapsRepo.fetchOne).not.toHaveBeenCalled();
  });

  it("save on a version conflict sets store.conflict instead of throwing or setting error", async () => {
    useConfigMapsStore.setState({
      selected: { tenantId: "acme", name: "app-config", version: 1, data: { a: "old" } },
    });
    vi.mocked(configMapsRepo.upsert).mockRejectedValueOnce(
      new ConfigMapConflict(2, { a: "newer" }),
    );

    await useConfigMapsStore.getState().save("app-config", { a: "mine" });

    const state = useConfigMapsStore.getState();
    expect(state.conflict).toEqual({ currentVersion: 2, currentData: { a: "newer" } });
    expect(state.error).toBeNull();
  });

  it("save with a name that isn't the currently-selected one is treated as a new ConfigMap (expectedVersion=0)", async () => {
    vi.mocked(configMapsRepo.upsert).mockResolvedValueOnce({
      tenantId: "acme",
      name: "brand-new",
      version: 1,
      data: { a: "1" },
    });

    await useConfigMapsStore.getState().save("brand-new", { a: "1" });

    expect(configMapsRepo.upsert).toHaveBeenCalledWith("acme", "brand-new", { a: "1" }, 0);
    expect(useConfigMapsStore.getState().names).toContain("brand-new");
  });

  it("a successful save clears any previously surfaced conflict", async () => {
    useConfigMapsStore.setState({ conflict: { currentVersion: 2, currentData: {} } });
    vi.mocked(configMapsRepo.upsert).mockResolvedValueOnce({
      tenantId: "acme",
      name: "app-config",
      version: 1,
      data: {},
    });

    await useConfigMapsStore.getState().save("app-config", {});

    expect(useConfigMapsStore.getState().conflict).toBeNull();
  });

  it("remove clears the selection when the removed name was selected", async () => {
    useConfigMapsStore.setState({
      names: ["app-config"],
      selected: { tenantId: "acme", name: "app-config", version: 1, data: {} },
    });
    vi.mocked(configMapsRepo.remove).mockResolvedValueOnce(undefined);

    await useConfigMapsStore.getState().remove("app-config");

    const state = useConfigMapsStore.getState();
    expect(state.names).toEqual([]);
    expect(state.selected).toBeNull();
  });
});
